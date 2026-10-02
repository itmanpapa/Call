package dummydomain.yetanothercallblocker;

import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dummydomain.yetanothercallblocker.data.BlacklistImporterExporter;
import dummydomain.yetanothercallblocker.data.RemoteListManager;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.backup.BackupArchive;
import dummydomain.yetanothercallblocker.data.backup.BackupBundle;
import dummydomain.yetanothercallblocker.data.backup.BackupCreator;
import dummydomain.yetanothercallblocker.data.backup.BackupException;
import dummydomain.yetanothercallblocker.data.backup.BackupRestorer;
import dummydomain.yetanothercallblocker.data.backup.BackupSummary;
import dummydomain.yetanothercallblocker.utils.DbFilteringUtils;
import dummydomain.yetanothercallblocker.utils.PackageManagerUtils;
import dummydomain.yetanothercallblocker.work.BnetzaUpdateWorker;
import dummydomain.yetanothercallblocker.work.ListsUpdateScheduler;
import dummydomain.yetanothercallblocker.work.PhoneBlockSyncWorker;

/**
 * Settings → "Backup": "Create backup" writes a ZIP file chosen with the system file
 * picker, "Restore" reads one, shows what it contains, lets the user pick the parts and
 * restores them. The work runs on a background thread; the results are shown in dialogs.
 *
 * <p>Create it in a field initializer of the fragment (it registers the activity result
 * launchers) and call {@link #onSaveInstanceState} / {@link #onRestoreInstanceState}.</p>
 */
final class BackupController {

    private static final String STATE_INCLUDE_SECRETS = "BACKUP_INCLUDE_SECRETS";

    private static final String MIME_ZIP = "application/zip";
    private static final String[] OPEN_MIME_TYPES = {
            MIME_ZIP, "application/x-zip-compressed", "application/octet-stream"};

    private static final Logger LOG = LoggerFactory.getLogger(BackupController.class);

    private final Fragment fragment;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String> createLauncher;
    private final ActivityResultLauncher<String[]> openLauncher;

    /** The secrets choice while the file picker is open. */
    private boolean includeSecrets = true;

    private AlertDialog progressDialog;

    BackupController(Fragment fragment) {
        this.fragment = fragment;
        createLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.CreateDocument(MIME_ZIP), this::onCreateDocument);
        openLauncher = fragment.registerForActivityResult(
                new ActivityResultContracts.OpenDocument(), this::onOpenDocument);
    }

    void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_INCLUDE_SECRETS, includeSecrets);
    }

    void onRestoreInstanceState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            includeSecrets = savedInstanceState.getBoolean(STATE_INCLUDE_SECRETS, true);
        }
    }

    /** Call from {@code onDestroy()}. */
    void shutdown() {
        dismissProgress();
        executor.shutdown();
    }

    // creating

    /** "Create backup": asks about the PhoneBlock key (if there is one), then picks a file. */
    void startCreate() {
        if (TextUtils.isEmpty(App.getSettings().getPhoneBlockToken())) {
            includeSecrets = false;
            launchCreate();
            return;
        }
        new MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.backup_secrets_title)
                .setMessage(R.string.backup_secrets_message)
                .setPositiveButton(R.string.backup_secrets_include, (d, w) -> {
                    includeSecrets = true;
                    launchCreate();
                })
                .setNegativeButton(R.string.backup_secrets_exclude, (d, w) -> {
                    includeSecrets = false;
                    launchCreate();
                })
                .setNeutralButton(android.R.string.cancel, null)
                .show();
    }

    private void launchCreate() {
        String name = "callguard-backup-"
                + new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date()) + ".zip";
        try {
            createLauncher.launch(name);
        } catch (ActivityNotFoundException e) {
            LOG.warn("launchCreate()", e);
            toast(R.string.backup_no_file_app);
        }
    }

    private void onCreateDocument(Uri uri) {
        if (uri == null || !fragment.isAdded()) return;

        Context context = fragment.requireContext().getApplicationContext();
        boolean secrets = includeSecrets;
        showProgress(R.string.backup_creating);
        executor.execute(() -> {
            String error = null;
            try {
                BackupBundle bundle = createBundle(secrets);
                writeBundle(context.getContentResolver(), uri, bundle);
            } catch (Exception e) {
                LOG.error("onCreateDocument() failed", e);
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            String name = getDisplayName(context.getContentResolver(), uri);
            String failure = error;
            runOnUi(activity -> {
                dismissProgress();
                if (failure != null) {
                    showMessage(activity, R.string.backup_failed_title,
                            activity.getString(R.string.backup_failed_message, failure), null);
                } else {
                    showMessage(activity, R.string.backup_created_title,
                            activity.getString(secrets ? R.string.backup_created_message_secrets
                                    : R.string.backup_created_message, name), null);
                }
            });
        });
    }

    private static BackupBundle createBundle(boolean secrets) throws IOException {
        Settings settings = App.getSettings();

        String blacklistCsv = null;
        if (YacbHolder.getBlacklistDao() != null) {
            StringBuilder sb = new StringBuilder();
            if (!new BlacklistImporterExporter().writeBackup(
                    YacbHolder.getBlacklistDao().loadAll(), sb)) {
                throw new IOException("The blacklist could not be exported");
            }
            blacklistCsv = sb.toString();
        }

        return BackupCreator.create(settings.getAll(), secrets, blacklistCsv,
                YacbHolder.getRulesManager() != null
                        ? YacbHolder.getRulesManager().getRules() : null,
                YacbHolder.getUserMarksStore() != null
                        ? YacbHolder.getUserMarksStore().getFile() : null,
                YacbHolder.getNumberListStore(),
                BuildConfig.VERSION_NAME, System.currentTimeMillis());
    }

    private static void writeBundle(ContentResolver resolver, Uri uri, BackupBundle bundle)
            throws IOException {
        OutputStream out;
        try {
            out = resolver.openOutputStream(uri, "wt"); // truncate an existing file
        } catch (FileNotFoundException | IllegalArgumentException e) {
            out = resolver.openOutputStream(uri, "w");
        }
        if (out == null) throw new IOException("Can't open " + uri);
        try (OutputStream o = out) {
            BackupArchive.write(bundle, o);
        }
    }

    // restoring

    /** "Restore": picks a file. */
    void startRestore() {
        try {
            openLauncher.launch(OPEN_MIME_TYPES);
        } catch (ActivityNotFoundException e) {
            LOG.warn("startRestore()", e);
            toast(R.string.backup_no_file_app);
        }
    }

    private void onOpenDocument(Uri uri) {
        if (uri == null || !fragment.isAdded()) return;

        Context context = fragment.requireContext().getApplicationContext();
        showProgress(R.string.backup_reading);
        executor.execute(() -> {
            BackupBundle bundle = null;
            BackupSummary summary = null;
            Exception error = null;
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Can't open " + uri);
                bundle = BackupArchive.read(in);
                summary = BackupSummary.of(bundle);
            } catch (Exception e) {
                LOG.warn("onOpenDocument() failed", e);
                error = e;
            }
            BackupBundle b = bundle;
            BackupSummary s = summary;
            Exception failure = error;
            runOnUi(activity -> {
                dismissProgress();
                if (failure != null) {
                    showMessage(activity, R.string.backup_error_title,
                            getErrorMessage(activity, failure), null);
                } else {
                    confirmRestore(activity, b, s);
                }
            });
        });
    }

    private static String getErrorMessage(Context context, Exception e) {
        if (e instanceof BackupException) {
            switch (((BackupException) e).getReason()) {
                case NOT_A_BACKUP:
                    return context.getString(R.string.backup_error_not_a_backup);
                case NEWER_VERSION:
                    return context.getString(R.string.backup_error_newer);
                case TOO_LARGE:
                    return context.getString(R.string.backup_error_too_large);
                case CORRUPT:
                default:
                    return context.getString(R.string.backup_error_corrupt);
            }
        }
        return context.getString(R.string.backup_error_read,
                e.getMessage() != null ? e.getMessage() : e.toString());
    }

    /** Shows the parts of the backup as a checklist; all parts are checked. */
    private void confirmRestore(FragmentActivity activity, BackupBundle bundle,
                                BackupSummary summary) {
        List<BackupRestorer.Part> parts = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        if (summary.hasSettings()) {
            parts.add(BackupRestorer.Part.SETTINGS);
            labels.add(activity.getString(summary.hasPhoneBlockToken()
                            ? R.string.backup_part_settings_token : R.string.backup_part_settings,
                    summary.getSettingsCount()));
        }
        if (summary.hasLists()) {
            parts.add(BackupRestorer.Part.LISTS);
            StringBuilder names = new StringBuilder();
            for (BackupSummary.ListInfo list : summary.getLists()) {
                if (names.length() > 0) names.append(", ");
                names.append(list.getDisplayName());
            }
            labels.add(activity.getString(R.string.backup_part_lists, names));
        }
        if (summary.hasRules()) {
            parts.add(BackupRestorer.Part.RULES);
            labels.add(activity.getString(R.string.backup_part_rules, summary.getRulesCount()));
        }
        if (summary.hasUserMarks()) {
            parts.add(BackupRestorer.Part.USER_MARKS);
            labels.add(activity.getString(R.string.backup_part_marks,
                    summary.getUserMarksCount()));
        }
        if (summary.hasBlacklist()) {
            parts.add(BackupRestorer.Part.BLACKLIST);
            labels.add(activity.getString(R.string.backup_part_blacklist,
                    summary.getBlacklistCount()));
        }

        String date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(summary.getCreatedAt()));
        String title = summary.getAppVersion() != null
                ? activity.getString(R.string.backup_restore_title_version, date,
                summary.getAppVersion())
                : activity.getString(R.string.backup_restore_title, date);

        if (parts.isEmpty()) {
            showMessage(activity, R.string.backup_error_title,
                    activity.getString(R.string.backup_restore_nothing), null);
            return;
        }

        boolean[] checked = new boolean[parts.size()];
        java.util.Arrays.fill(checked, true);
        new MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMultiChoiceItems(labels.toArray(new String[0]), checked,
                        (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.backup_restore_button, (d, w) -> {
                    BackupRestorer.Options options = new BackupRestorer.Options();
                    options.settings = isChecked(parts, checked, BackupRestorer.Part.SETTINGS);
                    options.lists = isChecked(parts, checked, BackupRestorer.Part.LISTS);
                    options.rules = isChecked(parts, checked, BackupRestorer.Part.RULES);
                    options.userMarks = isChecked(parts, checked,
                            BackupRestorer.Part.USER_MARKS);
                    options.blacklist = isChecked(parts, checked, BackupRestorer.Part.BLACKLIST);
                    restore(bundle, options);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static boolean isChecked(List<BackupRestorer.Part> parts, boolean[] checked,
                                     BackupRestorer.Part part) {
        int index = parts.indexOf(part);
        return index >= 0 && checked[index];
    }

    private void restore(BackupBundle bundle, BackupRestorer.Options options) {
        if (!fragment.isAdded()) return;
        Context context = fragment.requireContext().getApplicationContext();
        showProgress(R.string.backup_restoring);
        executor.execute(() -> {
            Settings settings = App.getSettings();
            boolean monitoringBefore = settings.getUseMonitoringService();

            BackupRestorer.Result result = createRestorer().restore(bundle, options);

            applySettingsInBackground(context, result);
            boolean monitoringAfter = settings.getUseMonitoringService();

            runOnUi(activity -> {
                dismissProgress();
                applySettingsOnUi(activity, result, monitoringBefore != monitoringAfter);
                showResult(activity, result);
            });
        });
    }

    private static BackupRestorer createRestorer() {
        Settings settings = App.getSettings();
        BackupRestorer.SettingsTarget settingsTarget = new BackupRestorer.SettingsTarget() {
            @Override
            public Map<String, ?> getAll() {
                return settings.getAll();
            }

            @Override
            public boolean applyAll(Map<String, ?> toSet, Collection<String> toRemove) {
                return settings.applyAll(toSet, toRemove);
            }
        };
        BackupRestorer.BlacklistTarget blacklistTarget = csv -> {
            if (YacbHolder.getBlacklistDao() == null) return -1;
            return new BlacklistImporterExporter().importBlacklist(
                    YacbHolder.getBlacklistDao(), YacbHolder.getBlacklistService(),
                    new ByteArrayInputStream(csv));
        };
        return new BackupRestorer(settingsTarget, blacklistTarget,
                YacbHolder.getRulesManager(), YacbHolder.getUserMarksStore(),
                YacbHolder.getSourcesManager());
    }

    /** Lets the rest of the app pick up restored settings and lists (background thread). */
    private static void applySettingsInBackground(Context context, BackupRestorer.Result result) {
        Settings settings = App.getSettings();
        try {
            if (result.getRestored().containsKey(BackupRestorer.Part.SETTINGS)
                    && YacbHolder.getDbManager() != null) {
                YacbHolder.getDbManager().setNumberFilter(
                        DbFilteringUtils.getNumberFilter(settings));
            }
        } catch (Exception e) {
            LOG.warn("applySettingsInBackground() number filter", e);
        }
        try {
            PhoneBlockSyncWorker.updateSchedule(context, false);
        } catch (Exception e) {
            LOG.warn("applySettingsInBackground() PhoneBlock schedule", e);
        }
        try {
            BnetzaUpdateWorker.updateSchedule(context);
        } catch (Exception e) {
            LOG.warn("applySettingsInBackground() Bundesnetzagentur schedule", e);
        }
        try {
            RemoteListManager manager = YacbHolder.getRemoteListManager();
            if (manager != null) {
                ListsUpdateScheduler.get(context).update(manager.hasAutoUpdateLists());
            }
        } catch (Exception e) {
            LOG.warn("applySettingsInBackground() lists schedule", e);
        }
    }

    private static void applySettingsOnUi(Context context, BackupRestorer.Result result,
                                          boolean monitoringChanged) {
        if (!result.getRestored().containsKey(BackupRestorer.Part.SETTINGS)) return;
        Settings settings = App.getSettings();
        try {
            App.setUiMode(settings.getUiMode());
            if (monitoringChanged) {
                boolean enabled = settings.getUseMonitoringService();
                PackageManagerUtils.setComponentEnabledOrDefault(
                        context, StartupReceiver.class, enabled);
                if (enabled) {
                    CallMonitoringService.start(context);
                } else {
                    CallMonitoringService.stop(context);
                }
            }
        } catch (Exception e) {
            LOG.warn("applySettingsOnUi() failed", e);
        }
    }

    private void showResult(FragmentActivity activity, BackupRestorer.Result result) {
        String restored = joinParts(activity, result.getRestored().keySet());
        if (restored.isEmpty()) {
            restored = activity.getString(R.string.backup_restore_nothing_restored);
        }
        // the settings screen shows the restored values after it is recreated
        Runnable onDismiss = () -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) activity.recreate();
        };
        if (result.isSuccess()) {
            showMessage(activity, R.string.backup_restored_title,
                    activity.getString(R.string.backup_restored_message, restored), onDismiss);
        } else {
            showMessage(activity, R.string.backup_restore_partial_title,
                    activity.getString(R.string.backup_restore_partial_message, restored,
                            joinParts(activity, result.getFailed().keySet())), onDismiss);
        }
    }

    private static String joinParts(Context context, Collection<BackupRestorer.Part> parts) {
        StringBuilder sb = new StringBuilder();
        for (BackupRestorer.Part part : parts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(context.getString(getPartName(part)));
        }
        return sb.toString();
    }

    private static int getPartName(BackupRestorer.Part part) {
        switch (part) {
            case SETTINGS: return R.string.backup_part_name_settings;
            case LISTS: return R.string.backup_part_name_lists;
            case RULES: return R.string.backup_part_name_rules;
            case USER_MARKS: return R.string.backup_part_name_marks;
            case BLACKLIST:
            default: return R.string.backup_part_name_blacklist;
        }
    }

    // UI helpers

    private interface UiAction {
        void run(FragmentActivity activity);
    }

    /** Runs on the main thread if the screen still exists, otherwise drops the action. */
    private void runOnUi(UiAction action) {
        FragmentActivity activity = fragment.getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            FragmentActivity current = fragment.getActivity();
            if (current == null || current.isFinishing() || current.isDestroyed()) {
                progressDialog = null;
                return;
            }
            action.run(current);
        });
    }

    private void showProgress(int messageRes) {
        Context context = fragment.requireContext();
        dismissProgress();

        int padding = Math.round(24 * context.getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(padding, padding, padding, padding);

        CircularProgressIndicator indicator = new CircularProgressIndicator(context);
        indicator.setIndeterminate(true);
        layout.addView(indicator);

        TextView text = new TextView(context);
        text.setText(messageRes);
        text.setPadding(padding, 0, 0, 0);
        layout.addView(text);

        progressDialog = new MaterialAlertDialogBuilder(context)
                .setView(layout)
                .setCancelable(false)
                .show();
    }

    private void dismissProgress() {
        if (progressDialog != null) {
            try {
                progressDialog.dismiss();
            } catch (Exception e) {
                LOG.debug("dismissProgress()", e);
            }
            progressDialog = null;
        }
    }

    private static void showMessage(Context context, int titleRes, String message,
                                    Runnable onDismiss) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(d -> {
                    if (onDismiss != null) onDismiss.run();
                })
                .show();
    }

    private void toast(int messageRes) {
        Context context = fragment.getContext();
        if (context != null) Toast.makeText(context, messageRes, Toast.LENGTH_LONG).show();
    }

    private static String getDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (!TextUtils.isEmpty(name)) return name;
            }
        } catch (Exception e) {
            LOG.debug("getDisplayName() failed", e);
        }
        String segment = uri.getLastPathSegment();
        return segment != null ? segment : uri.toString();
    }

}
