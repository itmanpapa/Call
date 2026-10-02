package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dummydomain.yetanothercallblocker.data.setupcheck.SetupCheck;

/**
 * The dismissible "protection has problems" card at the top of the call log.
 * Shown when the setup check finds errors; a dismissed card stays hidden until
 * the set of errors changes or all errors are fixed once.
 */
final class SetupCheckBanner {

    private static final String PREFERENCES_NAME = "setup_check";
    private static final String PREF_DISMISSED_ERRORS = "dismissedErrors";

    private static final Logger LOG = LoggerFactory.getLogger(SetupCheckBanner.class);

    private final BaseActivity activity;
    private final View banner;
    private final TextView textView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private String shownSignature;

    /**
     * @param banner the card from {@code activity_main.xml}
     */
    SetupCheckBanner(BaseActivity activity, View banner) {
        this.activity = activity;
        this.banner = banner;
        this.textView = banner.findViewById(R.id.setup_banner_text);

        View.OnClickListener open = v ->
                activity.startActivity(SetupCheckActivity.getIntent(activity));
        banner.setOnClickListener(open);
        banner.findViewById(R.id.setup_banner_open).setOnClickListener(open);
        banner.findViewById(R.id.setup_banner_dismiss).setOnClickListener(v -> dismiss());
    }

    /** Runs the check in the background and shows or hides the card. */
    void refresh() {
        if (executor.isShutdown()) return;
        Context appContext = activity.getApplicationContext();
        executor.execute(() -> {
            SetupCheck.Result result;
            try {
                result = AndroidSetupCheckEnvironment.runCheck(appContext);
            } catch (Exception e) {
                LOG.warn("refresh() check failed", e);
                return;
            }
            onChecked(appContext, result);
            boolean show = shouldShow(appContext, result);

            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                bind(result, show);
            });
        });
    }

    /** Call from {@code onDestroy()}. */
    void shutdown() {
        executor.shutdown();
    }

    private void bind(SetupCheck.Result result, boolean show) {
        shownSignature = show ? result.getErrorSignature() : null;
        if (show) {
            textView.setText(activity.getResources().getQuantityString(
                    R.plurals.setup_check_banner_text,
                    result.getProblemCount(), result.getProblemCount()));
        }
        banner.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void dismiss() {
        banner.setVisibility(View.GONE);
        if (shownSignature != null) {
            getPreferences(activity).edit()
                    .putString(PREF_DISMISSED_ERRORS, shownSignature)
                    .apply();
        }
    }

    private static boolean shouldShow(Context context, SetupCheck.Result result) {
        if (result.getErrorCount() == 0) return false;
        String dismissed = getPreferences(context).getString(PREF_DISMISSED_ERRORS, null);
        return !result.getErrorSignature().equals(dismissed);
    }

    /**
     * Forgets a dismissed card once everything is fine, so that the same problem
     * coming back later is shown again. Called after every check.
     */
    static void onChecked(Context context, SetupCheck.Result result) {
        if (result.getErrorCount() > 0) return;
        SharedPreferences preferences = getPreferences(context);
        if (preferences.contains(PREF_DISMISSED_ERRORS)) {
            preferences.edit().remove(PREF_DISMISSED_ERRORS).apply();
        }
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

}
