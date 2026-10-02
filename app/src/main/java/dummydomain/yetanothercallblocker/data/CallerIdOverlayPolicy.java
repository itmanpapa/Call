package dummydomain.yetanothercallblocker.data;

/**
 * Decision rules for the caller ID card shown over the incoming call screen
 * ({@code CallerIdOverlay}). Plain Java so the rules can be unit-tested.
 *
 * <p>The card is shown for a ringing call that is not blocked and not from a contact,
 * when the setting is on and the app may draw over other apps. Hidden numbers get the
 * card without the "Block" / "Not spam" buttons.</p>
 *
 * <p>The same call may be reported twice (by the call screening service and by the
 * phone state listener, possibly with differently formatted numbers):
 * {@link Session} makes sure the card is shown once per call and doesn't come back
 * after the user closed it.</p>
 */
public final class CallerIdOverlayPolicy {

    /** The card hides itself after this time even if the end of the call is missed. */
    public static final long TIMEOUT_MILLIS = 60_000;

    /**
     * Requests within this time after the card was shown belong to the same call even if
     * the numbers don't match (e.g. "+49 30 1234567" from the call screening service and
     * "0301234567" from the phone state listener).
     */
    public static final long DUPLICATE_WINDOW_MILLIS = 5_000;

    /**
     * A call that wasn't followed by an "idle" event is forgotten after this time,
     * so a lost event can't suppress the card for the next call of the same number.
     */
    public static final long SESSION_EXPIRY_MILLIS = 3 * 60_000;

    /** Numbers are compared by this many trailing digits (ignores prefixes and formatting). */
    static final int KEY_DIGITS = 9;

    /** What to do with a request to show the card. */
    public enum Decision {
        /** Show the card. */
        SHOW,
        /** The card is visible for another call (call waiting): show the new call in it. */
        UPDATE,
        /** Already shown, or closed by the user, for this call: do nothing. */
        IGNORE
    }

    private CallerIdOverlayPolicy() {
    }

    /**
     * @param callInfoEnabled the "incoming call notifications" (caller info) setting
     * @param overlayEnabled  the "caller ID card over the call screen" setting
     * @param canDrawOverlays the app holds the "display over other apps" permission
     * @param inContacts      the number is a contact
     * @param blocked         the call is being blocked
     * @return whether the card should be shown for a ringing call
     */
    public static boolean shouldShow(boolean callInfoEnabled, boolean overlayEnabled,
                                     boolean canDrawOverlays, boolean inContacts,
                                     boolean blocked) {
        return callInfoEnabled && overlayEnabled && canDrawOverlays && !inContacts && !blocked;
    }

    /**
     * @return whether the card offers "Block" and "Not spam" (not for hidden numbers:
     * there is nothing to block or mark)
     */
    public static boolean showActions(boolean noNumber) {
        return !noNumber;
    }

    /**
     * @return whether the setup check should warn: the card is wanted, but the
     * permission is missing
     */
    public static boolean isPermissionMissing(boolean callInfoEnabled, boolean overlayEnabled,
                                              boolean canDrawOverlays) {
        return callInfoEnabled && overlayEnabled && !canDrawOverlays;
    }

    /**
     * @return a key identifying the caller: the last {@value #KEY_DIGITS} digits of the
     * number, "" for hidden numbers
     */
    public static String callKey(String number) {
        if (number == null) return "";
        StringBuilder digits = new StringBuilder(number.length());
        for (int i = 0; i < number.length(); i++) {
            char c = number.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        int start = Math.max(0, digits.length() - KEY_DIGITS);
        return digits.substring(start);
    }

    /** @return whether two numbers most likely belong to the same caller */
    public static boolean sameCaller(String number1, String number2) {
        return callKey(number1).equals(callKey(number2));
    }

    /**
     * @return the vertical offset of the card limited to {@code [min, max]};
     * {@code min} wins if the range is empty (the screen is too small)
     */
    public static int clampOffset(int y, int min, int max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, y));
    }

    /**
     * The state of the card during a call. Not thread-safe: used on the main thread.
     */
    public static final class Session {

        private boolean showing;
        /** The number of the call the card was last shown for, null if none. */
        private String number;
        private long shownAt;

        /**
         * Decides about a request to show the card and updates the state.
         *
         * @param number the number of the ringing call, null or "" if hidden
         * @param now    current time, millis
         */
        public Decision onShowRequest(String number, long now) {
            if (number == null) number = "";

            if (this.number != null && now - shownAt >= SESSION_EXPIRY_MILLIS) {
                // the end of the previous call was missed
                showing = false;
                this.number = null;
            }

            if (showing) {
                if (sameCaller(number, this.number)) return Decision.IGNORE;
                this.number = number;
                shownAt = now;
                return Decision.UPDATE;
            }

            if (this.number != null) {
                // closed (by the user, a timeout or answering) during this call
                if (sameCaller(number, this.number)) return Decision.IGNORE;
                if (now - shownAt < DUPLICATE_WINDOW_MILLIS) return Decision.IGNORE;
            }

            showing = true;
            this.number = number;
            shownAt = now;
            return Decision.SHOW;
        }

        /**
         * The card was closed by the user, timed out or the call was answered;
         * repeated reports of the same call don't show it again.
         */
        public void onHidden() {
            showing = false;
        }

        /** The phone is idle: the next call starts from scratch. */
        public void onCallEnded() {
            showing = false;
            number = null;
            shownAt = 0;
        }

        /** The card couldn't be added to the screen: allow the next attempt. */
        public void onShowFailed() {
            onCallEnded();
        }

        public boolean isShowing() {
            return showing;
        }
    }

}
