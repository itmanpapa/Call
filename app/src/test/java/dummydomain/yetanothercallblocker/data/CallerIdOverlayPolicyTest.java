package dummydomain.yetanothercallblocker.data;

import org.junit.Before;
import org.junit.Test;

import dummydomain.yetanothercallblocker.data.CallerIdOverlayPolicy.Decision;
import dummydomain.yetanothercallblocker.data.CallerIdOverlayPolicy.Session;

import static dummydomain.yetanothercallblocker.data.CallerIdOverlayPolicy.DUPLICATE_WINDOW_MILLIS;
import static dummydomain.yetanothercallblocker.data.CallerIdOverlayPolicy.SESSION_EXPIRY_MILLIS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CallerIdOverlayPolicyTest {

    private static final long T0 = 1_790_000_000_000L;

    private Session session;

    @Before
    public void setUp() {
        session = new Session();
    }

    // shouldShow(callInfoEnabled, overlayEnabled, canDrawOverlays, inContacts, blocked)

    @Test
    public void shownForRingingNonContacts() {
        assertTrue(CallerIdOverlayPolicy.shouldShow(true, true, true, false, false));
    }

    @Test
    public void notShownForContacts() {
        assertFalse(CallerIdOverlayPolicy.shouldShow(true, true, true, true, false));
    }

    @Test
    public void notShownForBlockedCalls() {
        assertFalse(CallerIdOverlayPolicy.shouldShow(true, true, true, false, true));
    }

    @Test
    public void notShownWithoutPermission() {
        assertFalse(CallerIdOverlayPolicy.shouldShow(true, true, false, false, false));
    }

    @Test
    public void notShownWhenSettingsAreOff() {
        assertFalse(CallerIdOverlayPolicy.shouldShow(true, false, true, false, false));
        assertFalse(CallerIdOverlayPolicy.shouldShow(false, true, true, false, false));
    }

    @Test
    public void hiddenNumbersHaveNoActions() {
        assertTrue(CallerIdOverlayPolicy.showActions(false));
        assertFalse(CallerIdOverlayPolicy.showActions(true));
    }

    @Test
    public void permissionMissingOnlyMattersWhenTheCardIsWanted() {
        assertTrue(CallerIdOverlayPolicy.isPermissionMissing(true, true, false));
        assertFalse(CallerIdOverlayPolicy.isPermissionMissing(true, true, true));
        assertFalse(CallerIdOverlayPolicy.isPermissionMissing(true, false, false));
        assertFalse(CallerIdOverlayPolicy.isPermissionMissing(false, true, false));
    }

    // numbers

    @Test
    public void callKeyIgnoresFormattingAndPrefixes() {
        assertEquals("301234567", CallerIdOverlayPolicy.callKey("+49 30 1234567"));
        assertEquals("301234567", CallerIdOverlayPolicy.callKey("030-1234567"));
        assertEquals("112", CallerIdOverlayPolicy.callKey("112"));
        assertEquals("", CallerIdOverlayPolicy.callKey(null));
        assertEquals("", CallerIdOverlayPolicy.callKey(""));
    }

    @Test
    public void sameCallerAcrossFormats() {
        assertTrue(CallerIdOverlayPolicy.sameCaller("+49301234567", "0301234567"));
        assertTrue(CallerIdOverlayPolicy.sameCaller(null, ""));
        assertFalse(CallerIdOverlayPolicy.sameCaller("+49301234567", "+49301234568"));
        assertFalse(CallerIdOverlayPolicy.sameCaller("", "0301234567"));
    }

    @Test
    public void offsetIsClamped() {
        assertEquals(100, CallerIdOverlayPolicy.clampOffset(100, 0, 500));
        assertEquals(0, CallerIdOverlayPolicy.clampOffset(-20, 0, 500));
        assertEquals(500, CallerIdOverlayPolicy.clampOffset(900, 0, 500));
        assertEquals(50, CallerIdOverlayPolicy.clampOffset(900, 50, 10));
    }

    // session

    @Test
    public void firstRequestShows() {
        assertEquals(Decision.SHOW, session.onShowRequest("+49301234567", T0));
        assertTrue(session.isShowing());
    }

    @Test
    public void duplicateReportOfTheSameCallIsIgnored() {
        // call screening service, then the phone state listener with a national number
        assertEquals(Decision.SHOW, session.onShowRequest("+49301234567", T0));
        assertEquals(Decision.IGNORE, session.onShowRequest("0301234567", T0 + 300));
        assertTrue(session.isShowing());
    }

    @Test
    public void duplicateHiddenNumberIsIgnored() {
        assertEquals(Decision.SHOW, session.onShowRequest(null, T0));
        assertEquals(Decision.IGNORE, session.onShowRequest("", T0 + 300));
    }

    @Test
    public void closedCardDoesNotComeBackForTheSameCall() {
        session.onShowRequest("+49301234567", T0);
        session.onHidden();
        assertFalse(session.isShowing());

        assertEquals(Decision.IGNORE, session.onShowRequest("+49301234567", T0 + 20_000));
    }

    @Test
    public void differentlyFormattedDuplicateAfterCloseIsIgnored() {
        session.onShowRequest("+49301234567", T0);
        session.onHidden();

        // a report that doesn't match the number, but arrives right after the first one
        assertEquals(Decision.IGNORE,
                session.onShowRequest("YACB_hangouts_stub", T0 + DUPLICATE_WINDOW_MILLIS - 1));
    }

    @Test
    public void anotherCallerUpdatesTheVisibleCard() {
        session.onShowRequest("+49301234567", T0);
        assertEquals(Decision.UPDATE, session.onShowRequest("+49309999999", T0 + 20_000));
        assertTrue(session.isShowing());
        // and then that caller is the current one
        assertEquals(Decision.IGNORE, session.onShowRequest("+49309999999", T0 + 20_500));
    }

    @Test
    public void anotherCallerAfterCloseIsShown() {
        session.onShowRequest("+49301234567", T0);
        session.onHidden();
        assertEquals(Decision.SHOW, session.onShowRequest("+49309999999", T0 + 20_000));
    }

    @Test
    public void nextCallAfterIdleIsShown() {
        session.onShowRequest("+49301234567", T0);
        session.onHidden();
        session.onCallEnded();
        assertEquals(Decision.SHOW, session.onShowRequest("+49301234567", T0 + 1_000));
    }

    @Test
    public void lostIdleEventDoesNotSuppressTheNextCall() {
        session.onShowRequest("+49301234567", T0);
        session.onHidden();
        assertEquals(Decision.SHOW,
                session.onShowRequest("+49301234567", T0 + SESSION_EXPIRY_MILLIS));
    }

    @Test
    public void failedShowAllowsTheNextAttempt() {
        session.onShowRequest("+49301234567", T0);
        session.onShowFailed();
        assertFalse(session.isShowing());
        assertEquals(Decision.SHOW, session.onShowRequest("+49301234567", T0 + 100));
    }

}
