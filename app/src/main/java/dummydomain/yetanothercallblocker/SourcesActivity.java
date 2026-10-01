package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dummydomain.yetanothercallblocker.data.RemoteListManager;
import dummydomain.yetanothercallblocker.data.RemoteListPresets;
import dummydomain.yetanothercallblocker.data.SourcesManager;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.provider.PhoneBlockOnlineProvider;
import dummydomain.yetanothercallblocker.data.provider.YacbDatabaseProvider;
import dummydomain.yetanothercallblocker.data.sources.BnetzaMeasuresParser;
import dummydomain.yetanothercallblocker.data.sources.NumberListFormatDetector;
import dummydomain.yetanothercallblocker.data.sources.NumberListStore;
import dummydomain.yetanothercallblocker.data.sources.ParseResult;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockClient;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockSync;
import dummydomain.yetanothercallblocker.work.PhoneBlockSyncWorker;
import dummydomain.yetanothercallblocker.data.sources.RemoteListDownloader;
import dummydomain.yetanothercallblocker.data.sources.RemoteListInfo;
import dummydomain.yetanothercallblocker.work.ListsUpdateScheduler;

/**
 * "Data sources" screen: the list of number information sources with their
 * state, enable switches, ordering, deletion and import of offline lists,
 * and the PhoneBlock settings (API key, threshold, manual sync).
 */
public class SourcesActivity extends BaseActivity {

    private static final String[] CSV_MIME_TYPES = {
            "text/*", "text/csv", "text/comma-separated-values"};
    private static final String[] BNETZA_MIME_TYPES = {
            "text/*", "text/html", "text/plain", "application/xhtml+xml"};

    /** Larger files are certainly not number lists (and could exhaust the memory). */
    private static final int MAX_IMPORT_FILE_SIZE = 20 * 1024 * 1024;

    private static final int MAX_SKIPPED_EXAMPLES = 5;

    /** Long error messages of URL lists are cut in the list row. */
    private static final int MAX_ERROR_LENGTH = 120;

    private static final String SEPARATOR = " · ";

    private static final Logger LOG = LoggerFactory.getLogger(SourcesActivity.class);

    private final SourcesManager sourcesManager = YacbHolder.getSourcesManager();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> csvPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onCsvFilePicked);
    private final ActivityResultLauncher<String[]> bnetzaPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onBnetzaFilePicked);

    private SourcesAdapter adapter;
    private LinearProgressIndicator progress;
    private ExtendedFloatingActionButton importFab;

    private int busyTasks;

    public static Intent getIntent(Context context) {
        return new Intent(context, SourcesActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sources);

        progress = findViewById(R.id.sources_progress);
        importFab = findViewById(R.id.sources_import_fab);
        importFab.setOnClickListener(v -> showImportChooser());

        adapter = new SourcesAdapter();
        RecyclerView recyclerView = findViewById(R.id.sources_list);
        recyclerView.setAdapter(adapter);

        runInBackground(this::ensureListsUpdateScheduled);
    }

    @Override
    protected void onDestroy() {
        // a running import is allowed to finish, its result is just not shown
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        // always started from the settings screen, which is still in the back stack
        finish();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_sources, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_import_csv) {
            pickCsvFile();
            return true;
        } else if (id == R.id.menu_import_bnetza) {
            showBnetzaImportInfo();
            return true;
        } else if (id == R.id.menu_phoneblock) {
            showPhoneBlockDialog();
            return true;
        } else if (id == R.id.menu_add_url) {
            showAddUrlChooser();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // background work

    private interface BackgroundAction {
        void run() throws Exception;
    }

    /**
     * Runs the action (may be null) on the background thread, then reloads the list.
     */
    private void runInBackground(BackgroundAction action) {
        setBusy(true);
        executor.execute(() -> {
            try {
                if (action != null) action.run();
            } catch (Exception e) {
                LOG.warn("runInBackground() action failed", e);
            }

            List<SourcesManager.SourceInfo> sources;
            try {
                sources = sourcesManager.getSources();
            } catch (Exception e) {
                LOG.error("runInBackground() failed to get sources", e);
                sources = Collections.emptyList();
            }

            List<SourcesManager.SourceInfo> result = sources;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                setBusy(false);
                adapter.setItems(result);
            });
        });
    }

    private void setBusy(boolean busy) {
        busyTasks += busy ? 1 : -1;
        boolean show = busyTasks > 0;
        progress.setVisibility(show ? View.VISIBLE : View.GONE);
        importFab.setEnabled(!show);
    }

    // actions

    private void onEnabledChanged(SourcesManager.SourceInfo source, boolean enabled) {
        runInBackground(() -> sourcesManager.setEnabled(source.getId(), enabled));

        if (enabled && PhoneBlockOnlineProvider.ID.equals(source.getId())
                && TextUtils.isEmpty(App.getSettings().getPhoneBlockToken())) {
            showPhoneBlockDialog();
        }
    }

    private static boolean isPhoneBlockSource(SourcesManager.SourceInfo source) {
        return PhoneBlockSync.SOURCE_ID.equals(source.getId())
                || PhoneBlockOnlineProvider.ID.equals(source.getId());
    }

    private void onMoveUp(SourcesManager.SourceInfo source) {
        runInBackground(() -> sourcesManager.moveUp(source.getId()));
    }

    private void onMoveDown(SourcesManager.SourceInfo source) {
        runInBackground(() -> sourcesManager.moveDown(source.getId()));
    }

    private void onDelete(SourcesManager.SourceInfo source) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.source_delete)
                .setMessage(getString(R.string.source_delete_confirmation,
                        getSourceName(source)))
                .setPositiveButton(R.string.source_delete_button, (d, w) ->
                        runInBackground(() -> {
                            sourcesManager.deleteList(source.getId());
                            if (PhoneBlockSync.SOURCE_ID.equals(source.getId())) {
                                // the next sync downloads the full list again
                                PhoneBlockSync sync = YacbHolder.getPhoneBlockSync();
                                if (sync != null) sync.reset();
                            }
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showImportChooser() {
        CharSequence[] items = {
                getString(R.string.sources_import_csv),
                getString(R.string.sources_import_bnetza),
                getString(R.string.sources_add_url),
                getString(R.string.phoneblock_menu)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sources_import)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        pickCsvFile();
                    } else if (which == 1) {
                        showBnetzaImportInfo();
                    } else if (which == 2) {
                        showAddUrlChooser();
                    } else {
                        showPhoneBlockDialog();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showBnetzaImportInfo() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sources_import_bnetza)
                .setMessage(getString(R.string.sources_import_bnetza_message,
                        BnetzaMeasuresParser.SOURCE_URL))
                .setPositiveButton(R.string.sources_choose_file, (d, w) -> pickBnetzaFile())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickCsvFile() {
        try {
            csvPicker.launch(CSV_MIME_TYPES);
        } catch (Exception e) { // ActivityNotFoundException
            LOG.warn("pickCsvFile()", e);
            showError(getString(R.string.sources_no_file_picker));
        }
    }

    private void pickBnetzaFile() {
        try {
            bnetzaPicker.launch(BNETZA_MIME_TYPES);
        } catch (Exception e) { // ActivityNotFoundException
            LOG.warn("pickBnetzaFile()", e);
            showError(getString(R.string.sources_no_file_picker));
        }
    }

    // PhoneBlock

    private static final class PhoneBlockOutcome {
        PhoneBlockSync.SyncResult result;
        int minVotes;
        String error;
        boolean authError;
        boolean rateLimited;
    }

    private void showPhoneBlockDialog() {
        PhoneBlockSync sync = YacbHolder.getPhoneBlockSync();
        if (sync == null) return;

        Settings settings = App.getSettings();

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_phoneblock, null);
        TextInputEditText tokenInput = view.findViewById(R.id.phoneblock_token);
        Spinner minVotesSpinner = view.findViewById(R.id.phoneblock_min_votes);
        TextView status = view.findViewById(R.id.phoneblock_status);

        tokenInput.setText(settings.getPhoneBlockToken());

        int[] options = PhoneBlockSync.MIN_VOTES_OPTIONS;
        List<String> labels = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < options.length; i++) {
            labels.add(String.valueOf(options[i]));
            if (options[i] == settings.getPhoneBlockMinVotes()) selected = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        minVotesSpinner.setAdapter(adapter);
        minVotesSpinner.setSelection(selected);

        view.findViewById(R.id.phoneblock_get_token).setOnClickListener(v ->
                openUrl(PhoneBlockClient.TOKEN_PAGE_URL));

        status.setText(R.string.phoneblock_status_never);
        executor.execute(() -> {
            PhoneBlockSync.Status s = sync.getStatus();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                status.setText(s.getLastSync() > 0
                        ? getString(R.string.phoneblock_status, formatDate(s.getLastSync()),
                        s.getTotalNumbers())
                        : getString(R.string.phoneblock_status_never));
            });
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.phoneblock_title)
                .setView(view)
                .setPositiveButton(R.string.phoneblock_save, (d, w) -> {
                    savePhoneBlockSettings(tokenInput, minVotesSpinner, false);
                })
                .setNeutralButton(R.string.phoneblock_sync_now, (d, w) -> {
                    savePhoneBlockSettings(tokenInput, minVotesSpinner, true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void savePhoneBlockSettings(TextInputEditText tokenInput, Spinner minVotesSpinner,
                                        boolean syncNow) {
        Settings settings = App.getSettings();

        CharSequence text = tokenInput.getText();
        String token = text != null ? text.toString().trim() : "";
        int position = minVotesSpinner.getSelectedItemPosition();
        int minVotes = position >= 0 && position < PhoneBlockSync.MIN_VOTES_OPTIONS.length
                ? PhoneBlockSync.MIN_VOTES_OPTIONS[position] : PhoneBlockSync.DEFAULT_MIN_VOTES;

        boolean tokenChanged = !token.equals(settings.getPhoneBlockToken());
        boolean minVotesChanged = minVotes != settings.getPhoneBlockMinVotes();

        settings.setPhoneBlockToken(token);
        settings.setPhoneBlockMinVotes(minVotes);

        if (tokenChanged) {
            try {
                PhoneBlockSyncWorker.updateSchedule(this, true);
            } catch (Exception e) {
                LOG.warn("savePhoneBlockSettings() failed to schedule the sync", e);
            }
        }

        if (syncNow) {
            if (token.isEmpty()) {
                showError(R.string.phoneblock_sync_failed_title,
                        getString(R.string.phoneblock_token_required));
            } else {
                syncPhoneBlock(token, minVotes);
            }
        } else if (minVotesChanged) {
            PhoneBlockSync sync = YacbHolder.getPhoneBlockSync();
            if (sync != null) runInBackground(() -> sync.rebuildList(minVotes));
        }
    }

    private void syncPhoneBlock(String token, int minVotes) {
        PhoneBlockSync sync = YacbHolder.getPhoneBlockSync();
        if (sync == null) return;

        PhoneBlockOutcome outcome = new PhoneBlockOutcome();
        outcome.minVotes = minVotes;
        runInBackground(() -> {
            try {
                outcome.result = sync.sync(token, minVotes, true);
                // a skipped sync still applies a changed threshold
                if (outcome.result.isSkipped()) sync.rebuildList(minVotes);
            } catch (PhoneBlockClient.ApiException e) {
                LOG.warn("syncPhoneBlock() API error", e);
                outcome.authError = e.isAuthError();
                outcome.rateLimited = e.isRateLimited();
                outcome.error = e.getMessage();
            } catch (Exception e) {
                LOG.warn("syncPhoneBlock() failed", e);
                outcome.error = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showPhoneBlockResult(outcome);
                });
            }
        });
    }

    private void showPhoneBlockResult(PhoneBlockOutcome outcome) {
        if (outcome.result == null) {
            String message;
            if (outcome.authError) {
                message = getString(R.string.phoneblock_sync_auth_error);
            } else if (outcome.rateLimited) {
                message = getString(R.string.phoneblock_sync_rate_limited);
            } else {
                message = getString(R.string.phoneblock_sync_error,
                        outcome.error != null ? outcome.error : "");
            }
            showError(R.string.phoneblock_sync_failed_title, message);
            return;
        }

        PhoneBlockSync.SyncResult result = outcome.result;
        String message;
        if (result.isSkipped()) {
            message = getString(R.string.phoneblock_sync_skipped,
                    TimeUnit.MILLISECONDS.toMinutes(PhoneBlockSync.MIN_MANUAL_SYNC_INTERVAL_MILLIS));
        } else if (result.isFull()) {
            message = getString(R.string.phoneblock_sync_result_full,
                    result.getTotalNumbers(), outcome.minVotes, result.getListedNumbers());
        } else {
            message = getString(R.string.phoneblock_sync_result_incremental,
                    result.getReceived(), result.getRemoved(), outcome.minVotes,
                    result.getListedNumbers(), result.getTotalNumbers());
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.phoneblock_sync_result_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            LOG.warn("openUrl() no browser", e);
            showError(R.string.phoneblock_title, getString(R.string.phoneblock_no_browser, url));
        }
    }

    // import

    private interface Parser {
        ParseResult parse(String content) throws IOException;
    }

    private static final class ImportOutcome {
        ParseResult parseResult;
        String error;
        boolean tooLarge;
    }

    private static final class FileTooLargeException extends IOException {
        FileTooLargeException() {
            super("File is too large");
        }
    }

    private void onCsvFilePicked(Uri uri) {
        if (uri == null) return;

        String displayName = SourcesManager.fileNameToDisplayName(getFileName(uri));
        if (TextUtils.isEmpty(displayName)) displayName = getString(R.string.sources_csv_default_name);

        // CSV / plain text, but also Fritz!Box phone books and vCards
        importFile(uri, SourcesManager.csvSourceId(displayName), displayName,
                NumberListFormatDetector::parse);
    }

    private void onBnetzaFilePicked(Uri uri) {
        if (uri == null) return;

        importFile(uri, SourcesManager.BNETZA_SOURCE_ID, SourcesManager.BNETZA_DISPLAY_NAME,
                content -> {
                    BnetzaMeasuresParser parser = new BnetzaMeasuresParser();
                    return looksLikeHtml(content)
                            ? parser.parseHtml(content)
                            : parser.parseText(new StringReader(content));
                });
    }

    static boolean looksLikeHtml(String content) {
        String s = content.length() > 65536 ? content.substring(0, 65536) : content;
        s = s.toLowerCase(Locale.ROOT);
        return s.contains("<tr") || s.contains("<table") || s.contains("<html")
                || s.contains("<!doctype html");
    }

    private void importFile(Uri uri, String sourceId, String displayName, Parser parser) {
        LOG.debug("importFile() uri={}, sourceId={}", uri, sourceId);

        ImportOutcome outcome = new ImportOutcome();
        runInBackground(() -> {
            try {
                String content = readText(uri);
                ParseResult result = parser.parse(content);
                outcome.parseResult = result;

                if (!result.getEntries().isEmpty()) {
                    sourcesManager.importList(sourceId, displayName, result.getEntries(),
                            System.currentTimeMillis());
                }
            } catch (FileTooLargeException e) {
                outcome.tooLarge = true;
            } catch (Exception e) {
                LOG.warn("importFile() failed", e);
                outcome.error = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showImportResult(outcome);
                });
            }
        });
    }

    private String readText(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Can't open " + uri);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_IMPORT_FILE_SIZE) throw new FileTooLargeException();
            }

            String s = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1); // BOM
            return s;
        }
    }

    private String getFileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (!TextUtils.isEmpty(name)) return name;
                }
            }
        } catch (Exception e) {
            LOG.debug("getFileName()", e);
        }
        return uri.getLastPathSegment();
    }

    private void showImportResult(ImportOutcome outcome) {
        if (outcome.tooLarge) {
            showError(getString(R.string.sources_import_file_too_large));
            return;
        }
        if (outcome.error != null || outcome.parseResult == null) {
            showError(getString(R.string.sources_import_failed,
                    outcome.error != null ? outcome.error : ""));
            return;
        }

        ParseResult result = outcome.parseResult;
        int imported = result.getEntries().size();
        int skipped = result.getSkipped().size();

        StringBuilder message = new StringBuilder();
        if (imported == 0) {
            message.append(getString(R.string.sources_import_nothing_found));
            message.append("\n\n");
        }
        message.append(getString(R.string.sources_import_result, imported, skipped));

        if (skipped > 0) {
            message.append("\n\n").append(getString(R.string.sources_import_skipped_examples));
            List<ParseResult.SkippedLine> lines = result.getSkipped();
            for (int i = 0; i < Math.min(MAX_SKIPPED_EXAMPLES, lines.size()); i++) {
                ParseResult.SkippedLine line = lines.get(i);
                message.append('\n').append(getString(R.string.sources_import_skipped_line,
                        line.getLineNumber(), line.getReason()));
            }
            if (skipped > MAX_SKIPPED_EXAMPLES) message.append("\n…");
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(imported > 0 ? R.string.sources_import_result_title
                        : R.string.sources_import_failed_title)
                .setMessage(message.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showError(String message) {
        showError(R.string.sources_import_failed_title, message);
    }

    private void showError(int titleId, String message) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleId)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // lists from URL

    /**
     * The periodic update survives reboots and app updates, but not e.g. a restore of
     * the app data from a backup; re-schedule it if a list needs it (a no-op otherwise).
     */
    private void ensureListsUpdateScheduled() {
        RemoteListManager manager = YacbHolder.getRemoteListManager();
        if (manager != null && manager.hasAutoUpdateLists()) {
            ListsUpdateScheduler.get(this).schedule();
        }
    }

    private void showAddUrlChooser() {
        List<RemoteListPresets.Preset> presets = RemoteListPresets.getPresets();
        if (presets.isEmpty()) {
            showAddUrlDialog(null);
            return;
        }

        CharSequence[] items = new CharSequence[presets.size() + 1];
        for (int i = 0; i < presets.size(); i++) {
            RemoteListPresets.Preset preset = presets.get(i);
            items[i] = preset.getName() + "\n" + getString(R.string.sources_add_url_preset_summary,
                    preset.getLicense(), preset.getLastUpdated());
        }
        items[presets.size()] = getString(R.string.sources_add_url_enter);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sources_add_url)
                .setItems(items, (d, which) ->
                        showAddUrlDialog(which < presets.size() ? presets.get(which) : null))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Shows the "add list by URL" dialog, pre-filled from the preset (may be null).
     * Nothing is downloaded before the user confirms.
     */
    private void showAddUrlDialog(RemoteListPresets.Preset preset) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_url_list, null);
        TextView info = view.findViewById(R.id.add_url_info);
        TextInputLayout urlLayout = view.findViewById(R.id.add_url_url_layout);
        EditText urlEdit = view.findViewById(R.id.add_url_url);
        EditText nameEdit = view.findViewById(R.id.add_url_name);
        CheckBox autoUpdate = view.findViewById(R.id.add_url_auto_update);

        if (preset != null) {
            info.setText(getString(R.string.sources_add_url_preset_info,
                    preset.getHomepage(), preset.getLicense()));
            urlEdit.setText(preset.getUrl());
            nameEdit.setText(preset.getName());
        } else {
            info.setText(R.string.sources_add_url_hint);
        }

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(preset != null ? preset.getName() : getString(R.string.sources_add_url))
                .setView(view)
                .setPositiveButton(R.string.sources_add_url_button, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        // validate before closing the dialog
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String url;
                    try {
                        url = RemoteListManager.normalizeUrl(urlEdit.getText().toString());
                    } catch (IllegalArgumentException e) {
                        urlLayout.setError(getString(R.string.sources_add_url_invalid));
                        return;
                    }
                    urlLayout.setError(null);
                    dialog.dismiss();
                    addUrlList(url, nameEdit.getText().toString().trim(), autoUpdate.isChecked());
                }));
        dialog.show();
    }

    private void addUrlList(String url, String name, boolean autoUpdate) {
        LOG.debug("addUrlList() url={}, autoUpdate={}", url, autoUpdate);

        RemoteListManager manager = YacbHolder.getRemoteListManager();
        ImportOutcome outcome = new ImportOutcome();
        runInBackground(() -> {
            try {
                RemoteListManager.UpdateResult result = manager.addList(url,
                        TextUtils.isEmpty(name) ? null : name, autoUpdate);
                outcome.parseResult = result.getParseResult();
                if (autoUpdate) ListsUpdateScheduler.get(this).schedule();
            } catch (RemoteListManager.NoNumbersException e) {
                outcome.error = getString(R.string.sources_import_nothing_found);
            } catch (Exception e) {
                LOG.warn("addUrlList() failed", e);
                outcome.error = describeDownloadError(e);
            } finally {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showImportResult(outcome);
                });
            }
        });
    }

    private String describeDownloadError(Exception e) {
        if (e instanceof RemoteListDownloader.TooLargeException) {
            return getString(R.string.sources_import_file_too_large);
        }
        if (e instanceof NumberListFormatDetector.UnsupportedFormatException) {
            return getString(((NumberListFormatDetector.UnsupportedFormatException) e).getFormat()
                    == NumberListFormatDetector.Format.HTML
                    ? R.string.sources_url_html_error : R.string.sources_url_unsupported_format);
        }
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        return getString(R.string.sources_url_download_failed, message);
    }

    private void showUrlListActions(SourcesManager.SourceInfo source) {
        RemoteListInfo remote = RemoteListManager.getRemote(source);
        if (remote == null) return;

        CharSequence[] items = {
                getString(R.string.source_url_update_now),
                getString(remote.isAutoUpdate() ? R.string.source_url_disable_auto_update
                        : R.string.source_url_enable_auto_update)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(getSourceName(source))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        updateUrlList(source);
                    } else {
                        setUrlListAutoUpdate(source, !remote.isAutoUpdate());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void updateUrlList(SourcesManager.SourceInfo source) {
        RemoteListManager manager = YacbHolder.getRemoteListManager();
        RemoteListManager.UpdateResult[] result = new RemoteListManager.UpdateResult[1];
        runInBackground(() -> {
            try {
                result[0] = manager.update(source.getId());
            } finally {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showUpdateResult(result[0]);
                });
            }
        });
    }

    private void showUpdateResult(RemoteListManager.UpdateResult result) {
        if (result == null) return; // deleted meanwhile

        switch (result.getStatus()) {
            case UPDATED:
                Toast.makeText(this, getString(R.string.source_url_updated,
                        result.getParseResult().getEntries().size()), Toast.LENGTH_LONG).show();
                break;
            case NOT_MODIFIED:
                Toast.makeText(this, R.string.source_url_not_modified, Toast.LENGTH_SHORT).show();
                break;
            default:
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.sources_import_failed_title)
                        .setMessage(getString(R.string.source_url_update_failed, result.getError()))
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                break;
        }
    }

    private void setUrlListAutoUpdate(SourcesManager.SourceInfo source, boolean autoUpdate) {
        RemoteListManager manager = YacbHolder.getRemoteListManager();
        runInBackground(() -> {
            manager.setAutoUpdate(source.getId(), autoUpdate);
            ListsUpdateScheduler.get(this).update(manager.hasAutoUpdateLists());
        });
    }

    private void addRemoteDetails(List<String> parts, NumberListStore.ListMetadata metadata) {
        RemoteListInfo remote = metadata.getRemote();

        String host = Uri.parse(remote.getUrl()).getHost();
        if (!TextUtils.isEmpty(host)) parts.add(host);

        parts.add(getString(remote.isAutoUpdate() ? R.string.source_url_auto_update_on
                : R.string.source_url_auto_update_off));

        if (remote.getLastError() != null) {
            String error = remote.getLastError();
            if (error.length() > MAX_ERROR_LENGTH) error = error.substring(0, MAX_ERROR_LENGTH) + "…";
            parts.add(getString(R.string.source_url_last_error,
                    formatDate(remote.getLastCheckAt()), error));
        } else if (remote.getLastCheckAt() > metadata.getImportedAt()) {
            parts.add(getString(R.string.source_url_checked, formatDate(remote.getLastCheckAt())));
        }
    }

    // presentation

    private String getSourceName(SourcesManager.SourceInfo source) {
        if (YacbDatabaseProvider.ID.equals(source.getId())) {
            return getString(R.string.source_yacb_name);
        }
        if (PhoneBlockOnlineProvider.ID.equals(source.getId())) {
            return getString(R.string.source_phoneblock_online_name);
        }
        String name = source.getDisplayName();
        return !TextUtils.isEmpty(name) ? name : source.getId();
    }

    private String getSourceType(SourcesManager.SourceInfo source) {
        return getString(source.isOffline() ? R.string.source_type_offline
                : R.string.source_type_online)
                + SEPARATOR
                + getString(PhoneBlockSync.SOURCE_ID.equals(source.getId())
                ? R.string.source_kind_synchronized
                : !source.isImported() ? R.string.source_kind_built_in
                : RemoteListManager.getRemote(source) != null ? R.string.source_kind_url
                : R.string.source_kind_imported);
    }

    private String getSourceDetails(SourcesManager.SourceInfo source) {
        List<String> parts = new ArrayList<>();

        if (source.isLoadFailed()) parts.add(getString(R.string.source_load_error));

        NumberListStore.ListMetadata metadata = source.getMetadata();
        if (metadata != null) {
            if (!source.isLoadFailed()) {
                parts.add(getResources().getQuantityString(R.plurals.source_entry_count,
                        metadata.getEntryCount(), metadata.getEntryCount()));
            }
            if (metadata.getImportedAt() > 0) {
                parts.add(getString(R.string.source_updated, formatDate(metadata.getImportedAt())));
            }
            if (metadata.getRemote() != null) addRemoteDetails(parts, metadata);
        } else if (YacbDatabaseProvider.ID.equals(source.getId())) {
            long lastUpdate = App.getSettings().getLastUpdateTime();
            parts.add(lastUpdate > 0
                    ? getString(R.string.source_updated, formatDate(lastUpdate))
                    : getString(R.string.source_never_updated));
        } else if (PhoneBlockOnlineProvider.ID.equals(source.getId())) {
            parts.add(getString(TextUtils.isEmpty(App.getSettings().getPhoneBlockToken())
                    ? R.string.phoneblock_no_token : R.string.phoneblock_online_details));
        }

        return TextUtils.join(SEPARATOR, parts);
    }

    private String formatDate(long timestamp) {
        return DateUtils.formatDateTime(this, timestamp, DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_TIME);
    }

    private class SourcesAdapter extends RecyclerView.Adapter<SourcesAdapter.ViewHolder> {

        private List<SourcesManager.SourceInfo> items = Collections.emptyList();

        @SuppressLint("NotifyDataSetChanged") // the list is tiny
        void setItems(List<SourcesManager.SourceInfo> items) {
            this.items = new ArrayList<>(items);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.source_item, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            holder.bind(items.get(position), position, items.size());
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {

            final TextView name;
            final TextView type;
            final TextView details;
            final AppCompatImageButton urlActions;
            final AppCompatImageButton moveUp;
            final AppCompatImageButton moveDown;
            final AppCompatImageButton delete;
            final MaterialSwitch enabled;

            ViewHolder(View view) {
                super(view);
                name = view.findViewById(R.id.source_name);
                type = view.findViewById(R.id.source_type);
                details = view.findViewById(R.id.source_details);
                urlActions = view.findViewById(R.id.source_url_actions);
                moveUp = view.findViewById(R.id.source_move_up);
                moveDown = view.findViewById(R.id.source_move_down);
                delete = view.findViewById(R.id.source_delete);
                enabled = view.findViewById(R.id.source_enabled);
            }

            void bind(SourcesManager.SourceInfo source, int position, int count) {
                if (isPhoneBlockSource(source)) {
                    itemView.setOnClickListener(v -> showPhoneBlockDialog());
                } else {
                    itemView.setOnClickListener(null);
                    itemView.setClickable(false);
                }

                name.setText(getSourceName(source));
                type.setText(getSourceType(source));

                String detailsText = getSourceDetails(source);
                details.setText(detailsText);
                details.setVisibility(TextUtils.isEmpty(detailsText) ? View.GONE : View.VISIBLE);

                boolean urlList = RemoteListManager.getRemote(source) != null;
                urlActions.setVisibility(urlList ? View.VISIBLE : View.GONE);
                urlActions.setOnClickListener(v -> showUrlListActions(source));

                moveUp.setEnabled(position > 0);
                moveUp.setAlpha(position > 0 ? 1f : 0.38f);
                moveUp.setOnClickListener(v -> onMoveUp(source));

                moveDown.setEnabled(position < count - 1);
                moveDown.setAlpha(position < count - 1 ? 1f : 0.38f);
                moveDown.setOnClickListener(v -> onMoveDown(source));

                delete.setVisibility(source.isImported() ? View.VISIBLE : View.INVISIBLE);
                delete.setOnClickListener(v -> onDelete(source));

                enabled.setOnCheckedChangeListener(null);
                enabled.setChecked(source.isEnabled());
                enabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                        onEnabledChanged(source, isChecked));
            }
        }
    }

}
