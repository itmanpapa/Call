package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputLayout;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dummydomain.yetanothercallblocker.data.RemoteListManager;
import dummydomain.yetanothercallblocker.data.SourcesManager;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.provider.PhoneBlockOnlineProvider;
import dummydomain.yetanothercallblocker.data.sources.NumberListFormatDetector;
import dummydomain.yetanothercallblocker.data.sources.ParseResult;
import dummydomain.yetanothercallblocker.data.sources.RemoteListDownloader;
import dummydomain.yetanothercallblocker.event.MainDbDownloadFinishedEvent;
import dummydomain.yetanothercallblocker.event.MainDbDownloadingEvent;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdateFinished;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdatingEvent;
import dummydomain.yetanothercallblocker.work.ListsUpdateScheduler;

/**
 * "Databases" screen: one card per number source (the YACB database, PhoneBlock,
 * the Bundesnetzagentur list, then the lists added by the user) with its status and
 * an enable switch. A tap opens {@link SourceDetailsActivity}; the only other action
 * is adding a list (from a file or a URL).
 */
public class SourcesActivity extends BaseActivity implements SourceTasks.Listener {

    private static final String[] FILE_MIME_TYPES = {
            "text/*", "text/csv", "text/comma-separated-values", "text/xml",
            "application/xml", "text/vcard", "text/x-vcard"};

    /** Larger files are certainly not number lists (and could exhaust the memory). */
    private static final int MAX_IMPORT_FILE_SIZE = 20 * 1024 * 1024;

    private static final int MAX_SKIPPED_EXAMPLES = 5;

    private static final Logger LOG = LoggerFactory.getLogger(SourcesActivity.class);

    private final SourcesManager sourcesManager = YacbHolder.getSourcesManager();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::onFilePicked);

    private SourcesAdapter adapter;

    public static Intent getIntent(Context context) {
        return new Intent(context, SourcesActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sources);

        findViewById(R.id.sources_add_fab).setOnClickListener(v -> showAddChooser());

        adapter = new SourcesAdapter();
        RecyclerView recyclerView = findViewById(R.id.sources_list);
        recyclerView.setAdapter(adapter);

        executor.execute(this::ensureListsUpdateScheduled);
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventUtils.register(this);
        SourceTasks.addListener(this);
        reload();
    }

    @Override
    protected void onStop() {
        SourceTasks.removeListener(this);
        EventUtils.unregister(this);
        super.onStop();
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

    // state updates

    @Override
    public void onSourceTaskChanged(String sourceId, SourceTasks.Outcome outcome) {
        if (outcome != null && outcome.getNotice() != null) {
            Toast.makeText(this, outcome.getNotice(), Toast.LENGTH_SHORT).show();
        }
        reload();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdating(SecondaryDbUpdatingEvent event) {
        reload();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdateFinished(SecondaryDbUpdateFinished event) {
        reload();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloading(MainDbDownloadingEvent event) {
        reload();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloadFinished(MainDbDownloadFinishedEvent event) {
        reload();
    }

    private interface BackgroundAction {
        void run() throws Exception;
    }

    /**
     * Runs the action (may be null) on the background thread, then reloads the list.
     */
    private void runInBackground(BackgroundAction action) {
        executor.execute(() -> {
            try {
                if (action != null) action.run();
            } catch (Exception e) {
                LOG.warn("runInBackground() action failed", e);
            }

            List<SourceItem> items;
            try {
                items = SourceItem.loadAll(this);
            } catch (Exception e) {
                LOG.error("runInBackground() failed to load sources", e);
                items = Collections.emptyList();
            }

            List<SourceItem> result = items;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                adapter.setItems(result);
            });
        });
    }

    private void reload() {
        if (!executor.isShutdown()) runInBackground(null);
    }

    // actions

    private void onEnabledChanged(SourceItem item, boolean enabled) {
        runInBackground(() -> {
            sourcesManager.setEnabled(item.id, enabled);
            // the online check is a part of PhoneBlock
            if (item.kind == SourceItem.Kind.PHONEBLOCK && !enabled) {
                sourcesManager.setEnabled(PhoneBlockOnlineProvider.ID, false);
            }
        });

        if (enabled && item.kind == SourceItem.Kind.PHONEBLOCK
                && TextUtils.isEmpty(App.getSettings().getPhoneBlockToken())) {
            // nothing to use without a key
            openDetails(item);
        }
    }

    private void openDetails(SourceItem item) {
        startActivity(SourceDetailsActivity.getIntent(this, item.id));
    }

    private void showAddChooser() {
        CharSequence[] items = {
                getString(R.string.sources_add_file),
                getString(R.string.sources_add_link)
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sources_add_list)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        pickFile();
                    } else {
                        showAddUrlDialog();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // import from a file

    private static final class ImportOutcome {
        ParseResult parseResult;
        String error;
    }

    private static final class FileTooLargeException extends IOException {
        FileTooLargeException() {
            super("File is too large");
        }
    }

    private void pickFile() {
        try {
            filePicker.launch(FILE_MIME_TYPES);
        } catch (Exception e) { // ActivityNotFoundException
            LOG.warn("pickFile()", e);
            showError(getString(R.string.sources_no_file_picker));
        }
    }

    private void onFilePicked(Uri uri) {
        if (uri == null) return;

        String name = SourcesManager.fileNameToDisplayName(getFileName(uri));
        String displayName = !TextUtils.isEmpty(name)
                ? name : getString(R.string.sources_csv_default_name);
        String sourceId = SourcesManager.csvSourceId(displayName);
        LOG.debug("onFilePicked() uri={}, sourceId={}", uri, sourceId);

        ImportOutcome outcome = new ImportOutcome();
        runInBackground(() -> {
            try {
                // CSV / plain text, Fritz!Box phone books and vCards
                ParseResult result = NumberListFormatDetector.parse(readText(uri));
                outcome.parseResult = result;

                if (!result.getEntries().isEmpty()) {
                    sourcesManager.importList(sourceId, displayName, result.getEntries(),
                            System.currentTimeMillis());
                }
            } catch (FileTooLargeException e) {
                outcome.error = getString(R.string.sources_import_file_too_large);
            } catch (Exception e) {
                LOG.warn("onFilePicked() import failed", e);
                outcome.error = describeImportError(e);
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

    // lists from URL

    /**
     * The periodic update survives reboots and app updates, but not e.g. a restore of
     * the app data from a backup; re-schedule it if a list needs it (a no-op otherwise).
     */
    private void ensureListsUpdateScheduled() {
        try {
            RemoteListManager manager = YacbHolder.getRemoteListManager();
            if (manager != null && manager.hasAutoUpdateLists()) {
                ListsUpdateScheduler.get(this).schedule();
            }
        } catch (Exception e) {
            LOG.warn("ensureListsUpdateScheduled()", e);
        }
    }

    /**
     * Shows the "add list by URL" dialog. Nothing is downloaded before the user confirms.
     */
    private void showAddUrlDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_add_url_list, null);
        TextInputLayout urlLayout = view.findViewById(R.id.add_url_url_layout);
        EditText urlEdit = view.findViewById(R.id.add_url_url);
        EditText nameEdit = view.findViewById(R.id.add_url_name);
        CheckBox autoUpdate = view.findViewById(R.id.add_url_auto_update);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sources_add_url)
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
                outcome.error = describeImportError(e);
            } finally {
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showImportResult(outcome);
                });
            }
        });
    }

    /**
     * @return a user-facing description of a failed file import or list download
     */
    static String describeImportError(Context context, Exception e) {
        if (e instanceof RemoteListDownloader.TooLargeException) {
            return context.getString(R.string.sources_import_file_too_large);
        }
        if (e instanceof NumberListFormatDetector.UnsupportedFormatException) {
            return context.getString(((NumberListFormatDetector.UnsupportedFormatException) e)
                    .getFormat() == NumberListFormatDetector.Format.HTML
                    ? R.string.sources_url_html_error : R.string.sources_url_unsupported_format);
        }
        String message = e.getMessage() != null ? e.getMessage() : e.toString();
        return context.getString(R.string.sources_url_download_failed, message);
    }

    private String describeImportError(Exception e) {
        return describeImportError(this, e);
    }

    // results

    private void showImportResult(ImportOutcome outcome) {
        if (outcome.error != null || outcome.parseResult == null) {
            showError(outcome.error != null ? outcome.error : "");
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

    // list

    /**
     * Shows the status line of a source in the error color or the secondary text color.
     */
    static void bindStatus(TextView view, SourceItem item) {
        view.setText(item.status);
        view.setTextColor(MaterialColors.getColor(view, item.statusError
                ? com.google.android.material.R.attr.colorError
                : com.google.android.material.R.attr.colorOnSurfaceVariant));
    }

    private class SourcesAdapter extends RecyclerView.Adapter<SourcesAdapter.ViewHolder> {

        private List<SourceItem> items = Collections.emptyList();

        @SuppressLint("NotifyDataSetChanged") // the list is tiny
        void setItems(List<SourceItem> items) {
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
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {

            final TextView name;
            final TextView description;
            final TextView status;
            final LinearProgressIndicator progress;
            final MaterialSwitch enabled;

            ViewHolder(View view) {
                super(view);
                name = view.findViewById(R.id.source_name);
                description = view.findViewById(R.id.source_description);
                status = view.findViewById(R.id.source_status);
                progress = view.findViewById(R.id.source_progress);
                enabled = view.findViewById(R.id.source_enabled);
            }

            void bind(SourceItem item) {
                itemView.setOnClickListener(v -> openDetails(item));

                name.setText(item.name);
                description.setText(item.description);
                bindStatus(status, item);
                progress.setVisibility(item.running ? View.VISIBLE : View.GONE);

                enabled.setOnCheckedChangeListener(null);
                enabled.setChecked(item.enabled);
                enabled.setOnCheckedChangeListener((buttonView, isChecked) ->
                        onEnabledChanged(item, isChecked));
            }
        }
    }

}
