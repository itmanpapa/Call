package dummydomain.yetanothercallblocker;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.text.TextUtils;

import androidx.core.app.NotificationManagerCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import dummydomain.yetanothercallblocker.data.RemoteListManager;
import dummydomain.yetanothercallblocker.data.SourcesManager;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.provider.YacbDatabaseProvider;
import dummydomain.yetanothercallblocker.data.setupcheck.SetupCheck;
import dummydomain.yetanothercallblocker.data.setupcheck.SetupCheckEnvironment;
import dummydomain.yetanothercallblocker.data.sources.BnetzaAutoUpdater;
import dummydomain.yetanothercallblocker.data.sources.NumberListStore;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockSync;
import dummydomain.yetanothercallblocker.data.sources.RemoteListInfo;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;

/**
 * {@link SetupCheckEnvironment} backed by the system services, the settings and the
 * number sources. Reads files: use on a background thread.
 */
class AndroidSetupCheckEnvironment implements SetupCheckEnvironment {

    /**
     * Notification channel groups of call notifications, see {@link NotificationHelper}.
     */
    private static final String[] CALL_CHANNEL_GROUPS = {"incoming_calls", "blocked_calls"};

    /**
     * Calls from contacts: turning this channel off is a reasonable choice, not a problem.
     */
    private static final String CHANNEL_ID_KNOWN = "known_calls";

    private static final Logger LOG = LoggerFactory.getLogger(AndroidSetupCheckEnvironment.class);

    private final Context context;
    private final Settings settings;

    AndroidSetupCheckEnvironment(Context context) {
        this.context = context.getApplicationContext();
        this.settings = App.getSettings();
    }

    /** Runs the check. Blocks: call from a background thread. */
    static SetupCheck.Result runCheck(Context context) {
        return SetupCheck.run(new AndroidSetupCheckEnvironment(context));
    }

    @Override
    public int getSdkInt() {
        return Build.VERSION.SDK_INT;
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    @Override
    public String getAvailableAppUpdate() {
        try {
            return AppUpdateManager.get(context).getKnownUpdate();
        } catch (Exception e) {
            LOG.warn("getAvailableAppUpdate()", e);
            return null;
        }
    }

    @Override
    public boolean isSmsWarningsEnabled() {
        return settings.getSmsWarnings();
    }

    @Override
    public boolean hasSmsPermission() {
        return PermissionHelper.hasSmsPermission(context);
    }

    @Override
    public boolean hasCallScreeningRole() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && PermissionHelper.isCallScreeningHeld(context);
    }

    @Override
    public boolean isDefaultDialer() {
        return PermissionHelper.isDefaultDialer(context);
    }

    @Override
    public boolean isMonitoringServiceEnabled() {
        return settings.getUseMonitoringService();
    }

    @Override
    public boolean hasPhonePermissions() {
        return PermissionHelper.hasNumberInfoPermissions(context);
    }

    @Override
    public boolean hasCallControlPermissions() {
        if (!PermissionHelper.hasPermission(context, Manifest.permission.CALL_PHONE)) {
            return false;
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.P
                || PermissionHelper.hasPermission(context, Manifest.permission.ANSWER_PHONE_CALLS);
    }

    @Override
    public boolean hasContactsPermission() {
        return PermissionHelper.hasContactsPermission(context);
    }

    @Override
    public boolean hasNotificationPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || PermissionHelper.hasPermission(context,
                Manifest.permission.POST_NOTIFICATIONS);
    }

    @Override
    public boolean areNotificationsEnabled() {
        return NotificationManagerCompat.from(context).areNotificationsEnabled();
    }

    @Override
    public int getBlockedCallChannelCount() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 0;

        // the channels are created lazily; without them there is nothing the user turned off
        NotificationHelper.initNotificationChannels(context);
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return 0;

        int count = 0;
        try {
            for (NotificationChannel channel : manager.getNotificationChannels()) {
                String group = channel.getGroup();
                if (!isCallGroup(group) || CHANNEL_ID_KNOWN.equals(channel.getId())) continue;

                if (channel.getImportance() == NotificationManager.IMPORTANCE_NONE
                        || isGroupBlocked(manager, group)) {
                    count++;
                }
            }
        } catch (Exception e) {
            LOG.warn("getBlockedCallChannelCount()", e);
        }
        return count;
    }

    private static boolean isCallGroup(String group) {
        for (String id : CALL_CHANNEL_GROUPS) {
            if (id.equals(group)) return true;
        }
        return false;
    }

    private static boolean isGroupBlocked(NotificationManager manager, String groupId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        NotificationChannelGroup group = manager.getNotificationChannelGroup(groupId);
        return group != null && group.isBlocked();
    }

    @Override
    public boolean isIncomingCallNotificationsEnabled() {
        return settings.getIncomingCallNotifications();
    }

    @Override
    public boolean isCallerIdOverlayEnabled() {
        return settings.getCallerIdOverlay();
    }

    @Override
    public boolean canDrawOverlays() {
        return CallerIdOverlay.canDrawOverlays(context);
    }

    @Override
    public boolean isBlockByRatingEnabled() {
        return settings.getBlockNegativeSiaNumbers();
    }

    @Override
    public boolean isBlockHiddenEnabled() {
        return settings.getBlockHiddenNumbers();
    }

    @Override
    public boolean isBlockBlacklistedEnabled() {
        return settings.getBlockBlacklisted();
    }

    @Override
    public boolean isBlacklistEmpty() {
        return !settings.getBlacklistIsNotEmpty();
    }

    @Override
    public boolean isUseContactsEnabled() {
        return settings.getUseContacts();
    }

    @Override
    public boolean isIgnoringBatteryOptimizations() {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    // sources, in the order of the "Databases" screen

    @Override
    public List<Source> getSources() {
        SourcesManager manager = YacbHolder.getSourcesManager();

        List<Source> sources = new ArrayList<>();
        sources.add(createYacb(manager));
        sources.add(createPhoneBlock(manager));
        sources.add(createBnetza(manager));

        for (SourcesManager.SourceInfo source : manager.getSources()) {
            if (isUserList(source)) sources.add(createUserList(source));
        }
        return sources;
    }

    /** Same as {@code SourceItem}: imported lists except the built-in ones. */
    private static boolean isUserList(SourcesManager.SourceInfo source) {
        return source.isImported()
                && !PhoneBlockSync.SOURCE_ID.equals(source.getId())
                && !SourcesManager.BNETZA_SOURCE_ID.equals(source.getId());
    }

    private Source createYacb(SourcesManager manager) {
        String id = YacbDatabaseProvider.ID;
        CommunityDatabase db = YacbHolder.getCommunityDatabase();
        boolean hasData = db != null && db.isOperational();
        // a successful check without changes counts as "up to date" as well
        long lastSuccess = Math.max(settings.getLastUpdateCheckTime(),
                settings.getLastUpdateTime());

        return new Source(id, context.getString(R.string.source_yacb_name),
                manager.isEnabled(id), true, hasData, true, SourceItem.isYacbUpdating(),
                lastSuccess, null);
    }

    private Source createPhoneBlock(SourcesManager manager) {
        String id = PhoneBlockSync.SOURCE_ID;
        SourcesManager.SourceInfo source = manager.getSource(id);

        PhoneBlockSync sync = YacbHolder.getPhoneBlockSync();
        long lastSync = sync != null ? sync.getStatus().getLastSync() : 0;
        boolean hasData = lastSync > 0 || (source != null && source.getMetadata() != null);

        String error = null;
        String lastError = settings.getPhoneBlockLastError();
        if (lastError != null && settings.getPhoneBlockLastErrorTime() >= lastSync) {
            error = lastError;
        } else if (source != null && source.isLoadFailed()) {
            error = context.getString(R.string.source_load_error);
        }

        boolean configured = !TextUtils.isEmpty(settings.getPhoneBlockToken());
        return new Source(id, context.getString(R.string.phoneblock_title),
                manager.isEnabled(id), configured, hasData, configured,
                SourceTasks.isRunning(id), lastSync, error);
    }

    private Source createBnetza(SourcesManager manager) {
        String id = SourcesManager.BNETZA_SOURCE_ID;
        SourcesManager.SourceInfo source = manager.getSource(id);
        NumberListStore.ListMetadata metadata = source != null ? source.getMetadata() : null;

        boolean available = BnetzaActions.isAvailable();
        boolean hasData = metadata != null;
        long lastSuccess = metadata != null ? metadata.getImportedAt() : 0;
        String error = SourceTasks.getLastError(id);

        BnetzaAutoUpdater updater = YacbHolder.getBnetzaAutoUpdater();
        if (updater != null) {
            try {
                BnetzaAutoUpdater.Status status = updater.getStatus();
                hasData = status.isListStored();
                lastSuccess = Math.max(lastSuccess, status.getLastSuccessAt());
                if (error == null) error = status.getLastError();
            } catch (Exception e) {
                LOG.warn("createBnetza() failed to read the status", e);
            }
        }
        if (error == null && source != null && source.isLoadFailed()) {
            error = context.getString(R.string.source_load_error);
        }

        return new Source(id, context.getString(R.string.source_bnetza_name),
                manager.isEnabled(id), available || hasData, hasData, available,
                SourceTasks.isRunning(id), lastSuccess, error);
    }

    private Source createUserList(SourcesManager.SourceInfo source) {
        RemoteListInfo remote = RemoteListManager.getRemote(source);
        NumberListStore.ListMetadata metadata = source.getMetadata();

        String name = !TextUtils.isEmpty(source.getDisplayName())
                ? source.getDisplayName() : source.getId();

        long lastSuccess = metadata != null ? metadata.getImportedAt() : 0;
        String error = null;
        if (source.isLoadFailed()) {
            error = context.getString(R.string.source_load_error);
        } else if (remote != null) {
            lastSuccess = Math.max(lastSuccess, remote.getLastSuccessAt());
            error = remote.getLastError();
        }

        // files and lists without automatic updates are expected to get old
        boolean updatable = remote != null && remote.isAutoUpdate();
        return new Source(source.getId(), name, source.isEnabled(), true, metadata != null,
                updatable, SourceTasks.isRunning(source.getId()), lastSuccess, error);
    }

}
