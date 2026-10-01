package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
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
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dummydomain.yetanothercallblocker.data.SourcesManager;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.provider.YacbDatabaseProvider;
import dummydomain.yetanothercallblocker.data.sources.BnetzaMeasuresParser;
import dummydomain.yetanothercallblocker.data.sources.CsvNumberListParser;
import dummydomain.yetanothercallblocker.data.sources.NumberListStore;
import dummydomain.yetanothercallblocker.data.sources.ParseResult;

/**
 * "Data sources" screen: the list of number information sources with their
 * state, enable switches, ordering, deletion and import of offline lists.
 */
public class SourcesActivity extends BaseActivity {

    private static final String[] CSV_MIME_TYPES = {
            "text/*", "text/csv", "text/comma-separated-values"};
    private static final String[] BNETZA_MIME_TYPES = {
            "text/*", "text/html", "text/plain", "application/xhtml+xml"};

    /** Larger files are certainly not number lists (and could exhaust the memory). */
    private static final int MAX_IMPORT_FILE_SIZE = 20 * 1024 * 1024;

    private static final int MAX_SKIPPED_EXAMPLES = 5;

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

        runInBackground(null);
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
                        runInBackground(() -> sourcesManager.deleteList(source.getId())))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showImportChooser() {
        CharSequence[] items = {
                getString(R.string.sources_import_csv),
                getString(R.string.sources_import_bnetza)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sources_import)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        pickCsvFile();
                    } else {
                        showBnetzaImportInfo();
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

        importFile(uri, SourcesManager.csvSourceId(displayName), displayName,
                content -> new CsvNumberListParser().parse(content));
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
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sources_import_failed_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // presentation

    private String getSourceName(SourcesManager.SourceInfo source) {
        if (YacbDatabaseProvider.ID.equals(source.getId())) {
            return getString(R.string.source_yacb_name);
        }
        String name = source.getDisplayName();
        return !TextUtils.isEmpty(name) ? name : source.getId();
    }

    private String getSourceType(SourcesManager.SourceInfo source) {
        return getString(source.isOffline() ? R.string.source_type_offline
                : R.string.source_type_online)
                + SEPARATOR
                + getString(source.isImported() ? R.string.source_kind_imported
                : R.string.source_kind_built_in);
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
        } else if (YacbDatabaseProvider.ID.equals(source.getId())) {
            long lastUpdate = App.getSettings().getLastUpdateTime();
            parts.add(lastUpdate > 0
                    ? getString(R.string.source_updated, formatDate(lastUpdate))
                    : getString(R.string.source_never_updated));
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
            final AppCompatImageButton moveUp;
            final AppCompatImageButton moveDown;
            final AppCompatImageButton delete;
            final MaterialSwitch enabled;

            ViewHolder(View view) {
                super(view);
                name = view.findViewById(R.id.source_name);
                type = view.findViewById(R.id.source_type);
                details = view.findViewById(R.id.source_details);
                moveUp = view.findViewById(R.id.source_move_up);
                moveDown = view.findViewById(R.id.source_move_down);
                delete = view.findViewById(R.id.source_delete);
                enabled = view.findViewById(R.id.source_enabled);
            }

            void bind(SourcesManager.SourceInfo source, int position, int count) {
                name.setText(getSourceName(source));
                type.setText(getSourceType(source));

                String detailsText = getSourceDetails(source);
                details.setText(detailsText);
                details.setVisibility(TextUtils.isEmpty(detailsText) ? View.GONE : View.VISIBLE);

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
