package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.text.format.DateUtils;

import java.util.ArrayList;
import java.util.List;

import dummydomain.yetanothercallblocker.data.RemoteListManager;
import dummydomain.yetanothercallblocker.data.SourcesManager;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.provider.YacbDatabaseProvider;
import dummydomain.yetanothercallblocker.data.sources.NumberListStore;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockSync;
import dummydomain.yetanothercallblocker.data.sources.RemoteListInfo;
import dummydomain.yetanothercallblocker.event.MainDbDownloadingEvent;
import dummydomain.yetanothercallblocker.event.SecondaryDbUpdatingEvent;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;

/**
 * One entry of the "Databases" screen: a source with its name, description and
 * current status. Built on a background thread ({@link #loadAll(Context)} reads
 * files), immutable.
 *
 * <p>The built-in sources are always listed, in this order: the YACB database,
 * PhoneBlock, the Bundesnetzagentur list; then the lists added by the user (files and
 * URLs). The PhoneBlock online check is a setting of the PhoneBlock entry, not an
 * entry of its own.</p>
 */
final class SourceItem {

    enum Kind {YACB, PHONEBLOCK, BNETZA, FILE, URL}

    /** Long error messages are cut in the status line. */
    private static final int MAX_ERROR_LENGTH = 160;

    static final String SEPARATOR = " · ";

    final Kind kind;
    final String id;
    final String name;
    final String description;
    final String status;
    final boolean statusError;
    final boolean running;
    final boolean enabled;
    /** The stored list or provider; null if a built-in list was not downloaded yet. */
    final SourcesManager.SourceInfo source;

    private SourceItem(Kind kind, String id, String name, String description, String status,
                       boolean statusError, boolean running, boolean enabled,
                       SourcesManager.SourceInfo source) {
        this.kind = kind;
        this.id = id;
        this.name = name;
        this.description = description;
        this.status = status;
        this.statusError = statusError;
        this.running = running;
        this.enabled = enabled;
        this.source = source;
    }

    /** @return the download state of a list added by URL, null for other sources */
    RemoteListInfo getRemote() {
        return source != null ? RemoteListManager.getRemote(source) : null;
    }

    /**
     * @return all entries in display order
     */
    static List<SourceItem> loadAll(Context context) {
        SourcesManager manager = YacbHolder.getSourcesManager();

        List<SourceItem> items = new ArrayList<>();
        items.add(createYacb(context, manager));
        items.add(createPhoneBlock(context, manager));
        items.add(createBnetza(context, manager));

        for (SourcesManager.SourceInfo source : manager.getSources()) {
            if (isUserList(source)) items.add(createUserList(context, source));
        }
        return items;
    }

    /**
     * @return the entry with the given id, or null if there is no such source (any more)
     */
    static SourceItem load(Context context, String id) {
        SourcesManager manager = YacbHolder.getSourcesManager();
        if (YacbDatabaseProvider.ID.equals(id)) return createYacb(context, manager);
        if (PhoneBlockSync.SOURCE_ID.equals(id)) return createPhoneBlock(context, manager);
        if (SourcesManager.BNETZA_SOURCE_ID.equals(id)) return createBnetza(context, manager);

        SourcesManager.SourceInfo source = manager.getSource(id);
        return source != null && isUserList(source) ? createUserList(context, source) : null;
    }

    private static boolean isUserList(SourcesManager.SourceInfo source) {
        return source.isImported()
                && !PhoneBlockSync.SOURCE_ID.equals(source.getId())
                && !SourcesManager.BNETZA_SOURCE_ID.equals(source.getId());
    }

    // built-in sources

    static boolean isYacbUpdating() {
        return EventUtils.bus().getStickyEvent(MainDbDownloadingEvent.class) != null
                || EventUtils.bus().getStickyEvent(SecondaryDbUpdatingEvent.class) != null;
    }

    private static SourceItem createYacb(Context context, SourcesManager manager) {
        String id = YacbDatabaseProvider.ID;
        boolean running = isYacbUpdating();

        String status;
        boolean error = false;
        CommunityDatabase db = YacbHolder.getCommunityDatabase();
        if (running) {
            status = context.getString(R.string.source_status_updating);
        } else if (db == null || !db.isOperational()) {
            status = context.getString(R.string.yacb_status_not_downloaded);
            error = true;
        } else {
            String version = context.getString(R.string.yacb_status_version,
                    String.valueOf(db.getEffectiveDbVersion()));
            long lastUpdate = App.getSettings().getLastUpdateTime();
            status = lastUpdate > 0
                    ? context.getString(R.string.source_status_updated,
                    formatDate(context, lastUpdate), version)
                    : version;
        }

        return new SourceItem(Kind.YACB, id, context.getString(R.string.source_yacb_name),
                context.getString(R.string.source_yacb_description), status, error, running,
                manager.isEnabled(id), manager.getSource(id));
    }

    private static SourceItem createPhoneBlock(Context context, SourcesManager manager) {
        String id = PhoneBlockSync.SOURCE_ID;
        Settings settings = App.getSettings();
        SourcesManager.SourceInfo source = manager.getSource(id);
        boolean running = SourceTasks.isRunning(id);

        PhoneBlockSync sync = YacbHolder.getPhoneBlockSync();
        PhoneBlockSync.Status syncStatus = sync != null ? sync.getStatus() : null;
        long lastSync = syncStatus != null ? syncStatus.getLastSync() : 0;

        String status;
        boolean error = false;
        String lastError = settings.getPhoneBlockLastError();
        if (running) {
            status = context.getString(R.string.source_status_updating);
        } else if (TextUtils.isEmpty(settings.getPhoneBlockToken())) {
            status = context.getString(R.string.phoneblock_status_no_key);
        } else if (lastError != null && settings.getPhoneBlockLastErrorTime() >= lastSync) {
            status = formatError(context, lastError);
            error = true;
        } else if (source != null && source.isLoadFailed()) {
            status = context.getString(R.string.source_load_error);
            error = true;
        } else if (lastSync > 0) {
            NumberListStore.ListMetadata metadata = source != null ? source.getMetadata() : null;
            int count = metadata != null ? metadata.getEntryCount() : syncStatus.getTotalNumbers();
            status = context.getString(R.string.source_status_updated,
                    formatDate(context, lastSync), formatCount(context, count));
        } else {
            status = context.getString(R.string.source_status_never);
        }

        return new SourceItem(Kind.PHONEBLOCK, id, context.getString(R.string.phoneblock_title),
                context.getString(R.string.source_phoneblock_description), status, error,
                running, manager.isEnabled(id), source);
    }

    private static SourceItem createBnetza(Context context, SourcesManager manager) {
        String id = SourcesManager.BNETZA_SOURCE_ID;
        SourcesManager.SourceInfo source = manager.getSource(id);
        NumberListStore.ListMetadata metadata = source != null ? source.getMetadata() : null;
        boolean running = SourceTasks.isRunning(id);

        String lastError = SourceTasks.getLastError(id);
        if (lastError == null) lastError = BnetzaActions.getLastError();

        String status;
        boolean error = false;
        if (running) {
            status = context.getString(R.string.source_status_updating);
        } else if (lastError != null) {
            status = formatError(context, lastError);
            error = true;
        } else if (source != null && source.isLoadFailed()) {
            status = context.getString(R.string.source_load_error);
            error = true;
        } else if (metadata != null && metadata.getImportedAt() > 0) {
            status = formatUpdated(context, metadata);
        } else if (!BnetzaActions.isAvailable()) {
            status = context.getString(R.string.bnetza_status_not_available);
        } else {
            status = context.getString(R.string.source_status_never);
        }

        return new SourceItem(Kind.BNETZA, id, context.getString(R.string.source_bnetza_name),
                context.getString(R.string.source_bnetza_description), status, error, running,
                manager.isEnabled(id), source);
    }

    // lists added by the user

    private static SourceItem createUserList(Context context, SourcesManager.SourceInfo source) {
        RemoteListInfo remote = RemoteListManager.getRemote(source);
        NumberListStore.ListMetadata metadata = source.getMetadata();
        boolean running = SourceTasks.isRunning(source.getId());

        String name = !TextUtils.isEmpty(source.getDisplayName())
                ? source.getDisplayName() : source.getId();

        String description;
        if (remote != null) {
            String host = Uri.parse(remote.getUrl()).getHost();
            description = (!TextUtils.isEmpty(host) ? host + SEPARATOR : "")
                    + context.getString(remote.isAutoUpdate()
                    ? R.string.source_url_auto_update_on : R.string.source_url_auto_update_off);
        } else {
            description = context.getString(R.string.source_file_description);
        }

        String status;
        boolean error = false;
        if (running) {
            status = context.getString(R.string.source_status_updating);
        } else if (source.isLoadFailed()) {
            status = context.getString(R.string.source_load_error);
            error = true;
        } else if (remote != null && remote.getLastError() != null
                && (metadata == null || remote.getLastCheckAt() >= metadata.getImportedAt())) {
            status = formatError(context, remote.getLastError());
            error = true;
        } else if (metadata != null) {
            status = formatUpdated(context, metadata);
        } else {
            status = context.getString(R.string.source_status_never);
        }

        return new SourceItem(remote != null ? Kind.URL : Kind.FILE, source.getId(), name,
                description, status, error, running, source.isEnabled(), source);
    }

    // formatting

    private static String formatUpdated(Context context, NumberListStore.ListMetadata metadata) {
        return context.getString(R.string.source_status_updated,
                formatDate(context, metadata.getImportedAt()),
                formatCount(context, metadata.getEntryCount()));
    }

    static String formatCount(Context context, int count) {
        return context.getResources().getQuantityString(R.plurals.source_entry_count,
                count, count);
    }

    static String formatError(Context context, String error) {
        if (error.length() > MAX_ERROR_LENGTH) error = error.substring(0, MAX_ERROR_LENGTH) + "…";
        return context.getString(R.string.source_status_error, error);
    }

    static String formatDate(Context context, long timestamp) {
        return DateUtils.formatDateTime(context, timestamp, DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_SHOW_YEAR | DateUtils.FORMAT_SHOW_TIME);
    }

}
