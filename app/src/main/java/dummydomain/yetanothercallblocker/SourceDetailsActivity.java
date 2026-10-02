package dummydomain.yetanothercallblocker;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.data.RemoteListManager;
import dummydomain.yetanothercallblocker.data.SourcesManager;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.provider.PhoneBlockOnlineProvider;
import dummydomain.yetanothercallblocker.data.provider.YacbDatabaseProvider;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockClient;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockSync;
import dummydomain.yetanothercallblocker.data.sources.RemoteListInfo;
import dummydomain.yetanothercallblocker.event.MainDbDownloadFinishedEvent;
import dummydomain.yetanothercallblocker.event.MainDbDownloadingEvent;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdateFinished;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdatingEvent;
import dummydomain.yetanothercallblocker.sia.model.SiaMetadata;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabase;
import dummydomain.yetanothercallblocker.work.ListsUpdateScheduler;
import dummydomain.yetanothercallblocker.work.PhoneBlockSyncWorker;
import dummydomain.yetanothercallblocker.work.TaskService;

/**
 * Details of one source of the "Databases" screen: the status, "update now" and the
 * settings of the source (YACB: database info and reset; PhoneBlock: API key,
 * threshold, online check; lists added by the user: automatic updates, deletion).
 */
public class SourceDetailsActivity extends BaseActivity implements SourceTasks.Listener {

    private static final String EXTRA_SOURCE_ID = "sourceId";

    /** Toggle buttons for {@link PhoneBlockSync#MIN_VOTES_OPTIONS}, in the same order. */
    private static final int[] MIN_VOTES_BUTTON_IDS = {
            R.id.phoneblock_votes_10, R.id.phoneblock_votes_20,
            R.id.phoneblock_votes_50, R.id.phoneblock_votes_100};

    private static final Logger LOG = LoggerFactory.getLogger(SourceDetailsActivity.class);

    /** Data of the screen, loaded in the background. */
    private static final class Details {
        SourceItem item;
        boolean yacbOperational;
        String yacbInfo;
        long phoneBlockLastSync;
        boolean phoneBlockOnlineEnabled;
        PhoneBlockReportSection.Counts phoneBlockReports;
    }

    private final SourcesManager sourcesManager = YacbHolder.getSourcesManager();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private String sourceId;
    private Details details;
    /** True while the views are updated from {@link #details} (listeners ignore changes). */
    private boolean binding;
    private boolean tokenShown;
    private boolean verifyingKey;
    private PhoneBlockReportSection reportSection;

    private TextView descriptionView;
    private TextView statusView;
    private View progressView;
    private TextView extraView;
    private MaterialButton updateButton;

    public static Intent getIntent(Context context, String sourceId) {
        return new Intent(context, SourceDetailsActivity.class)
                .putExtra(EXTRA_SOURCE_ID, sourceId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_source_details);

        sourceId = getIntent().getStringExtra(EXTRA_SOURCE_ID);
        if (sourceId == null) {
            finish();
            return;
        }

        descriptionView = findViewById(R.id.details_description);
        statusView = findViewById(R.id.details_status);
        progressView = findViewById(R.id.details_progress);
        extraView = findViewById(R.id.details_extra);
        updateButton = findViewById(R.id.details_update);
        updateButton.setOnClickListener(v -> onUpdateClicked());

        if (YacbDatabaseProvider.ID.equals(sourceId)) {
            initYacb();
        } else if (PhoneBlockSync.SOURCE_ID.equals(sourceId)) {
            initPhoneBlock();
        } else if (SourcesManager.BNETZA_SOURCE_ID.equals(sourceId)) {
            findViewById(R.id.bnetza_section).setVisibility(View.VISIBLE);
            findViewById(R.id.bnetza_open_page).setOnClickListener(v ->
                    openUrl(BnetzaActions.SOURCE_PAGE_URL));
        } else {
            initUserList();
        }
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
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        // the list screen is still in the back stack
        finish();
        return true;
    }

    // state updates

    @Override
    public void onSourceTaskChanged(String id, SourceTasks.Outcome outcome) {
        if (!sourceId.equals(id)) return;
        if (outcome != null && outcome.getNotice() != null) {
            Toast.makeText(this, outcome.getNotice(), Toast.LENGTH_LONG).show();
        }
        reload();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdating(SecondaryDbUpdatingEvent event) {
        if (YacbDatabaseProvider.ID.equals(sourceId)) reload();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onSecondaryDbUpdateFinished(SecondaryDbUpdateFinished event) {
        if (YacbDatabaseProvider.ID.equals(sourceId)) reload();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloading(MainDbDownloadingEvent event) {
        if (YacbDatabaseProvider.ID.equals(sourceId)) reload();
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onMainDbDownloadFinished(MainDbDownloadFinishedEvent event) {
        if (YacbDatabaseProvider.ID.equals(sourceId)) reload();
    }

    private interface BackgroundAction {
        void run() throws Exception;
    }

    /**
     * Runs the action (may be null) on the background thread, then reloads the screen.
     */
    private void runInBackground(BackgroundAction action) {
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            try {
                if (action != null) action.run();
            } catch (Exception e) {
                LOG.warn("runInBackground() action failed", e);
            }

            Details loaded = new Details();
            try {
                loadDetails(loaded);
            } catch (Exception e) {
                LOG.error("runInBackground() failed to load {}", sourceId, e);
                return;
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                bind(loaded);
            });
        });
    }

    private void reload() {
        runInBackground(null);
    }

    private void loadDetails(Details d) {
        d.item = SourceItem.load(this, sourceId);
        if (d.item == null) return;

        switch (d.item.kind) {
            case YACB:
                CommunityDatabase db = YacbHolder.getCommunityDatabase();
                d.yacbOperational = db != null && db.isOperational();
                d.yacbInfo = buildYacbInfo();
                break;

            case PHONEBLOCK:
                PhoneBlockSync sync = YacbHolder.getPhoneBlockSync();
                d.phoneBlockLastSync = sync != null ? sync.getStatus().getLastSync() : 0;
                d.phoneBlockOnlineEnabled = sourcesManager.isEnabled(PhoneBlockOnlineProvider.ID);
                d.phoneBlockReports = PhoneBlockReportSection.load();
                break;

            default:
                break;
        }
    }

    private void bind(Details d) {
        if (d.item == null) {
            // deleted
            finish();
            return;
        }
        details = d;
        SourceItem item = d.item;

        binding = true;
        try {
            setTitle(item.name);
            descriptionView.setText(item.description);
            SourcesActivity.bindStatus(statusView, item);
            progressView.setVisibility(item.running ? View.VISIBLE : View.GONE);

            String extra = null;
            boolean canUpdate = !item.running;
            switch (item.kind) {
                case YACB:
                    bindYacb(d);
                    updateButton.setText(d.yacbOperational
                            ? R.string.source_update_now : R.string.yacb_download);
                    break;

                case PHONEBLOCK:
                    bindPhoneBlock(d);
                    canUpdate &= !TextUtils.isEmpty(App.getSettings().getPhoneBlockToken());
                    if (item.statusError && d.phoneBlockLastSync > 0) {
                        extra = getString(R.string.source_last_success,
                                SourceItem.formatDate(this, d.phoneBlockLastSync));
                    }
                    break;

                case BNETZA:
                    canUpdate &= BnetzaActions.isAvailable();
                    break;

                case URL:
                    extra = bindUserList(item);
                    break;

                case FILE:
                    bindUserList(item);
                    // a file can't be read again, it can only be imported again
                    updateButton.setVisibility(View.GONE);
                    break;
            }
            updateButton.setEnabled(canUpdate);

            extraView.setText(extra);
            extraView.setVisibility(extra != null ? View.VISIBLE : View.GONE);
        } finally {
            binding = false;
        }
    }

    // update

    private void onUpdateClicked() {
        if (details == null || details.item.kind == SourceItem.Kind.FILE) return;

        startUpdate(this, sourceId, details.yacbOperational);
        if (details.item.kind == SourceItem.Kind.YACB) updateButton.setEnabled(false);
    }

    /**
     * Starts the update of a source like the "Update now" button (also used by the
     * setup check). Lists imported from a file can't be updated.
     *
     * @param yacbOperational for the YACB database: true to download updates,
     *                        false to download the whole database
     */
    static void startUpdate(Context context, String sourceId, boolean yacbOperational) {
        Context appContext = context.getApplicationContext();

        if (YacbDatabaseProvider.ID.equals(sourceId)) {
            TaskService.start(context, yacbOperational
                    ? TaskService.TASK_UPDATE_SECONDARY_DB : TaskService.TASK_DOWNLOAD_MAIN_DB);
        } else if (PhoneBlockSync.SOURCE_ID.equals(sourceId)) {
            startPhoneBlockSync(appContext);
        } else if (SourcesManager.BNETZA_SOURCE_ID.equals(sourceId)) {
            SourceTasks.start(sourceId, () -> {
                BnetzaActions.Result result = BnetzaActions.update(true);
                if (!result.isSuccess()) throw new IOException(result.getError());
                return null;
            });
        } else {
            // a list without a URL is left alone by the manager
            SourceTasks.start(sourceId, () -> updateUrlList(appContext, sourceId));
        }
    }

    // YACB database

    private void initYacb() {
        findViewById(R.id.yacb_section).setVisibility(View.VISIBLE);

        findViewById(R.id.yacb_reset_updates).setOnClickListener(v -> confirm(
                R.string.yacb_reset_updates, R.string.yacb_reset_updates_message,
                () -> runInBackground(() ->
                        YacbHolder.getCommunityDatabase().resetSecondaryDatabase())));

        findViewById(R.id.yacb_reset_base).setOnClickListener(v -> confirm(
                R.string.yacb_reset_base, R.string.yacb_reset_base_message,
                () -> runInBackground(() -> {
                    YacbHolder.getCommunityDatabase().resetSecondaryDatabase();
                    YacbHolder.getDbManager().removeMainDb();
                    YacbHolder.getCommunityDatabase().reload();
                    YacbHolder.getFeaturedDatabase().reload();
                    YacbHolder.getSiaMetadata().reload();
                })));
    }

    private void bindYacb(Details d) {
        ((TextView) findViewById(R.id.yacb_info)).setText(d.yacbInfo);
        boolean enabled = !d.item.running && d.yacbOperational;
        findViewById(R.id.yacb_reset_updates).setEnabled(enabled);
        findViewById(R.id.yacb_reset_base).setEnabled(enabled);
    }

    /** Runs in the background. */
    private String buildYacbInfo() {
        CommunityDatabase communityDatabase = YacbHolder.getCommunityDatabase();
        FeaturedDatabase featuredDatabase = YacbHolder.getFeaturedDatabase();
        SiaMetadata siaMetadata = YacbHolder.getSiaMetadata();
        Settings settings = App.getSettings();

        String notAvailable = getString(R.string.db_version_not_available);
        boolean operational = communityDatabase.isOperational();

        return getString(R.string.yacb_info,
                operational ? String.valueOf(communityDatabase.getEffectiveDbVersion())
                        : notAvailable,
                operational ? String.valueOf(communityDatabase.getBaseDbVersion())
                        : notAvailable,
                operational ? String.valueOf(siaMetadata.getSiaAppVersion()) : notAvailable,
                dateOrNever(settings.getLastUpdateTime()),
                dateOrNever(settings.getLastUpdateCheckTime()),
                featuredDatabase.isOperational()
                        ? String.valueOf(featuredDatabase.getBaseDbVersion()) : notAvailable);
    }

    private String dateOrNever(long time) {
        return time > 0 ? SourceItem.formatDate(this, time)
                : getString(R.string.db_last_update_check_never);
    }

    // PhoneBlock

    private void initPhoneBlock() {
        findViewById(R.id.phoneblock_section).setVisibility(View.VISIBLE);
        reportSection = new PhoneBlockReportSection(this, findViewById(R.id.phoneblock_section));

        findViewById(R.id.phoneblock_get_token).setOnClickListener(v ->
                openUrl(PhoneBlockClient.TOKEN_PAGE_URL));
        findViewById(R.id.phoneblock_save).setOnClickListener(v -> onSaveKeyClicked());

        MaterialButtonToggleGroup group = findViewById(R.id.phoneblock_min_votes_group);
        for (int i = 0; i < MIN_VOTES_BUTTON_IDS.length; i++) {
            MaterialButton button = findViewById(MIN_VOTES_BUTTON_IDS[i]);
            if (i < PhoneBlockSync.MIN_VOTES_OPTIONS.length) {
                button.setText(String.valueOf(PhoneBlockSync.MIN_VOTES_OPTIONS[i]));
            } else {
                button.setVisibility(View.GONE);
            }
        }
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (binding || !isChecked) return;
            for (int i = 0; i < MIN_VOTES_BUTTON_IDS.length; i++) {
                if (MIN_VOTES_BUTTON_IDS[i] == checkedId
                        && i < PhoneBlockSync.MIN_VOTES_OPTIONS.length) {
                    onMinVotesChanged(PhoneBlockSync.MIN_VOTES_OPTIONS[i]);
                }
            }
        });

        MaterialSwitch online = findViewById(R.id.phoneblock_online);
        online.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) return;
            runInBackground(() -> sourcesManager.setEnabled(PhoneBlockOnlineProvider.ID,
                    isChecked));
        });
    }

    private void bindPhoneBlock(Details d) {
        Settings settings = App.getSettings();
        boolean hasToken = !TextUtils.isEmpty(settings.getPhoneBlockToken());

        if (!tokenShown) {
            // only once: don't overwrite what the user is typing
            tokenShown = true;
            TextInputEditText tokenInput = findViewById(R.id.phoneblock_token);
            tokenInput.setText(settings.getPhoneBlockToken());
        }

        findViewById(R.id.phoneblock_save).setEnabled(!verifyingKey);

        int minVotes = settings.getPhoneBlockMinVotes();
        for (int i = 0; i < MIN_VOTES_BUTTON_IDS.length
                && i < PhoneBlockSync.MIN_VOTES_OPTIONS.length; i++) {
            if (PhoneBlockSync.MIN_VOTES_OPTIONS[i] == minVotes) {
                ((MaterialButtonToggleGroup) findViewById(R.id.phoneblock_min_votes_group))
                        .check(MIN_VOTES_BUTTON_IDS[i]);
            }
        }

        MaterialSwitch online = findViewById(R.id.phoneblock_online);
        online.setChecked(d.phoneBlockOnlineEnabled);
        // the online check is a part of PhoneBlock and needs the key
        online.setEnabled(hasToken && d.item.enabled);

        if (reportSection != null) reportSection.bind(d.phoneBlockReports, hasToken);
    }

    private void onSaveKeyClicked() {
        TextInputLayout layout = findViewById(R.id.phoneblock_token_layout);
        TextInputEditText input = findViewById(R.id.phoneblock_token);
        CharSequence text = input.getText();
        String token = text != null ? text.toString().trim() : "";

        Settings settings = App.getSettings();
        Context appContext = getApplicationContext();

        if (token.isEmpty()) {
            settings.setPhoneBlockToken("");
            settings.setPhoneBlockLastError(null, 0);
            layout.setError(null);
            layout.setHelperText(getString(R.string.phoneblock_key_removed));
            runInBackground(() -> {
                // without a key the online check is useless
                sourcesManager.setEnabled(PhoneBlockOnlineProvider.ID, false);
                PhoneBlockSyncWorker.updateSchedule(appContext, false); // cancels
            });
            return;
        }

        layout.setError(null);
        layout.setHelperText(getString(R.string.phoneblock_key_checking));
        verifyingKey = true;
        findViewById(R.id.phoneblock_save).setEnabled(false);

        executor.execute(() -> {
            String error = null;
            try {
                PhoneBlockHelper.verifyKey(token);

                // valid: store it, enable the list and download it right away
                settings.setPhoneBlockToken(token);
                settings.setPhoneBlockLastError(null, 0);
                sourcesManager.setEnabled(PhoneBlockSync.SOURCE_ID, true);
                try {
                    PhoneBlockSyncWorker.updateSchedule(appContext, true);
                } catch (Exception e) {
                    LOG.warn("onSaveKeyClicked() failed to schedule the sync", e);
                }
                startPhoneBlockSync(appContext);
                // reports that failed with the old key
                PhoneBlockReports.scheduleIfPending(appContext);
            } catch (Exception e) {
                LOG.warn("onSaveKeyClicked() key check failed", e);
                error = PhoneBlockHelper.describeError(appContext, e);
            }

            String result = error;
            runOnUiThread(() -> {
                verifyingKey = false;
                if (isFinishing() || isDestroyed()) return;

                findViewById(R.id.phoneblock_save).setEnabled(true);
                if (result == null) {
                    layout.setHelperText(getString(R.string.phoneblock_key_accepted));
                } else {
                    layout.setHelperText(null);
                    layout.setError(result);
                }
                reload();
            });
        });
    }

    private void onMinVotesChanged(int minVotes) {
        Settings settings = App.getSettings();
        if (minVotes == settings.getPhoneBlockMinVotes()) return;

        settings.setPhoneBlockMinVotes(minVotes);
        PhoneBlockSync sync = YacbHolder.getPhoneBlockSync();
        // no network: the local copy has all vote counts
        if (sync != null) runInBackground(() -> sync.rebuildList(minVotes));
    }

    /** Can be called from any thread. */
    private static void startPhoneBlockSync(Context appContext) {
        SourceTasks.start(PhoneBlockSync.SOURCE_ID, () -> {
            PhoneBlockSync.SyncResult result = PhoneBlockHelper.sync(appContext, true);
            return result.isSkipped() ? appContext.getString(R.string.phoneblock_sync_skipped,
                    TimeUnit.MILLISECONDS.toMinutes(PhoneBlockSync.MIN_MANUAL_SYNC_INTERVAL_MILLIS))
                    : null;
        });
    }

    // lists added by the user

    private void initUserList() {
        findViewById(R.id.list_section).setVisibility(View.VISIBLE);

        MaterialSwitch autoUpdate = findViewById(R.id.list_auto_update);
        autoUpdate.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding) return;
            RemoteListManager manager = YacbHolder.getRemoteListManager();
            String id = sourceId;
            runInBackground(() -> {
                manager.setAutoUpdate(id, isChecked);
                ListsUpdateScheduler.get(getApplicationContext())
                        .update(manager.hasAutoUpdateLists());
            });
        });

        findViewById(R.id.list_delete).setOnClickListener(v -> {
            String name = details != null ? details.item.name : sourceId;
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.source_delete)
                    .setMessage(getString(R.string.source_delete_confirmation, name))
                    .setPositiveButton(R.string.source_delete_button, (dialog, which) -> {
                        String id = sourceId;
                        // the reload finds no source and closes the screen
                        runInBackground(() -> sourcesManager.deleteList(id));
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    /**
     * @return the "last check" line of a URL list, or null
     */
    private String bindUserList(SourceItem item) {
        RemoteListInfo remote = item.getRemote();

        TextView url = findViewById(R.id.list_url);
        MaterialSwitch autoUpdate = findViewById(R.id.list_auto_update);
        if (remote == null) {
            url.setVisibility(View.GONE);
            autoUpdate.setVisibility(View.GONE);
            return null;
        }

        url.setText(remote.getUrl());
        autoUpdate.setChecked(remote.isAutoUpdate());
        return remote.getLastCheckAt() > 0
                ? getString(R.string.source_url_checked,
                SourceItem.formatDate(this, remote.getLastCheckAt()))
                : null;
    }

    /** Runs in the background. */
    private static String updateUrlList(Context appContext, String id) throws IOException {
        RemoteListManager.UpdateResult result = YacbHolder.getRemoteListManager().update(id);
        if (result == null) return null; // deleted meanwhile

        switch (result.getStatus()) {
            case UPDATED:
                return appContext.getString(R.string.source_url_updated,
                        result.getParseResult().getEntries().size());
            case NOT_MODIFIED:
                return appContext.getString(R.string.source_url_not_modified);
            default:
                // also stored with the list
                throw new IOException(result.getError());
        }
    }

    // helpers

    private interface Action {
        void run();
    }

    private void confirm(int titleId, int messageId, Action action) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(titleId)
                .setMessage(messageId)
                .setPositiveButton(titleId, (dialog, which) -> action.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            LOG.warn("openUrl() no browser", e);
            Toast.makeText(this, getString(R.string.no_browser, url), Toast.LENGTH_LONG).show();
        }
    }

}
