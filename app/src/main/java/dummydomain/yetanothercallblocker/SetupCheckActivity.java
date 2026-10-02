package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dummydomain.yetanothercallblocker.data.setupcheck.CheckItem;
import dummydomain.yetanothercallblocker.data.setupcheck.SetupCheck;
import dummydomain.yetanothercallblocker.data.setupcheck.SetupCheckEnvironment;
import dummydomain.yetanothercallblocker.event.MainDbDownloadFinishedEvent;
import dummydomain.yetanothercallblocker.event.MainDbDownloadingEvent;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdateFinished;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdatingEvent;

/**
 * "Setup check": shows whether calls are screened and blocked, explains every
 * finding in plain words and offers a button that fixes it. The checks themselves
 * are in {@link SetupCheck}.
 */
public class SetupCheckActivity extends BaseActivity implements SourceTasks.Listener {

    private static final String STATE_REQUEST_TOKEN = "STATE_REQUEST_TOKEN";

    private static final Logger LOG = LoggerFactory.getLogger(SetupCheckActivity.class);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private PermissionHelper.RequestToken requestToken;

    private ImageView summaryIcon;
    private TextView summaryTitle;
    private TextView summaryText;
    private View progress;
    private ViewGroup itemsContainer;

    public static Intent getIntent(Context context) {
        return new Intent(context, SetupCheckActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_check);

        requestToken = PermissionHelper.RequestToken
                .fromSavedInstanceState(savedInstanceState, STATE_REQUEST_TOKEN);

        summaryIcon = findViewById(R.id.setup_summary_icon);
        summaryTitle = findViewById(R.id.setup_summary_title);
        summaryText = findViewById(R.id.setup_summary_text);
        progress = findViewById(R.id.setup_progress);
        itemsContainer = findViewById(R.id.setup_items);

        findViewById(R.id.setup_howto_add).setOnClickListener(v ->
                startActivity(EditBlacklistItemActivity.getIntent(this,
                        getString(R.string.setup_check_test_entry_name), null)));
        findViewById(R.id.setup_howto_blacklist).setOnClickListener(v ->
                startActivity(new Intent(this, BlacklistActivity.class)));
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventUtils.register(this);
        SourceTasks.addListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // the user may come back from a system screen (role dialog, settings)
        refresh();
    }

    @Override
    protected void onStop() {
        SourceTasks.removeListener(this);
        EventUtils.unregister(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        // opened from the settings or from the call log: go back to where we came from
        finish();
        return true;
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (requestToken != null) {
            requestToken.onSaveInstanceState(outState, STATE_REQUEST_TOKEN);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (PermissionHelper.handleCallScreeningResult(this, requestCode, resultCode,
                requestToken)) {
            refresh();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        Settings settings = App.getSettings();
        if (!PermissionHelper.handleSmsPermissionResult(this, requestCode, permissions,
                grantResults)) {
            PermissionHelper.handlePermissionsResult(this, requestCode, permissions,
                    grantResults, settings.getIncomingCallNotifications(),
                    settings.getCallBlockingEnabled(), settings.getUseContacts());
        }

        // "don't ask again": the system doesn't show the dialog any more
        for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
            if (grantResults[i] != PackageManager.PERMISSION_GRANTED
                    && !ActivityCompat.shouldShowRequestPermissionRationale(
                    this, permissions[i])) {
                openAppDetails();
                break;
            }
        }
        refresh();
    }

    // state updates

    @Override
    public void onSourceTaskChanged(String sourceId, SourceTasks.Outcome outcome) {
        if (outcome != null && outcome.getNotice() != null) {
            Toast.makeText(this, outcome.getNotice(), Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloading(MainDbDownloadingEvent event) {
        refresh();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloadFinished(MainDbDownloadFinishedEvent event) {
        refresh();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdating(SecondaryDbUpdatingEvent event) {
        refresh();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdateFinished(SecondaryDbUpdateFinished event) {
        refresh();
    }

    private void refresh() {
        if (executor.isShutdown()) return;
        progress.setVisibility(View.VISIBLE);

        Context appContext = getApplicationContext();
        executor.execute(() -> {
            SetupCheck.Result checked;
            try {
                checked = AndroidSetupCheckEnvironment.runCheck(appContext);
            } catch (Exception e) {
                LOG.error("refresh() check failed", e);
                return;
            }
            LOG.debug("refresh() {}", checked);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                bind(checked);
            });
        });
    }

    // binding

    private void bind(SetupCheck.Result checked) {
        progress.setVisibility(View.GONE);
        SetupCheckBanner.onChecked(this, checked);

        bindSummary(checked);

        itemsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (CheckItem item : checked.getItems()) {
            View view = inflater.inflate(R.layout.setup_check_item, itemsContainer, false);
            bindItem(view, item);
            itemsContainer.addView(view);
        }
    }

    private void bindSummary(SetupCheck.Result checked) {
        String text;
        if (checked.getErrorCount() > 0) {
            summaryTitle.setText(getString(R.string.setup_check_summary_problems,
                    checked.getProblemCount()));
            text = getString(R.string.setup_check_summary_problems_text);
            summaryIcon.setImageResource(R.drawable.ic_error_24dp);
        } else {
            summaryTitle.setText(R.string.setup_check_summary_ok);
            text = checked.getWarningCount() > 0
                    ? getResources().getQuantityString(R.plurals.setup_check_summary_notes,
                    checked.getWarningCount(), checked.getWarningCount())
                    : getString(R.string.setup_check_summary_ok_text);
            summaryIcon.setImageResource(R.drawable.ic_security_24dp);
        }
        summaryIcon.setImageTintList(ColorStateList.valueOf(
                getStatusColor(checked.getOverallStatus())));
        summaryText.setText(text);
        summaryText.setVisibility(View.VISIBLE);
    }

    private void bindItem(View view, CheckItem item) {
        ImageView icon = view.findViewById(R.id.check_icon);
        icon.setImageResource(getStatusIcon(item.getStatus()));
        icon.setImageTintList(ColorStateList.valueOf(getStatusColor(item.getStatus())));
        icon.setContentDescription(getString(getStatusDescription(item.getStatus())));

        TextView title = view.findViewById(R.id.check_title);
        TextView text = view.findViewById(R.id.check_text);
        SetupCheckEnvironment.Source source = item.getSource();
        if (source != null) {
            title.setText(source.getName());
            text.setText(describeSource(item, source));
        } else if (item.getReason() == CheckItem.Reason.APP_UPDATE_AVAILABLE) {
            title.setText(getString(R.string.update_available_title, item.getDetail()));
            text.setText(R.string.update_setup_check_text);
        } else {
            title.setText(titleFor(item.getReason()));
            text.setText(explanationFor(item.getReason()));
        }

        MaterialButton button = view.findViewById(R.id.check_button);
        MaterialButton secondary = view.findViewById(R.id.check_secondary_button);

        int buttonText = getActionText(item.getAction());
        if (buttonText != 0 && item.getStatus() != CheckItem.Status.OK) {
            button.setText(buttonText);
            button.setOnClickListener(v -> onAction(item));
            button.setVisibility(View.VISIBLE);
        } else {
            button.setVisibility(View.GONE);
        }

        // a source can always be opened, e.g. to check its settings
        if (source != null && (item.getAction() != CheckItem.Action.OPEN_SOURCE
                || button.getVisibility() == View.GONE)) {
            secondary.setText(R.string.setup_check_action_details);
            secondary.setOnClickListener(v ->
                    startActivity(SourceDetailsActivity.getIntent(this, source.getId())));
            secondary.setVisibility(View.VISIBLE);
        } else {
            secondary.setVisibility(View.GONE);
        }

        view.findViewById(R.id.check_buttons).setVisibility(
                button.getVisibility() == View.VISIBLE || secondary.getVisibility() == View.VISIBLE
                        ? View.VISIBLE : View.GONE);
    }

    private String describeSource(CheckItem item, SetupCheckEnvironment.Source source) {
        switch (item.getReason()) {
            case SOURCE_DISABLED:
                return getString(R.string.setup_check_source_disabled);
            case SOURCE_UPDATING:
                return getString(R.string.source_status_updating);
            case SOURCE_NOT_CONFIGURED:
                return getString(R.string.setup_check_source_not_configured);
            case SOURCE_NEVER:
                return source.getLastError() != null
                        ? getString(R.string.setup_check_source_never_error,
                        source.getLastError())
                        : getString(R.string.setup_check_source_never);
            case SOURCE_ERROR:
                return item.getAgeDays() >= 0
                        ? getString(R.string.setup_check_source_error_with_age,
                        source.getLastError(), formatAge(item.getAgeDays()))
                        : getString(R.string.setup_check_source_error, source.getLastError());
            case SOURCE_STALE:
                return getString(R.string.setup_check_source_stale,
                        formatAge(item.getAgeDays()));
            default:
                return item.getAgeDays() >= 0
                        ? getString(R.string.setup_check_source_ok, formatAge(item.getAgeDays()))
                        : getString(R.string.setup_check_source_ready);
        }
    }

    private String formatAge(long days) {
        if (days <= 0) return getString(R.string.setup_check_today);
        int n = (int) Math.min(days, Integer.MAX_VALUE);
        return getResources().getQuantityString(R.plurals.setup_check_days_ago, n, n);
    }

    // actions

    private void onAction(CheckItem item) {
        switch (item.getAction()) {
            case REQUEST_CALL_SCREENING:
                requestToken = PermissionHelper.requestCallScreening(this);
                break;

            case REQUEST_PERMISSIONS:
                requestMissingPermissions();
                break;

            case OPEN_NOTIFICATION_SETTINGS:
                openNotificationSettings();
                break;

            case REQUEST_OVERLAY_PERMISSION:
                if (!CallerIdOverlay.requestPermission(this)) openAppDetails();
                break;

            case OPEN_SETTINGS:
                startActivity(new Intent(this, SettingsActivity.class));
                break;

            case OPEN_BATTERY_SETTINGS:
                if (!IntentHelper.startActivity(this, new Intent(
                        android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
                    openAppDetails();
                }
                break;

            case OPEN_SOURCES:
                startActivity(SourcesActivity.getIntent(this));
                break;

            case UPDATE_SOURCE:
                SetupCheckEnvironment.Source source = item.getSource();
                if (source != null) {
                    SourceDetailsActivity.startUpdate(this, source.getId(), source.hasData());
                    refresh();
                }
                break;

            case OPEN_APP_UPDATE:
                startActivity(UpdateActivity.getIntent(this));
                break;

            case REQUEST_SMS_PERMISSION:
                PermissionHelper.requestSmsPermission(this);
                break;

            case OPEN_SOURCE:
                if (item.getSource() != null) {
                    startActivity(SourceDetailsActivity.getIntent(this,
                            item.getSource().getId()));
                }
                break;

            default:
                break;
        }
    }

    private void requestMissingPermissions() {
        // everything the app may need; the result is handled in onRequestPermissionsResult()
        PermissionHelper.checkPermissions(this, true, true, App.getSettings().getUseContacts());
    }

    private void openNotificationSettings() {
        NotificationHelper.initNotificationChannels(this);

        Intent intent = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
        if (!IntentHelper.startActivity(this, intent)) openAppDetails();
    }

    private void openAppDetails() {
        IntentHelper.startActivity(this, new Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)));
    }

    // resources

    private int getStatusColor(CheckItem.Status status) {
        switch (status) {
            case OK:
                return UiUtils.getColorInt(this, R.color.ratePositive);
            case WARNING:
                return UiUtils.getColorInt(this, R.color.rateNeutral);
            case ERROR:
                return UiUtils.getColorInt(this, R.color.rateNegative);
            default:
                return MaterialColors.getColor(summaryIcon,
                        com.google.android.material.R.attr.colorPrimary);
        }
    }

    private static int getStatusIcon(CheckItem.Status status) {
        switch (status) {
            case OK:
                return R.drawable.ic_check_circle_24dp;
            case WARNING:
                return R.drawable.ic_warning_24dp;
            case ERROR:
                return R.drawable.ic_error_24dp;
            default:
                return R.drawable.ic_info_24dp;
        }
    }

    private static int getStatusDescription(CheckItem.Status status) {
        switch (status) {
            case OK:
                return R.string.setup_check_status_ok;
            case WARNING:
                return R.string.setup_check_status_warning;
            case ERROR:
                return R.string.setup_check_status_error;
            default:
                return R.string.setup_check_status_info;
        }
    }

    private static int getActionText(CheckItem.Action action) {
        switch (action) {
            case REQUEST_CALL_SCREENING:
            case REQUEST_PERMISSIONS:
            case REQUEST_OVERLAY_PERMISSION:
            case REQUEST_SMS_PERMISSION:
                return R.string.setup_check_action_allow;
            case OPEN_NOTIFICATION_SETTINGS:
                return R.string.setup_check_action_notifications;
            case OPEN_SETTINGS:
                return R.string.setup_check_action_settings;
            case OPEN_BATTERY_SETTINGS:
                return R.string.setup_check_action_battery;
            case OPEN_SOURCES:
                return R.string.databases_title;
            case UPDATE_SOURCE:
                return R.string.setup_check_action_update;
            case OPEN_SOURCE:
                return R.string.setup_check_action_details;
            case OPEN_APP_UPDATE:
                return R.string.update_action_open;
            default:
                return 0;
        }
    }

    private static int titleFor(CheckItem.Reason reason) {
        switch (reason) {
            case SCREENING_ROLE_HELD:
            case SCREENING_DEFAULT_DIALER:
                return R.string.setup_check_screening_ok;
            case SCREENING_ROLE_MISSING:
                return R.string.setup_check_screening_missing;
            case SCREENING_MONITORING_SERVICE:
                return R.string.setup_check_screening_monitoring;
            case SCREENING_LEGACY:
                return R.string.setup_check_screening_legacy;
            case PERMISSIONS_OK:
                return R.string.setup_check_permissions_ok;
            case PERMISSIONS_PHONE_MISSING:
                return R.string.setup_check_permissions_phone;
            case PERMISSIONS_CALL_CONTROL_MISSING:
                return R.string.setup_check_permissions_call_control;
            case PERMISSIONS_CONTACTS_MISSING:
                return R.string.setup_check_permissions_contacts;
            case BLOCKING_RATING:
                return R.string.setup_check_blocking_rating;
            case BLOCKING_NO_RATING:
                return R.string.setup_check_blocking_no_rating;
            case BLOCKING_EMPTY_BLACKLIST:
                return R.string.setup_check_blocking_empty_blacklist;
            case BLOCKING_OFF:
                return R.string.setup_check_blocking_off;
            case NOTIFICATIONS_OK:
                return R.string.setup_check_notifications_ok;
            case NOTIFICATIONS_PERMISSION_MISSING:
                return R.string.setup_check_notifications_permission;
            case NOTIFICATIONS_APP_DISABLED:
                return R.string.setup_check_notifications_disabled;
            case NOTIFICATIONS_CHANNELS_BLOCKED:
                return R.string.setup_check_notifications_channels;
            case NOTIFICATIONS_SETTING_OFF:
                return R.string.setup_check_notifications_setting_off;
            case OVERLAY_OK:
                return R.string.setup_check_overlay_ok;
            case OVERLAY_PERMISSION_MISSING:
                return R.string.setup_check_overlay_missing;
            case BATTERY_EXEMPT:
                return R.string.setup_check_battery_exempt;
            case BATTERY_OPTIMIZED:
                return R.string.setup_check_battery_optimized;
            case CONTACTS_NEVER_BLOCKED:
                return R.string.setup_check_contacts;
            case SOURCES_NONE_ENABLED:
                return R.string.setup_check_sources_none;
            case SMS_WARNINGS_ACTIVE:
                return R.string.setup_check_sms_active;
            case SMS_PERMISSION_MISSING:
                return R.string.setup_check_sms_permission;
            default:
                return R.string.setup_check_title;
        }
    }

    private static int explanationFor(CheckItem.Reason reason) {
        switch (reason) {
            case SCREENING_ROLE_HELD:
                return R.string.setup_check_screening_ok_text;
            case SCREENING_DEFAULT_DIALER:
                return R.string.setup_check_screening_dialer_text;
            case SCREENING_ROLE_MISSING:
                return R.string.setup_check_screening_missing_text;
            case SCREENING_MONITORING_SERVICE:
                return R.string.setup_check_screening_monitoring_text;
            case SCREENING_LEGACY:
                return R.string.setup_check_screening_legacy_text;
            case PERMISSIONS_OK:
                return R.string.setup_check_permissions_ok_text;
            case PERMISSIONS_PHONE_MISSING:
                return R.string.setup_check_permissions_phone_text;
            case PERMISSIONS_CALL_CONTROL_MISSING:
                return R.string.setup_check_permissions_call_control_text;
            case PERMISSIONS_CONTACTS_MISSING:
                return R.string.setup_check_permissions_contacts_text;
            case BLOCKING_RATING:
                return R.string.setup_check_blocking_rating_text;
            case BLOCKING_NO_RATING:
                return R.string.setup_check_blocking_no_rating_text;
            case BLOCKING_EMPTY_BLACKLIST:
                return R.string.setup_check_blocking_empty_blacklist_text;
            case BLOCKING_OFF:
                return R.string.setup_check_blocking_off_text;
            case NOTIFICATIONS_OK:
                return R.string.setup_check_notifications_ok_text;
            case NOTIFICATIONS_PERMISSION_MISSING:
            case NOTIFICATIONS_APP_DISABLED:
                return R.string.setup_check_notifications_disabled_text;
            case NOTIFICATIONS_CHANNELS_BLOCKED:
                return R.string.setup_check_notifications_channels_text;
            case NOTIFICATIONS_SETTING_OFF:
                return R.string.setup_check_notifications_setting_off_text;
            case OVERLAY_OK:
                return R.string.setup_check_overlay_ok_text;
            case OVERLAY_PERMISSION_MISSING:
                return R.string.setup_check_overlay_missing_text;
            case BATTERY_EXEMPT:
                return R.string.setup_check_battery_exempt_text;
            case BATTERY_OPTIMIZED:
                return R.string.setup_check_battery_optimized_text;
            case CONTACTS_NEVER_BLOCKED:
                return R.string.setup_check_contacts_text;
            case SOURCES_NONE_ENABLED:
                return R.string.setup_check_sources_none_text;
            case SMS_WARNINGS_ACTIVE:
                return R.string.setup_check_sms_active_text;
            case SMS_PERMISSION_MISSING:
                return R.string.setup_check_sms_permission_text;
            default:
                return R.string.setup_check_preference_summary;
        }
    }

}
