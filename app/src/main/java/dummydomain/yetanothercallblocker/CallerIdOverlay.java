package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;

import com.google.android.material.color.DynamicColors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import dummydomain.yetanothercallblocker.data.CallerIdOverlayPolicy;
import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabaseItem;

/**
 * The caller ID card shown over the incoming call screen (like Truecaller does).
 *
 * <p>On recent Android versions the dialer's own incoming-call heads-up takes the
 * heads-up slot, so our notification only lands in the shade. The card is a
 * {@code TYPE_APPLICATION_OVERLAY} window placed below the dialer's heads-up; it needs
 * the "display over other apps" permission. Overlays are not shown above the lock
 * screen: there the notification remains the only caller info.</p>
 *
 * <p>The card disappears when the call is answered or ends, on any button, and after
 * {@link CallerIdOverlayPolicy#TIMEOUT_MILLIS}. It can be dragged vertically; the
 * position is remembered. All window operations run on the main thread; the public
 * methods may be called from any thread.</p>
 */
public final class CallerIdOverlay {

    private static final Logger LOG = LoggerFactory.getLogger(CallerIdOverlay.class);

    /** Distance from the status bar: the dialer's heads-up is above the card. */
    private static final int DEFAULT_OFFSET_DP = 140;
    /** The card is never wider than this (tablets, landscape). */
    private static final int MAX_WIDTH_DP = 480;
    /** Rough height of the card, used to keep a remembered position on the screen. */
    private static final int APPROX_HEIGHT_DP = 160;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    // main thread only
    private static final CallerIdOverlayPolicy.Session SESSION =
            new CallerIdOverlayPolicy.Session();
    private static WindowManager windowManager;
    private static View view;
    private static String shownNumber;

    private static final Runnable TIMEOUT = () -> {
        LOG.debug("timeout");
        hideOnMain();
    };

    private CallerIdOverlay() {
    }

    /** @return whether the app may draw over other apps */
    public static boolean canDrawOverlays(Context context) {
        try {
            return android.provider.Settings.canDrawOverlays(context);
        } catch (Exception e) {
            LOG.warn("canDrawOverlays() failed", e);
            return false;
        }
    }

    /**
     * Opens the system "display over other apps" screen for the app. Android 11+ shows
     * the list of all apps instead of the app's page: a toast tells what to look for.
     *
     * @return whether a screen was opened
     */
    public static boolean requestPermission(Context context) {
        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName()));
        boolean opened = IntentHelper.startActivity(context, intent);
        if (!opened) {
            // some devices only have the list of all apps
            opened = IntentHelper.startActivity(context,
                    new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }

        if (opened && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(context, R.string.caller_id_overlay_permission_hint,
                    Toast.LENGTH_LONG).show();
        }
        return opened;
    }

    /**
     * Shows the card for a ringing call if the settings, the permission and the call
     * allow it (see {@link CallerIdOverlayPolicy#shouldShow}). Repeated reports of the
     * same call are ignored.
     *
     * @param blocked whether the call is being blocked (then nothing is shown)
     */
    public static void show(Context context, NumberInfo numberInfo, boolean blocked) {
        if (numberInfo == null) return;

        Context appContext = context.getApplicationContext();
        Settings settings = App.getSettings();
        boolean canDraw = canDrawOverlays(appContext);
        if (!CallerIdOverlayPolicy.shouldShow(settings.getIncomingCallNotifications(),
                settings.getCallerIdOverlay(), canDraw, numberInfo.contactItem != null,
                blocked)) {
            LOG.debug("show() not shown, canDrawOverlays={}", canDraw);
            return;
        }

        runOnMain(() -> showOnMain(appContext, numberInfo));
    }

    /** Hides the card (e.g. the call was answered); it doesn't come back for this call. */
    public static void hide() {
        runOnMain(CallerIdOverlay::hideOnMain);
    }

    /** Hides the card if it shows the given caller. */
    public static void hideFor(String number) {
        runOnMain(() -> {
            if (view != null && CallerIdOverlayPolicy.sameCaller(number, shownNumber)) {
                hideOnMain();
            }
        });
    }

    /** The phone is idle: hides the card and forgets the call. */
    public static void onCallEnded() {
        runOnMain(() -> {
            removeView();
            SESSION.onCallEnded();
        });
    }

    /**
     * Always posts, even on the main thread: show, hide and "call ended" requests from
     * different threads are then handled in the order they were made.
     */
    private static void runOnMain(Runnable runnable) {
        HANDLER.post(runnable);
    }

    // main thread

    private static void showOnMain(Context appContext, NumberInfo numberInfo) {
        String number = numberInfo.noNumber ? "" : numberInfo.number;

        CallerIdOverlayPolicy.Decision decision =
                SESSION.onShowRequest(number, SystemClock.elapsedRealtime());
        LOG.debug("showOnMain() decision={}", decision);

        switch (decision) {
            case SHOW:
                removeView(); // a stale card, just in case
                if (addView(appContext, numberInfo)) {
                    shownNumber = number;
                    scheduleTimeout();
                } else {
                    SESSION.onShowFailed();
                }
                break;

            case UPDATE:
                if (view != null) {
                    try {
                        bind(appContext, view, numberInfo);
                    } catch (RuntimeException e) {
                        // a posted runnable must not crash the app during a call
                        LOG.error("showOnMain() failed to update the card", e);
                        hideOnMain();
                        break;
                    }
                    shownNumber = number;
                    scheduleTimeout();
                } else if (addView(appContext, numberInfo)) {
                    shownNumber = number;
                    scheduleTimeout();
                } else {
                    SESSION.onShowFailed();
                }
                break;

            default:
                break;
        }
    }

    private static void hideOnMain() {
        removeView();
        SESSION.onHidden();
    }

    private static void scheduleTimeout() {
        HANDLER.removeCallbacks(TIMEOUT);
        HANDLER.postDelayed(TIMEOUT, CallerIdOverlayPolicy.TIMEOUT_MILLIS);
    }

    private static boolean addView(Context appContext, NumberInfo numberInfo) {
        // the permission may have been revoked since the check
        if (!canDrawOverlays(appContext)) {
            LOG.warn("addView() no permission");
            return false;
        }

        try {
            Context windowContext = createWindowContext(appContext);
            WindowManager wm = (WindowManager) windowContext.getSystemService(
                    Context.WINDOW_SERVICE);
            if (wm == null) {
                LOG.warn("addView() no WindowManager");
                return false;
            }

            Context themed = createThemedContext(windowContext);
            @SuppressLint("InflateParams") // a window root has no parent
            View root = LayoutInflater.from(themed).inflate(R.layout.caller_id_overlay, null);

            WindowManager.LayoutParams params = createLayoutParams(windowContext);
            bind(appContext, root, numberInfo);
            setUpDragging(root, wm, params);

            wm.addView(root, params);
            windowManager = wm;
            view = root;
            LOG.info("addView() shown");
            return true;
        } catch (RuntimeException e) {
            // WindowManager.BadTokenException, SecurityException (permission revoked),
            // inflation errors: the notification is still there
            LOG.error("addView() failed", e);
            return false;
        }
    }

    private static void removeView() {
        HANDLER.removeCallbacks(TIMEOUT);

        View v = view;
        WindowManager wm = windowManager;
        view = null;
        windowManager = null;
        shownNumber = null;

        if (v == null || wm == null) return;
        try {
            wm.removeView(v);
            LOG.debug("removeView() removed");
        } catch (RuntimeException e) {
            LOG.warn("removeView() failed", e);
        }
    }

    /**
     * @return a context for an overlay window (Android 11+), or the application context
     */
    private static Context createWindowContext(Context appContext) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                DisplayManager displayManager = (DisplayManager) appContext.getSystemService(
                        Context.DISPLAY_SERVICE);
                Display display = displayManager != null
                        ? displayManager.getDisplay(Display.DEFAULT_DISPLAY) : null;
                if (display != null) {
                    return appContext.createDisplayContext(display).createWindowContext(
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
                }
            } catch (RuntimeException e) {
                LOG.warn("createWindowContext() failed", e);
            }
        }
        return appContext;
    }

    /** The app theme with dynamic colors and the app's day/night setting. */
    private static Context createThemedContext(Context base) {
        ContextThemeWrapper themed = new ContextThemeWrapper(base, R.style.AppTheme);

        // AppCompatDelegate only applies the setting to activities
        int uiMode = App.getSettings().getUiMode();
        if (uiMode == AppCompatDelegate.MODE_NIGHT_YES
                || uiMode == AppCompatDelegate.MODE_NIGHT_NO) {
            Configuration configuration = new Configuration();
            configuration.uiMode = (base.getResources().getConfiguration().uiMode
                    & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (uiMode == AppCompatDelegate.MODE_NIGHT_YES
                    ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            themed.applyOverrideConfiguration(configuration);
        }

        return DynamicColors.wrapContextIfAvailable(themed);
    }

    private static WindowManager.LayoutParams createLayoutParams(Context context) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // touches outside the card go to the call screen; no keyboard focus
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.setTitle("CallerIdOverlay");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // y counts from the top of the screen on all versions
            params.setFitInsetsTypes(0);
        }

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        int maxWidth = dp(context, MAX_WIDTH_DP);
        if (metrics.widthPixels > maxWidth) params.width = maxWidth;

        int saved = App.getSettings().getCallerIdOverlayY();
        int y = saved >= 0 ? saved : getStatusBarHeight(context) + dp(context, DEFAULT_OFFSET_DP);
        params.y = CallerIdOverlayPolicy.clampOffset(y, 0,
                metrics.heightPixels - dp(context, APPROX_HEIGHT_DP));
        return params;
    }

    @SuppressLint("ClickableViewAccessibility") // dragging only, the buttons are separate
    private static void setUpDragging(View root, WindowManager wm,
                                      WindowManager.LayoutParams params) {
        View card = root.findViewById(R.id.overlay_card);
        int touchSlop = ViewConfiguration.get(root.getContext()).getScaledTouchSlop();

        card.setOnTouchListener(new View.OnTouchListener() {
            private float downRawY;
            private int downY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = event.getRawY();
                        downY = params.y;
                        dragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.abs(dy) > touchSlop) dragging = true;
                        if (dragging) {
                            int max = root.getResources().getDisplayMetrics().heightPixels
                                    - root.getHeight();
                            params.y = CallerIdOverlayPolicy.clampOffset(
                                    downY + Math.round(dy), 0, max);
                            try {
                                wm.updateViewLayout(root, params);
                            } catch (RuntimeException e) {
                                LOG.warn("onTouch() update failed", e);
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            App.getSettings().setCallerIdOverlayY(params.y);
                            // the user is looking at the card: give them time
                            if (view == root) scheduleTimeout();
                        }
                        dragging = false;
                        return true;

                    default:
                        return false;
                }
            }
        });
    }

    // content

    private static void bind(Context appContext, View root, NumberInfo numberInfo) {
        Context context = root.getContext();

        AppCompatImageView badge = root.findViewById(R.id.overlay_badge);
        IconAndColor iconAndColor = IconAndColor.forNumberRating(
                numberInfo.rating, numberInfo.contactItem != null);
        iconAndColor.applyToImageView(badge);
        int color = iconAndColor.getColorInt(context);
        ViewCompat.setBackgroundTintList(badge, ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(color, iconAndColor.noInfo ? 0x26 : 0x33)));

        String category = NumberInfoUtils.getCategoryName(context, numberInfo);
        boolean hasName = !TextUtils.isEmpty(numberInfo.name);

        TextView title = root.findViewById(R.id.overlay_title);
        title.setText(getTitle(context, numberInfo, category));

        TextView number = root.findViewById(R.id.overlay_number);
        if (!numberInfo.noNumber && !TextUtils.isEmpty(numberInfo.number)) {
            number.setText(numberInfo.number);
            number.setVisibility(View.VISIBLE);
        } else {
            number.setVisibility(View.GONE);
        }

        TextView source = root.findViewById(R.id.overlay_source);
        String sourceText = getSourceLine(context, numberInfo, hasName ? category : null);
        if (!TextUtils.isEmpty(sourceText)) {
            source.setText(sourceText);
            source.setVisibility(View.VISIBLE);
        } else {
            source.setVisibility(View.GONE);
        }

        root.findViewById(R.id.overlay_close).setOnClickListener(v -> {
            LOG.debug("closed by the user");
            hideOnMain();
        });

        View actions = root.findViewById(R.id.overlay_actions);
        View notSpam = root.findViewById(R.id.overlay_not_spam);
        View block = root.findViewById(R.id.overlay_block);

        if (CallerIdOverlayPolicy.showActions(numberInfo.noNumber)
                && !TextUtils.isEmpty(numberInfo.number)) {
            String rawNumber = numberInfo.number;
            String normalizedNumber = numberInfo.normalizedNumber;
            String name = CallNotificationActionReceiver.getName(numberInfo);

            boolean markedNotSpam = numberInfo.userMark != null && numberInfo.userMark.isNotSpam();
            notSpam.setVisibility(markedNotSpam ? View.GONE : View.VISIBLE);
            notSpam.setOnClickListener(v -> onAction(appContext,
                    CallNotificationActionReceiver.ACTION_NOT_SPAM,
                    rawNumber, normalizedNumber, name));
            block.setOnClickListener(v -> onAction(appContext,
                    CallNotificationActionReceiver.ACTION_BLOCK,
                    rawNumber, normalizedNumber, name));
            actions.setVisibility(View.VISIBLE);
        } else {
            actions.setVisibility(View.GONE);
        }
    }

    private static void onAction(Context appContext, String action, String number,
                                 String normalizedNumber, String name) {
        LOG.info("onAction() {}", action);
        hideOnMain();
        // the prominent notification offers the same buttons: done with it as well
        NotificationHelper.hideIncomingCallNotification(appContext);
        CallNotificationActionReceiver.perform(appContext, action, number, normalizedNumber,
                name, null);
    }

    /** Name, category, "Hidden number", "Likely spam" or "Unknown caller". */
    private static String getTitle(Context context, NumberInfo numberInfo, String category) {
        if (!TextUtils.isEmpty(numberInfo.name)) return numberInfo.name;
        if (!TextUtils.isEmpty(category)) return category;
        if (numberInfo.noNumber) return context.getString(R.string.caller_id_overlay_hidden_number);

        switch (numberInfo.rating) {
            case NEGATIVE:
                return context.getString(R.string.notification_call_alert_spam);
            case POSITIVE:
                return context.getString(R.string.caller_id_overlay_positive);
            default:
                return context.getString(R.string.notification_call_alert_unknown);
        }
    }

    /**
     * @return e.g. "Rule: … · PhoneBlock: Advertising · 25 reports", or null
     * @param category the category if it isn't the title already, may be null
     */
    private static String getSourceLine(Context context, NumberInfo numberInfo,
                                        String category) {
        List<String> parts = new ArrayList<>();

        if (!TextUtils.isEmpty(category)) parts.add(category);

        String rule = NumberInfoUtils.getRuleDescription(context, numberInfo);
        if (!TextUtils.isEmpty(rule)) parts.add(rule);

        // "My mark: spam" or the list that rated the number
        String source = NumberInfoUtils.getSourceDescription(context, numberInfo);
        if (!TextUtils.isEmpty(source)) parts.add(source);

        if (numberInfo.userMark == null && numberInfo.sourceReviewCount > 0) {
            parts.add(context.getResources().getQuantityString(R.plurals.caller_id_overlay_reports,
                    numberInfo.sourceReviewCount, numberInfo.sourceReviewCount));
        }

        CommunityDatabaseItem communityItem = numberInfo.communityDatabaseItem;
        if (communityItem != null && communityItem.hasRatings()) {
            parts.add(context.getString(R.string.notification_incoming_call_text_description,
                    communityItem.getNegativeRatingsCount(),
                    communityItem.getPositiveRatingsCount(),
                    communityItem.getNeutralRatingsCount()));
        }

        if (numberInfo.blacklistItem != null && numberInfo.contactItem == null) {
            parts.add(context.getString(R.string.info_in_blacklist));
        }

        return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
    }

    // dimensions

    private static int dp(Context context, int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics()));
    }

    @SuppressLint({"DiscouragedApi", "InternalInsetResource"})
    private static int getStatusBarHeight(Context context) {
        Resources resources = context.getResources();
        int id = resources.getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            try {
                return resources.getDimensionPixelSize(id);
            } catch (Resources.NotFoundException e) {
                LOG.debug("getStatusBarHeight() not found", e);
            }
        }
        return dp(context, 24);
    }

}
