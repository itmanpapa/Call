package dummydomain.yetanothercallblocker;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.format.DateFormat;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.IdRes;

import java.util.Date;

/**
 * "About": app name, version, the update check (not in the F-Droid build), license, the
 * links to the fork and the original project and the optional donation button. The
 * status of the databases is shown on the "Databases" screen.
 */
public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        ((TextView) findViewById(R.id.about_version)).setText(
                getString(R.string.version_string, BuildConfig.VERSION_NAME));

        setLink(R.id.about_based_on, getString(R.string.about_based_on,
                link(getString(R.string.url_repo), getString(R.string.about_original_name))));
        setLink(R.id.about_fork, link(getString(R.string.url_fork_repo),
                getString(R.string.about_fork_link)));

        View checkUpdates = findViewById(R.id.about_check_updates);
        if (AppUpdateManager.isSelfUpdateEnabled()) {
            checkUpdates.setOnClickListener(v -> startActivity(UpdateActivity.getIntent(this)));
        } else {
            // the F-Droid build is updated by its app store
            checkUpdates.setVisibility(View.GONE);
        }

        // shown only when a donation link is configured (res/values/donation.xml)
        String donationUrl = getString(R.string.donation_url).trim();
        View donate = findViewById(R.id.about_donate);
        if (donationUrl.isEmpty()) {
            donate.setVisibility(View.GONE);
        } else {
            donate.setVisibility(View.VISIBLE);
            donate.setOnClickListener(v -> openUrl(donationUrl));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUpdateStatus();
    }

    /** Shows the result of the last update check, if there was one. */
    private void updateUpdateStatus() {
        TextView status = findViewById(R.id.about_update_status);
        if (!AppUpdateManager.isSelfUpdateEnabled()) {
            status.setVisibility(View.GONE);
            return;
        }
        AppUpdateManager manager = AppUpdateManager.get(this);

        String update = manager.getKnownUpdate();
        long lastCheck = manager.getLastCheckTime();
        if (update != null) {
            status.setText(getString(R.string.update_available_title, update));
        } else if (lastCheck > 0) {
            status.setText(getString(R.string.update_last_check,
                    DateFormat.getDateFormat(this).format(new Date(lastCheck))));
        } else {
            status.setVisibility(View.GONE);
            return;
        }
        status.setVisibility(View.VISIBLE);
    }

    private void openUrl(String url) {
        if (!IntentHelper.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            Toast.makeText(this, R.string.update_no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    private static String link(String url, String text) {
        return "<a href=\"" + url + "\">" + Html.escapeHtml(text) + "</a>";
    }

    private void setLink(@IdRes int textViewId, String html) {
        TextView textView = findViewById(textViewId);
        textView.setMovementMethod(LinkMovementMethod.getInstance());
        textView.setText(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY));
    }

}
