package dummydomain.yetanothercallblocker.data.provider;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dummydomain.yetanothercallblocker.data.sources.PhoneBlockClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PhoneBlockOnlineProviderTest {

    private static final String SPAM_RESPONSE = "{\"phone\":\"+4915112345678\",\"votes\":42,"
            + "\"rating\":\"E_ADVERTISING\",\"votesWildcard\":120,\"whiteListed\":false,"
            + "\"blackListed\":false,\"archived\":false,\"dateAdded\":1700000000000,"
            + "\"lastUpdate\":1759310000000,\"heat\":1.75,\"spamConfidence\":93,\"calls\":57}";

    private static final String UNKNOWN_RESPONSE = "{\"phone\":\"unknown\",\"votes\":0,"
            + "\"rating\":\"A_LEGITIMATE\",\"votesWildcard\":0,\"whiteListed\":false,"
            + "\"blackListed\":false,\"archived\":false,\"dateAdded\":0,\"lastUpdate\":0,"
            + "\"heat\":0.0,\"spamConfidence\":0,\"calls\":0}";

    private static class Transport implements PhoneBlockClient.HttpTransport {
        final List<String> urls = new ArrayList<>();
        String body = SPAM_RESPONSE;

        @Override
        public PhoneBlockClient.HttpResponse get(String url, Map<String, String> headers) {
            urls.add(url);
            return PhoneBlockClient.HttpResponse.of(200, body);
        }
    }

    private final Transport transport = new Transport();
    private String token = "token";
    private int minVotes = 10;

    private PhoneBlockOnlineProvider provider() {
        return new PhoneBlockOnlineProvider(new PhoneBlockClient(transport, null, null),
                () -> token, () -> minVotes);
    }

    @Test
    public void properties() {
        PhoneBlockOnlineProvider provider = provider();
        assertEquals(PhoneBlockOnlineProvider.ID, provider.getId());
        assertFalse(provider.isOffline());
        assertFalse(provider.isEnabledByDefault());
        assertTrue(provider.hasToken());
        token = " ";
        assertFalse(provider.hasToken());
    }

    @Test
    public void spamNumberIsNegative() throws Exception {
        ProviderResult result = provider().lookup("015112345678");

        assertEquals(ProviderResult.Rating.NEGATIVE, result.getRating());
        assertEquals("E_ADVERTISING", result.getCategory());
        assertEquals(42, result.getReviewCount());
        assertEquals(PhoneBlockOnlineProvider.ID, result.getSourceId());
        // the hash of the E.164 form is sent
        assertTrue(transport.urls.get(0).contains(
                "sha1=" + PhoneBlockClient.sha1Hex("+4915112345678")));
    }

    @Test
    public void thresholdIsApplied() throws Exception {
        minVotes = 50;
        assertNull(provider().lookup("+4915112345678"));
    }

    @Test
    public void unknownNumber() throws Exception {
        transport.body = UNKNOWN_RESPONSE;
        assertNull(provider().lookup("+4915112345678"));
    }

    @Test
    public void personalBlacklistAndWhitelist() {
        PhoneBlockOnlineProvider provider = provider();

        ProviderResult blocked = provider.toResult(new PhoneBlockClient.PhoneInfo("+4930123",
                0, 0, PhoneBlockClient.Rating.A_LEGITIMATE, false, true, false, null, null), 10);
        assertEquals(ProviderResult.Rating.NEGATIVE, blocked.getRating());
        assertNull(blocked.getCategory());

        ProviderResult allowed = provider.toResult(new PhoneBlockClient.PhoneInfo("+4930123",
                0, 0, PhoneBlockClient.Rating.A_LEGITIMATE, true, false, false, null, null), 10);
        assertEquals(ProviderResult.Rating.POSITIVE, allowed.getRating());

        // range votes alone are not used
        assertNull(provider.toResult(new PhoneBlockClient.PhoneInfo("unknown",
                0, 500, PhoneBlockClient.Rating.B_MISSED, false, false, false, null, null), 10));
    }

    @Test
    public void noRequestWithoutTokenOrNumber() throws Exception {
        token = null;
        assertNull(provider().lookup("+4915112345678"));
        token = "token";
        assertNull(provider().lookup("**21#"));
        assertNull(provider().lookup(""));
        assertTrue(transport.urls.isEmpty());
    }

    @Test(expected = IOException.class)
    public void errorsArePropagated() throws Exception {
        PhoneBlockOnlineProvider provider = new PhoneBlockOnlineProvider(
                new PhoneBlockClient((url, headers) -> PhoneBlockClient.HttpResponse.of(500, "x"),
                        null, null), () -> "token", () -> 10);
        provider.lookup("+4915112345678");
    }

}
