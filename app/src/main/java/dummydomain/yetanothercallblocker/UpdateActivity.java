package dummydomain.yetanothercallblocker;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dummydomain.yetanothercallblocker.data.update.GitHubReleaseClient;
import dummydomain.yetanothercallblocker.data.update.ReleaseInfo;
import dummydomain.yetanothercallblocker.data.update.UpdateChecker;

/**
 * The update screen: checks GitHub for the latest release, shows its version and notes,
 * downloads the APK (the download continues in {@link AppUpdateManager} if the screen is
 * closed) and hands it to the system package installer. The installer asks the user to
 * confirm; it accepts the APK only if it is signed with the same key as the installed app.
 */
public class UpdateActivity extends BaseActivity implements AppUpdateManager.Listener {

    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";

    private static final String STATE_PENDING_INSTALL = "pendingInstall";

    private static final Logger LOG = LoggerFactory.getLogger(UpdateActivity.class);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AppUpdateManager manager;

    private TextView titleView;
    private TextView metaView;
    private TextView statusView;
    private TextView notesLabel;
    private TextView notesView;
    private LinearProgressIndicator checkingView;
    private LinearProgressIndicator progressView;
    private MaterialButton installButton;
    private MaterialButton pageButton;

    /** The latest release, null while checking or after an error. */
    private ReleaseInfo release;
    private boolean checking;
    private String checkError;
    /** Waiting for the user to allow installing apps from this app. */
    private boolean pendingInstall;

    public static Intent getIntent(Context context) {
        return new Intent(context, UpdateActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_update);
        setTitle(R.string.update_screen_title);

        manager = AppUpdateManager.get(this);

        titleView = findViewById(R.id.update_title);
        metaView = findViewById(R.id.update_meta);
        statusView = findViewById(R.id.update_status);
        notesLabel = findViewById(R.id.update_notes_label);
        notesView = findViewById(R.id.update_notes);
        checkingView = findViewById(R.id.update_checking);
        progressView = findViewById(R.id.update_progress);
        installButton = findViewById(R.id.update_install_button);
        pageButton = findViewById(R.id.update_page_button);

        ((TextView) findViewById(R.id.update_current_version)).setText(
                getString(R.string.update_installed_version, BuildConfig.VERSION_NAME));

        pageButton.setOnClickListener(v -> openReleasePage());

        if (savedInstanceState != null) {
            pendingInstall = savedInstanceState.getBoolean(STATE_PENDING_INSTALL);
        }

        UpdateNotifications.cancel(this);

        // reuse the release of a check made a moment ago (e.g. on recreation)
        release = manager.getLastRelease();
        if (release == null || savedInstanceState == null) {
            check();
        }
        updateViews();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_PENDING_INSTALL, pendingInstall);
    }

    @Override
    protected void onStart() {
        super.onStart();
        manager.addListener(this);
        updateViews();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // back from the "install unknown apps" settings screen
        if (pendingInstall && canInstallPackages()) {
            pendingInstall = false;
            AppUpdateManager.DownloadState state = manager.getDownloadState();
            if (state.status == AppUpdateManager.DownloadState.Status.DONE && state.file != null) {
                install(state.file);
            }
        }
    }

    @Override
    protected void onStop() {
        manager.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onDownloadStateChanged(AppUpdateManager.DownloadState state) {
        updateViews();
    }

    // check

    private void check() {
        checking = true;
        checkError = null;
        updateViews();

        executor.execute(() -> {
            ReleaseInfo result = null;
            String error = null;
            try {
                UpdateChecker.Result checkResult = manager.check();
                result = checkResult.getRelease();
                if (checkResult.isUpdateAvailable()) {
                    manager.markNotified(result.getVersion());
                }
            } catch (GitHubReleaseClient.ApiException e) {
                LOG.warn("check() failed", e);
                error = e.isRateLimited() ? getString(R.string.update_error_rate_limit)
                        : e.getMessage();
            } catch (Exception e) {
                LOG.warn("check() failed", e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }

            ReleaseInfo finalResult = result;
            String finalError = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                checking = false;
                release = finalResult;
                checkError = finalError;
                updateViews();
            });
        });
    }

    // UI

    private boolean isUpdateAvailable() {
        return release != null && release.isNewerThan(BuildConfig.VERSION_NAME);
    }

    private void updateViews() {
        AppUpdateManager.DownloadState state = manager.getDownloadState();
        boolean available = isUpdateAvailable();

        // title
        if (checking) {
            titleView.setText(R.string.update_checking);
        } else if (checkError != null) {
            titleView.setText(R.string.update_check_failed);
        } else if (release == null) {
            titleView.setText(R.string.update_no_releases);
        } else if (available) {
            titleView.setText(getString(R.string.update_available_title, release.getVersion()));
        } else {
            titleView.setText(R.string.update_up_to_date);
        }

        // release details
        if (release != null && !checking) {
            String meta = getString(R.string.update_latest_version, release.getVersion());
            if (release.getPublishedAt() > 0) {
                meta += " · " + DateFormat.getDateFormat(this)
                        .format(new Date(release.getPublishedAt()));
            }
            if (release.getApkSize() > 0) {
                meta += " · " + Formatter.formatShortFileSize(this, release.getApkSize());
            }
            metaView.setText(meta);
            metaView.setVisibility(View.VISIBLE);

            String notes = release.getPlainNotes();
            boolean hasNotes = !TextUtils.isEmpty(notes);
            notesView.setText(notes);
            notesView.setVisibility(hasNotes ? View.VISIBLE : View.GONE);
            notesLabel.setVisibility(hasNotes ? View.VISIBLE : View.GONE);
        } else {
            metaView.setVisibility(View.GONE);
            notesView.setVisibility(View.GONE);
            notesLabel.setVisibility(View.GONE);
        }

        checkingView.setVisibility(checking ? View.VISIBLE : View.GONE);

        // download state of this release
        boolean downloadForRelease = release != null && state.isFor(release);
        AppUpdateManager.DownloadState.Status status = downloadForRelease
                ? state.status : AppUpdateManager.DownloadState.Status.IDLE;

        String statusText = null;
        if (checkError != null && !checking) {
            statusText = checkError;
        } else if (status == AppUpdateManager.DownloadState.Status.RUNNING) {
            statusText = state.total > 0
                    ? getString(R.string.update_downloading_progress,
                    Formatter.formatShortFileSize(this, state.downloaded),
                    Formatter.formatShortFileSize(this, state.total))
                    : getString(R.string.update_downloading);
        } else if (status == AppUpdateManager.DownloadState.Status.FAILED) {
            statusText = getString(R.string.update_download_failed, state.error);
        } else if (status == AppUpdateManager.DownloadState.Status.DONE) {
            statusText = getString(R.string.update_downloaded);
        } else if (available && !release.hasApk()) {
            statusText = getString(R.string.update_no_apk);
        }
        statusView.setText(statusText);
        statusView.setVisibility(statusText != null ? View.VISIBLE : View.GONE);

        if (status == AppUpdateManager.DownloadState.Status.RUNNING) {
            progressView.setVisibility(View.VISIBLE);
            int value = state.total > 0
                    ? (int) Math.min(1000, state.downloaded * 1000 / state.total) : 0;
            progressView.setProgressCompat(value, true);
        } else {
            progressView.setVisibility(View.GONE);
        }

        // main button
        if (checking) {
            installButton.setVisibility(View.GONE);
        } else if (checkError != null) {
            showButton(R.string.update_retry, v -> check());
        } else if (status == AppUpdateManager.DownloadState.Status.RUNNING) {
            showButton(android.R.string.cancel, v -> manager.cancelDownload());
        } else if (status == AppUpdateManager.DownloadState.Status.DONE && state.file != null) {
            showButton(R.string.update_install, v -> install(state.file));
        } else if (available && release.hasApk()) {
            showButton(status == AppUpdateManager.DownloadState.Status.FAILED
                            ? R.string.update_retry : R.string.update_download_and_install,
                    v -> manager.startDownload(release));
        } else {
            installButton.setVisibility(View.GONE);
        }
    }

    private void showButton(int textRes, View.OnClickListener listener) {
        installButton.setText(textRes);
        installButton.setOnClickListener(listener);
        installButton.setVisibility(View.VISIBLE);
    }

    private void openReleasePage() {
        String url = release != null && !TextUtils.isEmpty(release.getPageUrl())
                ? release.getPageUrl() : GitHubReleaseClient.RELEASES_PAGE_URL;
        if (!IntentHelper.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(url)))) {
            Toast.makeText(this, R.string.update_no_browser, Toast.LENGTH_SHORT).show();
        }
    }

    // installation

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls();
    }

    private void install(File file) {
        if (!file.isFile()) {
            manager.resetDownloadState();
            updateViews();
            return;
        }

        if (!canInstallPackages()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.update_permission_title)
                    .setMessage(R.string.update_permission_message)
                    .setPositiveButton(R.string.update_permission_open_settings, (d, w) -> {
                        pendingInstall = true;
                        Intent intent = new Intent(
                                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        if (!IntentHelper.startActivity(this, intent)) {
                            pendingInstall = false;
                            IntentHelper.startActivity(this, new Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", getPackageName(), null)));
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        Uri uri;
        try {
            uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        } catch (IllegalArgumentException e) {
            LOG.error("install() FileProvider can't serve {}", file, e);
            Toast.makeText(this, R.string.update_install_failed, Toast.LENGTH_LONG).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME_TYPE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            LOG.warn("install() ACTION_VIEW not handled, trying ACTION_INSTALL_PACKAGE", e);
            @SuppressWarnings("deprecation")
            Intent fallback = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!IntentHelper.startActivity(this, fallback)) {
                Toast.makeText(this, R.string.update_install_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

}
