package dummydomain.yetanothercallblocker;

import android.text.TextUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dummydomain.yetanothercallblocker.data.BlacklistService;
import dummydomain.yetanothercallblocker.data.BlacklistUtils;
import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.UserMark;
import dummydomain.yetanothercallblocker.data.UserMarksStore;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.db.BlacklistItem;

/**
 * UI-side helpers for the user's own marks ("My mark") and the quick "block" action.
 */
public class UserMarkActions {

    /** The result of a mark change; keeps the previous mark for "undo". */
    public static class Change {
        public final String number;
        public final UserMark previous;
        public final UserMark current;

        Change(String number, UserMark previous, UserMark current) {
            this.number = number;
            this.previous = previous;
            this.current = current;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(UserMarkActions.class);

    /**
     * @return the key a mark of the number is stored under: the normalized (E.164)
     * number if known, the raw number otherwise
     */
    public static String getMarkKey(NumberInfo numberInfo) {
        return !TextUtils.isEmpty(numberInfo.normalizedNumber)
                ? numberInfo.normalizedNumber : numberInfo.number;
    }

    /**
     * Sets or clears the mark of the number and updates {@code numberInfo.userMark}.
     *
     * @param type the new mark, null to remove the mark
     * @return the change, or null if the number is hidden or saving failed
     */
    public static Change setMark(NumberInfo numberInfo, UserMark.Type type) {
        return setMark(numberInfo, type, false);
    }

    /**
     * Like {@link #setMark(NumberInfo, UserMark.Type)}; also queues the PhoneBlock report.
     *
     * @param deferSpam true if the caller asks for the PhoneBlock category of a SPAM mark
     *                  itself ({@link PhoneBlockReportDialogs#afterMarkChanged})
     */
    public static Change setMark(NumberInfo numberInfo, UserMark.Type type, boolean deferSpam) {
        if (numberInfo == null || numberInfo.noNumber) return null;
        Change change = saveMark(getMarkKey(numberInfo), numberInfo.number, type);
        if (change != null) {
            numberInfo.userMark = change.current;
            PhoneBlockReports.onMarkChanged(change.number, numberInfo.number, type,
                    numberInfo.contactItem != null, deferSpam);
        }
        return change;
    }

    /**
     * Sets or clears the mark of a number.
     *
     * @param key       the normalized number (see {@link #getMarkKey(NumberInfo)})
     * @param rawNumber the number as received, used to clear old marks stored under it;
     *                  may be null
     * @param type      the new mark, null to remove the mark
     * @return the change, or null if saving failed
     */
    public static Change setMark(String key, String rawNumber, UserMark.Type type) {
        Change change = saveMark(key, rawNumber, type);
        if (change != null) {
            PhoneBlockReports.onMarkChanged(change.number, rawNumber, type, false, false);
        }
        return change;
    }

    private static Change saveMark(String key, String rawNumber, UserMark.Type type) {
        UserMarksStore store = YacbHolder.getUserMarksStore();
        if (store == null || UserMarksStore.normalizeKey(key) == null) return null;

        try {
            UserMark previous = store.get(key, rawNumber);
            if (previous != null && !previous.getNumber().equals(UserMarksStore.normalizeKey(key))) {
                // stored under the raw number earlier: move it to the normalized key
                store.remove(previous.getNumber());
            }

            UserMark current = null;
            if (type != null) {
                store.set(key, type, System.currentTimeMillis(),
                        previous != null && previous.getType() == type ? previous.getNote() : null);
                current = store.get(key);
            } else {
                store.remove(key);
            }
            LOG.info("setMark() {} -> {}", previous, current);
            return new Change(UserMarksStore.normalizeKey(key), previous, current);
        } catch (Exception e) {
            LOG.error("setMark() failed", e);
            return null;
        }
    }

    /** Reverts a change made by {@code setMark()}. */
    public static boolean undo(Change change) {
        UserMarksStore store = YacbHolder.getUserMarksStore();
        if (store == null || change == null) return false;
        try {
            if (change.previous != null && !change.previous.getNumber().equals(change.number)) {
                store.remove(change.number);
            }
            store.restore(change.number, change.previous);
            PhoneBlockReports.onMarkChanged(change.number, null,
                    change.previous != null ? change.previous.getType() : null, false, false);
            return true;
        } catch (Exception e) {
            LOG.error("undo() failed", e);
            return false;
        }
    }

    /**
     * Adds the exact number to the blacklist unless it is already blacklisted.
     * Does database I/O: call it off the main thread.
     *
     * @return whether the number is blacklisted now
     */
    public static boolean addToBlacklist(String number, String name) {
        BlacklistService blacklistService = YacbHolder.getBlacklistService();
        if (blacklistService == null || TextUtils.isEmpty(number)) return false;

        try {
            if (blacklistService.getBlacklistItemForNumber(number) != null) {
                LOG.debug("addToBlacklist() already blacklisted");
                return true;
            }

            String pattern = BlacklistUtils.cleanNumber(number);
            if (pattern.isEmpty()) return false;

            blacklistService.insert(new BlacklistItem(name, pattern));
            LOG.info("addToBlacklist() added {}", pattern);
            return true;
        } catch (Exception e) {
            LOG.error("addToBlacklist() failed", e);
            return false;
        }
    }

}
