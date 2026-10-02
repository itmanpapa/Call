package dummydomain.yetanothercallblocker.data.setupcheck;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.data.setupcheck.CheckItem.Action;
import dummydomain.yetanothercallblocker.data.setupcheck.CheckItem.Reason;
import dummydomain.yetanothercallblocker.data.setupcheck.CheckItem.Status;
import dummydomain.yetanothercallblocker.data.setupcheck.CheckItem.Type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SetupCheckTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    /** A device where everything is set up; tests break one thing at a time. */
    static class FakeEnvironment implements SetupCheckEnvironment {
        int sdkInt = 34;
        boolean role = true;
        boolean defaultDialer;
        boolean monitoringService;
        boolean phonePermissions = true;
        boolean callControlPermissions = true;
        boolean contactsPermission = true;
        boolean notificationPermission = true;
        boolean notificationsEnabled = true;
        int blockedChannels;
        boolean incomingCallNotifications = true;
        // the card is on by default in the app; off here so the other tests see no item
        boolean callerIdOverlay;
        boolean overlayPermission = true;
        boolean blockByRating = true;
        boolean blockHidden;
        boolean blockBlacklisted = true;
        boolean blacklistEmpty = true;
        boolean useContacts;
        boolean ignoringBatteryOptimizations;
        boolean smsWarnings;
        boolean smsPermission;
        final List<Source> sources = new ArrayList<>();

        FakeEnvironment() {
            sources.add(source("yacb", true, NOW - DAY, null));
        }

        @Override public int getSdkInt() { return sdkInt; }
        @Override public long now() { return NOW; }
        @Override public boolean hasCallScreeningRole() { return role; }
        @Override public boolean isDefaultDialer() { return defaultDialer; }
        @Override public boolean isMonitoringServiceEnabled() { return monitoringService; }
        @Override public boolean hasPhonePermissions() { return phonePermissions; }
        @Override public boolean hasCallControlPermissions() { return callControlPermissions; }
        @Override public boolean hasContactsPermission() { return contactsPermission; }
        @Override public boolean hasNotificationPermission() { return notificationPermission; }
        @Override public boolean areNotificationsEnabled() { return notificationsEnabled; }
        @Override public int getBlockedCallChannelCount() { return blockedChannels; }
        @Override public boolean isIncomingCallNotificationsEnabled() { return incomingCallNotifications; }
        @Override public boolean isCallerIdOverlayEnabled() { return callerIdOverlay; }
        @Override public boolean canDrawOverlays() { return overlayPermission; }
        @Override public boolean isBlockByRatingEnabled() { return blockByRating; }
        @Override public boolean isBlockHiddenEnabled() { return blockHidden; }
        @Override public boolean isBlockBlacklistedEnabled() { return blockBlacklisted; }
        @Override public boolean isBlacklistEmpty() { return blacklistEmpty; }
        @Override public boolean isUseContactsEnabled() { return useContacts; }
        @Override public boolean isIgnoringBatteryOptimizations() { return ignoringBatteryOptimizations; }
        @Override public List<Source> getSources() { return sources; }
        @Override public boolean isSmsWarningsEnabled() { return smsWarnings; }
        @Override public boolean hasSmsPermission() { return smsPermission; }
    }

    static SetupCheckEnvironment.Source source(String id, boolean hasData, long lastSuccessAt,
                                               String lastError) {
        return new SetupCheckEnvironment.Source(id, id, true, true, hasData, true, false,
                lastSuccessAt, lastError);
    }

    private FakeEnvironment env;

    @Before
    public void setUp() {
        env = new FakeEnvironment();
    }

    private static CheckItem find(SetupCheck.Result result, Type type) {
        for (CheckItem item : result.getItems()) {
            if (item.getType() == type) return item;
        }
        return null;
    }

    private CheckItem item(Type type) {
        return find(SetupCheck.run(env), type);
    }

    // aggregation

    @Test
    public void everythingSetUpWorks() {
        SetupCheck.Result result = SetupCheck.run(env);

        assertTrue(result.toString(), result.isProtectionWorking());
        assertEquals(0, result.getProblemCount());
        assertEquals(Status.OK, result.getOverallStatus());
        assertEquals("", result.getErrorSignature());
        for (CheckItem item : result.getItems()) {
            assertEquals(item.toString(), Status.OK, item.getStatus());
        }
    }

    @Test
    public void warningsDoNotBreakProtection() {
        env.notificationsEnabled = false;

        SetupCheck.Result result = SetupCheck.run(env);

        assertTrue(result.isProtectionWorking());
        assertEquals(Status.WARNING, result.getOverallStatus());
        assertEquals(0, result.getErrorCount());
        assertEquals(1, result.getWarningCount());
        assertEquals(1, result.getProblemCount());
    }

    @Test
    public void errorsAndWarningsAreCounted() {
        env.role = false;                 // error: blocking is on
        env.notificationsEnabled = false; // warning
        env.sources.add(source("phoneblock", false, 0, null)); // error: never downloaded

        SetupCheck.Result result = SetupCheck.run(env);

        assertFalse(result.isProtectionWorking());
        assertEquals(Status.ERROR, result.getOverallStatus());
        assertEquals(2, result.getErrorCount());
        assertEquals(1, result.getWarningCount());
        assertEquals(3, result.getProblemCount());
        assertEquals(2, result.getItems(Status.ERROR).size());
    }

    @Test
    public void infoIsNotAProblem() {
        env.useContacts = true;
        env.incomingCallNotifications = false;

        SetupCheck.Result result = SetupCheck.run(env);

        assertEquals(0, result.getProblemCount());
        assertEquals(Status.OK, result.getOverallStatus());
        assertEquals(Status.INFO, find(result, Type.CONTACTS).getStatus());
        assertEquals(Status.INFO, find(result, Type.NOTIFICATIONS).getStatus());
    }

    @Test
    public void errorSignatureChangesWithTheErrors() {
        env.role = false;
        String first = SetupCheck.run(env).getErrorSignature();
        assertEquals("CALL_SCREENING:SCREENING_ROLE_MISSING", first);

        // a new warning doesn't change it
        env.notificationsEnabled = false;
        assertEquals(first, SetupCheck.run(env).getErrorSignature());

        // a new error does
        env.phonePermissions = false;
        assertNotEquals(first, SetupCheck.run(env).getErrorSignature());
    }

    @Test
    public void itemsKeepTheirOrder() {
        env.useContacts = true;
        env.monitoringService = true;

        List<CheckItem> items = SetupCheck.run(env).getItems();

        assertEquals(Type.CALL_SCREENING, items.get(0).getType());
        assertEquals(Type.PERMISSIONS, items.get(1).getType());
        assertEquals(Type.BLOCKING, items.get(2).getType());
        assertEquals(Type.NOTIFICATIONS, items.get(3).getType());
        assertEquals(Type.BATTERY, items.get(4).getType());
        assertEquals(Type.CONTACTS, items.get(5).getType());
        assertEquals(Type.SOURCE, items.get(6).getType());
    }

    // caller ID card

    @Test
    public void overlayWithPermissionIsOk() {
        env.callerIdOverlay = true;
        CheckItem item = item(Type.OVERLAY);
        assertNotNull(item);
        assertEquals(Status.OK, item.getStatus());
        assertEquals(Reason.OVERLAY_OK, item.getReason());
        assertTrue(SetupCheck.run(env).isProtectionWorking());
    }

    @Test
    public void overlayWithoutPermissionIsAWarning() {
        env.callerIdOverlay = true;
        env.overlayPermission = false;
        CheckItem item = item(Type.OVERLAY);
        assertEquals(Status.WARNING, item.getStatus());
        assertEquals(Reason.OVERLAY_PERMISSION_MISSING, item.getReason());
        assertEquals(Action.REQUEST_OVERLAY_PERMISSION, item.getAction());

        SetupCheck.Result result = SetupCheck.run(env);
        assertTrue(result.isProtectionWorking());
        assertEquals(1, result.getWarningCount());
    }

    @Test
    public void overlayItemFollowsNotifications() {
        env.callerIdOverlay = true;
        java.util.List<CheckItem> items = SetupCheck.run(env).getItems();
        assertEquals(Type.NOTIFICATIONS, items.get(3).getType());
        assertEquals(Type.OVERLAY, items.get(4).getType());
    }

    @Test
    public void noOverlayItemWhenTheCardIsOff() {
        env.overlayPermission = false;
        assertNull(item(Type.OVERLAY));

        env.callerIdOverlay = true;
        env.incomingCallNotifications = false;
        assertNull(item(Type.OVERLAY));
    }

    // call screening

    @Test
    public void missingRoleIsAnErrorWhenBlocking() {
        env.role = false;
        CheckItem item = item(Type.CALL_SCREENING);
        assertEquals(Status.ERROR, item.getStatus());
        assertEquals(Reason.SCREENING_ROLE_MISSING, item.getReason());
        assertEquals(Action.REQUEST_CALL_SCREENING, item.getAction());
    }

    @Test
    public void missingRoleIsAWarningWithoutBlocking() {
        env.role = false;
        env.blockByRating = false;
        env.blockBlacklisted = false;
        assertEquals(Status.WARNING, item(Type.CALL_SCREENING).getStatus());
    }

    @Test
    public void oldAndroidUsesDefaultDialerOrMonitoringService() {
        env.sdkInt = 28;
        env.role = false;

        assertEquals(Reason.SCREENING_LEGACY, item(Type.CALL_SCREENING).getReason());
        assertEquals(Status.WARNING, item(Type.CALL_SCREENING).getStatus());

        env.monitoringService = true;
        assertEquals(Reason.SCREENING_MONITORING_SERVICE, item(Type.CALL_SCREENING).getReason());
        assertEquals(Status.OK, item(Type.CALL_SCREENING).getStatus());

        env.defaultDialer = true;
        assertEquals(Reason.SCREENING_DEFAULT_DIALER, item(Type.CALL_SCREENING).getReason());
        assertEquals(Status.OK, item(Type.CALL_SCREENING).getStatus());
    }

    // permissions

    @Test
    public void missingPhonePermissionsAreAnError() {
        env.phonePermissions = false;
        CheckItem item = item(Type.PERMISSIONS);
        assertEquals(Status.ERROR, item.getStatus());
        assertEquals(Reason.PERMISSIONS_PHONE_MISSING, item.getReason());
        assertEquals(Action.REQUEST_PERMISSIONS, item.getAction());
    }

    @Test
    public void callControlPermissionsMatterOnlyWithoutScreening() {
        env.callControlPermissions = false;
        assertEquals(Status.OK, item(Type.PERMISSIONS).getStatus());

        env.role = false;
        assertEquals(Reason.PERMISSIONS_CALL_CONTROL_MISSING, item(Type.PERMISSIONS).getReason());
        assertEquals(Status.ERROR, item(Type.PERMISSIONS).getStatus());

        // nothing to hang up
        env.blockByRating = false;
        env.blockBlacklisted = false;
        assertEquals(Status.OK, item(Type.PERMISSIONS).getStatus());
    }

    @Test
    public void contactsPermissionMattersOnlyWithUseContacts() {
        env.contactsPermission = false;
        assertEquals(Status.OK, item(Type.PERMISSIONS).getStatus());

        env.useContacts = true;
        assertEquals(Status.WARNING, item(Type.PERMISSIONS).getStatus());
        assertEquals(Reason.PERMISSIONS_CONTACTS_MISSING, item(Type.PERMISSIONS).getReason());
    }

    // blocking

    @Test
    public void blockingSettings() {
        assertEquals(Reason.BLOCKING_RATING, item(Type.BLOCKING).getReason());

        env.blockByRating = false;
        // blacklist on, but empty: nothing would be blocked
        assertEquals(Reason.BLOCKING_EMPTY_BLACKLIST, item(Type.BLOCKING).getReason());
        assertEquals(Status.ERROR, item(Type.BLOCKING).getStatus());

        env.blacklistEmpty = false;
        assertEquals(Reason.BLOCKING_NO_RATING, item(Type.BLOCKING).getReason());
        assertEquals(Status.WARNING, item(Type.BLOCKING).getStatus());

        env.blockBlacklisted = false;
        assertEquals(Reason.BLOCKING_OFF, item(Type.BLOCKING).getReason());
        assertEquals(Status.ERROR, item(Type.BLOCKING).getStatus());
        assertEquals(Action.OPEN_SETTINGS, item(Type.BLOCKING).getAction());

        env.blockHidden = true;
        assertEquals(Reason.BLOCKING_NO_RATING, item(Type.BLOCKING).getReason());
    }

    // notifications

    @Test
    public void notificationPermissionOnlyFromAndroid13() {
        env.notificationPermission = false;
        CheckItem item = item(Type.NOTIFICATIONS);
        assertEquals(Reason.NOTIFICATIONS_PERMISSION_MISSING, item.getReason());
        assertEquals(Status.WARNING, item.getStatus());
        assertEquals(Action.OPEN_NOTIFICATION_SETTINGS, item.getAction());

        env.sdkInt = 32;
        assertEquals(Reason.NOTIFICATIONS_OK, item(Type.NOTIFICATIONS).getReason());
    }

    @Test
    public void blockedChannelsAreReported() {
        env.blockedChannels = 2;
        assertEquals(Reason.NOTIFICATIONS_CHANNELS_BLOCKED, item(Type.NOTIFICATIONS).getReason());
        assertEquals(Status.WARNING, item(Type.NOTIFICATIONS).getStatus());

        env.notificationsEnabled = false;
        assertEquals(Reason.NOTIFICATIONS_APP_DISABLED, item(Type.NOTIFICATIONS).getReason());
    }

    @Test
    public void notificationsDontMatterIfNothingUsesThem() {
        env.incomingCallNotifications = false;
        env.blockByRating = false;
        env.blockBlacklisted = false;
        env.notificationsEnabled = false;

        CheckItem item = item(Type.NOTIFICATIONS);
        assertEquals(Reason.NOTIFICATIONS_SETTING_OFF, item.getReason());
        assertEquals(Status.INFO, item.getStatus());
    }

    // battery, contacts

    @Test
    public void batteryOnlyWithMonitoringService() {
        assertNull(item(Type.BATTERY));

        env.monitoringService = true;
        assertEquals(Status.INFO, item(Type.BATTERY).getStatus());
        assertEquals(Reason.BATTERY_OPTIMIZED, item(Type.BATTERY).getReason());
        assertEquals(Action.OPEN_BATTERY_SETTINGS, item(Type.BATTERY).getAction());

        env.ignoringBatteryOptimizations = true;
        assertEquals(Status.OK, item(Type.BATTERY).getStatus());
    }

    @Test
    public void contactsInfoOnlyWithUseContacts() {
        assertNull(item(Type.CONTACTS));

        env.useContacts = true;
        CheckItem item = item(Type.CONTACTS);
        assertNotNull(item);
        assertEquals(Reason.CONTACTS_NEVER_BLOCKED, item.getReason());
    }

    // sources

    private CheckItem checkSource(SetupCheckEnvironment.Source source) {
        return SetupCheck.checkSource(source, NOW);
    }

    @Test
    public void sourceAgeThresholds() {
        CheckItem fresh = checkSource(source("a", true, NOW - 14 * DAY, null));
        assertEquals(Status.OK, fresh.getStatus());
        assertEquals(14, fresh.getAgeDays());

        CheckItem stale = checkSource(source("a", true, NOW - 14 * DAY - 1, null));
        assertEquals(Status.WARNING, stale.getStatus());
        assertEquals(Reason.SOURCE_STALE, stale.getReason());
        assertEquals(Action.UPDATE_SOURCE, stale.getAction());
        assertEquals(14, stale.getAgeDays());

        CheckItem old = checkSource(source("a", true, NOW - 40 * DAY, null));
        assertEquals(40, old.getAgeDays());
    }

    @Test
    public void neverDownloadedSourceIsAnError() {
        CheckItem item = checkSource(source("a", false, 0, null));
        assertEquals(Status.ERROR, item.getStatus());
        assertEquals(Reason.SOURCE_NEVER, item.getReason());
        assertEquals(Action.UPDATE_SOURCE, item.getAction());
        assertEquals(-1, item.getAgeDays());

        // a failed first download is still "never"
        assertEquals(Reason.SOURCE_NEVER,
                checkSource(source("a", false, 0, "timeout")).getReason());
    }

    @Test
    public void lastErrorIsAWarning() {
        CheckItem item = checkSource(source("a", true, NOW - DAY, "HTTP 500"));
        assertEquals(Status.WARNING, item.getStatus());
        assertEquals(Reason.SOURCE_ERROR, item.getReason());
        assertEquals("HTTP 500", item.getSource().getLastError());
    }

    @Test
    public void unknownUpdateTimeIsNotStale() {
        CheckItem item = checkSource(source("a", true, 0, null));
        assertEquals(Status.OK, item.getStatus());
        assertEquals(-1, item.getAgeDays());
    }

    @Test
    public void futureTimestampCountsAsFresh() {
        CheckItem item = checkSource(source("a", true, NOW + DAY, null));
        assertEquals(Status.OK, item.getStatus());
        assertEquals(0, item.getAgeDays());
    }

    @Test
    public void filesAreNeverStale() {
        SetupCheckEnvironment.Source file = new SetupCheckEnvironment.Source("csv_x", "x",
                true, true, true, false, false, NOW - 100 * DAY, null);
        assertEquals(Status.OK, checkSource(file).getStatus());

        SetupCheckEnvironment.Source broken = new SetupCheckEnvironment.Source("csv_x", "x",
                true, true, true, false, false, NOW - DAY, "load error");
        assertEquals(Action.OPEN_SOURCE, checkSource(broken).getAction());
    }

    @Test
    public void disabledRunningAndUnconfiguredSources() {
        SetupCheckEnvironment.Source disabled = new SetupCheckEnvironment.Source("a", "a",
                false, true, false, true, false, 0, null);
        assertEquals(Status.INFO, checkSource(disabled).getStatus());
        assertEquals(Reason.SOURCE_DISABLED, checkSource(disabled).getReason());

        SetupCheckEnvironment.Source running = new SetupCheckEnvironment.Source("a", "a",
                true, true, false, true, true, 0, null);
        assertEquals(Status.OK, checkSource(running).getStatus());
        assertEquals(Reason.SOURCE_UPDATING, checkSource(running).getReason());

        SetupCheckEnvironment.Source noKey = new SetupCheckEnvironment.Source("a", "a",
                true, false, false, true, false, 0, null);
        assertEquals(Status.INFO, checkSource(noKey).getStatus());
        assertEquals(Reason.SOURCE_NOT_CONFIGURED, checkSource(noKey).getReason());
        assertEquals(Action.OPEN_SOURCE, checkSource(noKey).getAction());
    }

    @Test
    public void noEnabledSource() {
        env.sources.clear();
        env.sources.add(new SetupCheckEnvironment.Source("a", "a",
                false, true, true, true, false, NOW, null));

        CheckItem item = item(Type.SOURCES);
        assertEquals(Reason.SOURCES_NONE_ENABLED, item.getReason());
        assertEquals(Status.ERROR, item.getStatus());
        assertEquals(Action.OPEN_SOURCES, item.getAction());

        env.blockByRating = false;
        assertEquals(Status.WARNING, item(Type.SOURCES).getStatus());
    }

    @Test
    public void itemKeysIncludeTheSource() {
        CheckItem item = checkSource(source("phoneblock", false, 0, null));
        assertEquals("SOURCE:SOURCE_NEVER:phoneblock", item.getKey());
    }

    // SMS warnings

    @Test
    public void smsWarningsOffAreNotListed() {
        env.smsPermission = true;
        assertNull(item(Type.SMS_WARNINGS));
    }

    @Test
    public void smsWarningsActiveAreInfo() {
        env.smsWarnings = true;
        env.smsPermission = true;

        CheckItem item = item(Type.SMS_WARNINGS);
        assertEquals(Status.INFO, item.getStatus());
        assertEquals(Reason.SMS_WARNINGS_ACTIVE, item.getReason());
        assertEquals(Action.NONE, item.getAction());
        assertEquals(0, SetupCheck.run(env).getProblemCount());
    }

    @Test
    public void smsWarningsWithoutPermissionWarn() {
        env.smsWarnings = true;

        CheckItem item = item(Type.SMS_WARNINGS);
        assertEquals(Status.WARNING, item.getStatus());
        assertEquals(Reason.SMS_PERMISSION_MISSING, item.getReason());
        assertEquals(Action.REQUEST_SMS_PERMISSION, item.getAction());

        SetupCheck.Result result = SetupCheck.run(env);
        // SMS are never blocked: calls are still protected
        assertTrue(result.isProtectionWorking());
        assertEquals(1, result.getWarningCount());
    }

}
