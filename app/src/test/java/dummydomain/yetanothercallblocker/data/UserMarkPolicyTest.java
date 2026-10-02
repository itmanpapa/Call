package dummydomain.yetanothercallblocker.data;

import org.junit.Test;

import static dummydomain.yetanothercallblocker.data.UserMarkPolicy.IncomingNotification.NONE;
import static dummydomain.yetanothercallblocker.data.UserMarkPolicy.IncomingNotification.PROMINENT;
import static dummydomain.yetanothercallblocker.data.UserMarkPolicy.IncomingNotification.REGULAR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UserMarkPolicyTest {

    private static final UserMark SPAM = new UserMark("+4930123", UserMark.Type.SPAM, 1, null);
    private static final UserMark NOT_SPAM
            = new UserMark("+4930123", UserMark.Type.NOT_SPAM, 1, null);

    @Test
    public void effectOfMarks() {
        assertEquals(UserMarkPolicy.Effect.NONE, UserMarkPolicy.effect(null));
        assertEquals(UserMarkPolicy.Effect.FORCE_NEGATIVE, UserMarkPolicy.effect(SPAM));
        assertEquals(UserMarkPolicy.Effect.FORCE_NOT_SPAM, UserMarkPolicy.effect(NOT_SPAM));
    }

    @Test
    public void markSkipsOtherSources() {
        assertTrue(UserMarkPolicy.shouldQueryOtherSources(null));
        assertFalse(UserMarkPolicy.shouldQueryOtherSources(SPAM));
        assertFalse(UserMarkPolicy.shouldQueryOtherSources(NOT_SPAM));
    }

    // incomingNotification(negative, unknown, inContacts, noNumber, mark,
    //                      prominentEnabled, regularAllowed)

    @Test
    public void negativeAndUnknownCallersAreProminent() {
        assertEquals(PROMINENT, UserMarkPolicy.incomingNotification(
                true, false, false, false, null, true, true));
        assertEquals(PROMINENT, UserMarkPolicy.incomingNotification(
                false, true, false, false, null, true, true));
        // a SPAM mark makes the number negative
        assertEquals(PROMINENT, UserMarkPolicy.incomingNotification(
                true, false, false, false, SPAM, true, true));
        // hidden numbers
        assertEquals(PROMINENT, UserMarkPolicy.incomingNotification(
                false, true, false, true, null, true, true));
    }

    @Test
    public void knownAndPositiveCallersAreRegular() {
        // positive or neutral rating
        assertEquals(REGULAR, UserMarkPolicy.incomingNotification(
                false, false, false, false, null, true, true));
        // contacts, even with a bad rating
        assertEquals(REGULAR, UserMarkPolicy.incomingNotification(
                false, true, true, false, null, true, true));
        assertEquals(REGULAR, UserMarkPolicy.incomingNotification(
                true, false, true, false, null, true, true));
        // marked "not spam"
        assertEquals(REGULAR, UserMarkPolicy.incomingNotification(
                false, false, false, false, NOT_SPAM, true, true));
    }

    @Test
    public void settingsAreRespected() {
        // the prominent notification is disabled
        assertEquals(REGULAR, UserMarkPolicy.incomingNotification(
                true, false, false, false, null, false, true));
        // the legacy per-kind toggle hides the notification completely
        assertEquals(NONE, UserMarkPolicy.incomingNotification(
                true, false, false, false, null, true, false));
        assertEquals(NONE, UserMarkPolicy.incomingNotification(
                false, true, false, false, null, true, false));
    }

    @Test
    public void notSpamActionOnlyForRatingBlocks() {
        assertTrue(UserMarkPolicy.offerNotSpamForBlockedCall(true, false));
        // blacklist or hidden-number blocks are not undone by a mark
        assertFalse(UserMarkPolicy.offerNotSpamForBlockedCall(false, false));
        assertFalse(UserMarkPolicy.offerNotSpamForBlockedCall(true, true));
    }

}
