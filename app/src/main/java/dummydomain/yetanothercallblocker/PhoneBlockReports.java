package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dummydomain.yetanothercallblocker.data.ContactsHelper;
import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.UserMark;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockClient;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportQueue;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockReporter;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockSync;
import dummydomain.yetanothercallblocker.work.PhoneBlockReportWorker;

/**
 * "Send my marks to PhoneBlock": the settings, the persistent queue and the glue
 * between the user's marks ({@link UserMarkActions}) and the background sending
 * ({@link PhoneBlockReportWorker}).
 *
 * <p>Reports are only sent when an API key is set, the setting is on (the default) and
 * the user has seen the one-time explanation ({@link PhoneBlockReportDialogs}); marks
 * made before that are queued and sent after the user agreed (dropped if the user
 * declines). Contacts and hidden numbers are never reported.</p>
 */
public final class PhoneBlockReports {

    private static final String PREFS_NAME = "phoneblock_reports";
    private static final String PREF_ENABLED = "enabled";
    private static final String PREF_EXPLAINED = "explained";
    private static final String PREF_CATEGORY = "category";
    private static final String PREF_LAST_ERROR = "lastError";

    static final String QUEUE_FILE = "phoneblock/reports.tsv";

    private static final Logger LOG = LoggerFactory.getLogger(PhoneBlockReports.class);

    /** Serializes queue updates of mark changes (keeps their order, off the main thread). */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "phoneblock-reports"));

    private static PhoneBlockReportQueue queue;

    private PhoneBlockReports() {}

    // settings

    private static Context storageContext() {
        // the same (device protected) storage as the other settings and data files
        return App.getInstance().createDeviceProtectedStorageContext();
    }

    private static SharedPreferences prefs() {
        return storageContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean hasToken() {
        return !TextUtils.isEmpty(App.getSettings().getPhoneBlockToken());
    }

    /** @return the "send my marks to PhoneBlock" setting (on by default) */
    public static boolean isEnabled() {
        return prefs().getBoolean(PREF_ENABLED, true);
    }

    /** @return whether the user has seen the explanation (or changed the setting) */
    public static boolean isExplained() {
        return prefs().getBoolean(PREF_EXPLAINED, false);
    }

    /**
     * Changes the setting (also counts as "explained"). Switching it off drops the
     * reports that were not sent yet; switching it on sends the queued ones.
     */
    public static void setEnabled(Context context, boolean enabled) {
        prefs().edit().putBoolean(PREF_ENABLED, enabled).putBoolean(PREF_EXPLAINED, true).apply();
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                if (enabled) {
                    scheduleIfPending(appContext);
                } else {
                    getQueue().cancelAll();
                    PhoneBlockReportWorker.cancel(appContext);
                }
            } catch (Exception e) {
                LOG.error("setEnabled() failed", e);
            }
        });
    }

    /** @return whether reports are sent now: key, setting and consent */
    public static boolean isActive() {
        return hasToken() && isEnabled() && isExplained();
    }

    /** @return the category last chosen for spam reports */
    public static PhoneBlockReporter.Category getCategory() {
        return PhoneBlockReporter.Category.fromName(prefs().getString(PREF_CATEGORY, null));
    }

    public static void setCategory(PhoneBlockReporter.Category category) {
        prefs().edit().putString(PREF_CATEGORY, category.name()).apply();
    }

    /** @return the last error of the background sending, null if the last run succeeded */
    public static String getLastError() {
        return prefs().getString(PREF_LAST_ERROR, null);
    }

    public static void setLastError(String error) {
        prefs().edit().putString(PREF_LAST_ERROR, error).apply();
    }

    // queue

    public static synchronized PhoneBlockReportQueue getQueue() {
        if (queue == null) {
            queue = new PhoneBlockReportQueue(new File(storageContext().getFilesDir(), QUEUE_FILE));
        }
        return queue;
    }

    /** @return the reporter, null if PhoneBlock is not initialized */
    public static PhoneBlockReporter getReporter() {
        PhoneBlockSync sync = YacbHolder.getPhoneBlockSync();
        if (sync == null) return null;
        return new PhoneBlockReporter(sync.getClient(), getQueue(), System::currentTimeMillis);
    }

    /**
     * @return whether a mark of the number can be reported: key set, setting on,
     * a number in international form, not a contact (as far as known here)
     */
    public static boolean canReport(NumberInfo numberInfo) {
        if (numberInfo == null || numberInfo.noNumber) return false;
        return PhoneBlockReporter.shouldReport(isEnabled(), App.getSettings().getPhoneBlockToken(),
                numberInfo.contactItem != null, numberInfo.isHiddenNumber,
                UserMarkActions.getMarkKey(numberInfo));
    }

    /**
     * Called by {@link UserMarkActions} after a mark was set, changed, removed or undone.
     * Queues the report in the background (contacts are checked there) and schedules
     * the sending.
     *
     * @param key        the normalized number
     * @param rawNumber  the number as received (for the contacts lookup), may be null
     * @param type       the new mark, null if removed
     * @param inContacts true if the number is known to be a contact
     * @param deferSpam  true if the caller asks for the category and calls
     *                   {@link #reportSpam} itself (a SPAM mark is not queued here)
     */
    static void onMarkChanged(String key, String rawNumber, UserMark.Type type,
                              boolean inContacts, boolean deferSpam) {
        if (deferSpam && type == UserMark.Type.SPAM) return;
        Context appContext = App.getInstance();
        if (appContext == null || inContacts || !hasToken() || !isEnabled()) return;
        EXECUTOR.execute(() -> {
            try {
                if (!PhoneBlockReporter.shouldReport(isEnabled(),
                        App.getSettings().getPhoneBlockToken(), isContact(appContext, key, rawNumber),
                        false, key)) {
                    LOG.debug("onMarkChanged() not reported");
                    return;
                }
                PhoneBlockReporter.Category category = null;
                if (type == UserMark.Type.SPAM) category = categoryFor(key);
                queue(appContext, key, type, category);
            } catch (Exception e) {
                LOG.error("onMarkChanged() failed", e);
            }
        });
    }

    /** Queues a spam report with the chosen category (after the category dialog). */
    public static void reportSpam(Context context, String key,
                                  PhoneBlockReporter.Category category) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                queue(appContext, key, UserMark.Type.SPAM, category);
            } catch (Exception e) {
                LOG.error("reportSpam() failed", e);
            }
        });
    }

    private static void queue(Context appContext, String key, UserMark.Type type,
                              PhoneBlockReporter.Category category) throws Exception {
        PhoneBlockReporter.Category effective = category != null
                ? category : PhoneBlockReporter.Category.DEFAULT;
        PhoneBlockReportQueue.Verdict verdict = PhoneBlockReporter.verdictFor(type);
        PhoneBlockClient.Rating rating = verdict == PhoneBlockReportQueue.Verdict.SPAM
                ? effective.getRating() : null;
        PhoneBlockReportQueue.Entry entry = getQueue().request(key, verdict, rating,
                System.currentTimeMillis());
        LOG.info("queue() {}", entry);
        if (entry != null && entry.isPending() && isExplained()) {
            PhoneBlockReportWorker.schedule(appContext);
        }
    }

    /**
     * For a SPAM mark set without a category dialog (e.g. undo): keep the rating that was
     * reported before, otherwise the last chosen category.
     */
    private static PhoneBlockReporter.Category categoryFor(String key) {
        PhoneBlockReportQueue.Entry entry = getQueue().get(key);
        PhoneBlockClient.Rating previous = entry == null ? null
                : entry.getSentRating() != null ? entry.getSentRating() : entry.getWantedRating();
        if (previous != null) {
            for (PhoneBlockReporter.Category c : PhoneBlockReporter.Category.values()) {
                if (c.getRating() == previous) return c;
            }
        }
        return getCategory();
    }

    private static boolean isContact(Context context, String key, String rawNumber) {
        try {
            return ContactsHelper.getContact(context, key) != null
                    || rawNumber != null && !rawNumber.equals(key)
                    && ContactsHelper.getContact(context, rawNumber) != null;
        } catch (Exception e) {
            LOG.warn("isContact() failed", e);
            // better not report than report a contact
            return true;
        }
    }

    /** Schedules the sending if reports are waiting (e.g. at start or after consent). */
    public static void scheduleIfPending(Context context) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                if (isActive() && getQueue().getPendingCount() > 0) {
                    PhoneBlockReportWorker.schedule(appContext);
                }
            } catch (Exception e) {
                LOG.error("scheduleIfPending() failed", e);
            }
        });
    }

    /** Forgets an unsent report of the number (e.g. "don't send" in the category dialog). */
    public static void cancel(String key) {
        EXECUTOR.execute(() -> {
            try {
                getQueue().cancel(key);
            } catch (Exception e) {
                LOG.error("cancel() failed", e);
            }
        });
    }

    // UI

    /**
     * Runs the action on the main thread after the queue updates requested so far are
     * done (to refresh a status display).
     */
    static void runAfterQueued(Runnable action) {
        if (action == null) return;
        EXECUTOR.execute(() -> new Handler(Looper.getMainLooper()).post(action));
    }

    /**
     * @return the report status line of the info dialog ("Sent to PhoneBlock"),
     * null if there is nothing to show. Reads a small file.
     */
    public static String getStatusText(Context context, NumberInfo numberInfo) {
        if (numberInfo == null || numberInfo.noNumber) return null;
        PhoneBlockReportQueue.Entry entry;
        try {
            entry = getQueue().get(UserMarkActions.getMarkKey(numberInfo));
        } catch (Exception e) {
            LOG.warn("getStatusText() failed", e);
            return null;
        }
        if (entry == null) return null;

        switch (entry.getStatus()) {
            case PENDING:
                // without a key (removed meanwhile) nothing can be sent
                return context.getString(hasToken()
                        ? R.string.pbreport_status_pending
                        : R.string.pbreport_status_pending_inactive);
            case SENT:
                // only if PhoneBlock knows the current mark
                UserMark mark = numberInfo.userMark;
                if (mark == null || PhoneBlockReporter.verdictFor(mark.getType())
                        != entry.getSent()) {
                    return null;
                }
                return context.getString(R.string.pbreport_status_sent);
            case REJECTED:
                return context.getString(R.string.pbreport_status_rejected);
            default:
                return null;
        }
    }

}
