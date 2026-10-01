package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PhoneBlockClientTest {

    static final String TOKEN = "pbt_test-token";

    /** Transport returning canned responses and recording the requests. */
    static class FakeTransport implements PhoneBlockClient.HttpTransport {
        final Deque<PhoneBlockClient.HttpResponse> responses = new ArrayDeque<>();
        final List<String> urls = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        IOException failure;

        FakeTransport respond(int code, String body) {
            responses.add(PhoneBlockClient.HttpResponse.of(code, body));
            return this;
        }

        @Override
        public PhoneBlockClient.HttpResponse get(String url, Map<String, String> headers)
                throws IOException {
            urls.add(url);
            this.headers.add(headers);
            if (failure != null) throw failure;
            if (responses.isEmpty()) throw new AssertionError("Unexpected request " + url);
            return responses.poll();
        }
    }

    static String resource(String name) throws IOException {
        return BnetzaMeasuresParserTest.readResource(name);
    }

    private static PhoneBlockClient client(FakeTransport transport) {
        return new PhoneBlockClient(transport, null, "YACB-test/1.0");
    }

    @Test
    public void parsesFullBlocklist() throws IOException {
        PhoneBlockClient.Blocklist blocklist = PhoneBlockClient.parseBlocklist(
                new StringReader(resource("phoneblock_blocklist_full.json")));

        assertEquals(4217, blocklist.getVersion());
        assertEquals(6, blocklist.getEntries().size());
        assertEquals(0, blocklist.getInvalidEntries());

        PhoneBlockClient.BlocklistEntry first = blocklist.getEntries().get(0);
        assertEquals("+4930123456701", first.getPhone());
        assertEquals(10, first.getVotes());
        assertEquals(PhoneBlockClient.Rating.G_FRAUD, first.getRating());
        assertEquals(1759300000000L, first.getLastActivity());

        PhoneBlockClient.BlocklistEntry italian = blocklist.getEntries().get(4);
        assertEquals("+390456789123", italian.getPhone());
        assertEquals(PhoneBlockClient.Rating.F_GAMBLE, italian.getRating());
    }

    @Test
    public void parsesIncrementalUpdate() throws IOException {
        PhoneBlockClient.Blocklist update = PhoneBlockClient.parseBlocklist(
                new StringReader(resource("phoneblock_blocklist_update.json")));

        assertEquals(4230, update.getVersion());
        assertEquals(4, update.getEntries().size());
        PhoneBlockClient.BlocklistEntry removal = update.getEntries().get(1);
        assertEquals("+498912345602", removal.getPhone());
        assertEquals(0, removal.getVotes());
        assertEquals(PhoneBlockClient.Rating.A_LEGITIMATE, removal.getRating());
    }

    @Test
    public void skipsMalformedEntriesAndUnknownFields() throws IOException {
        String json = "{\"extra\":{\"a\":[1,2]},\"numbers\":["
                + "{\"phone\":\"+4930111\",\"votes\":4,\"rating\":\"NEW_RATING\"},"
                + "{\"votes\":10},"
                + "{\"phone\":\"030 1234\",\"votes\":10},"
                + "{\"phone\":\"+49301234567\",\"rating\":\"G_FRAUD\"},"
                + "42,"
                + "{\"phone\":\"00491701234567\",\"votes\":\"20\",\"rating\":\"g_fraud\"}"
                + "],\"version\":7}";

        PhoneBlockClient.Blocklist blocklist = PhoneBlockClient.parseBlocklist(new StringReader(json));
        assertEquals(7, blocklist.getVersion());
        assertEquals(2, blocklist.getEntries().size());
        assertEquals(4, blocklist.getInvalidEntries());

        // unknown rating codes are kept as null
        assertNull(blocklist.getEntries().get(0).getRating());
        assertEquals("+491701234567", blocklist.getEntries().get(1).getPhone());
        assertEquals(20, blocklist.getEntries().get(1).getVotes());
        assertEquals(PhoneBlockClient.Rating.G_FRAUD, blocklist.getEntries().get(1).getRating());
    }

    @Test
    public void emptyBlocklist() throws IOException {
        PhoneBlockClient.Blocklist blocklist = PhoneBlockClient.parseBlocklist(
                new StringReader("{\"numbers\":[],\"version\":12}"));
        assertTrue(blocklist.getEntries().isEmpty());
        assertEquals(12, blocklist.getVersion());
    }

    @Test
    public void rejectsNonBlocklistJson() {
        for (String json : new String[]{"{}", "[]", "{\"phone\":\"+49\"}", "<html></html>"}) {
            try {
                PhoneBlockClient.parseBlocklist(new StringReader(json));
                fail("Expected an exception for " + json);
            } catch (IOException expected) {
                // ok
            }
        }
    }

    @Test
    public void parsesPhoneInfo() throws IOException {
        PhoneBlockClient.PhoneInfo info = PhoneBlockClient.parsePhoneInfo(
                new StringReader(resource("phoneblock_check_spam.json")));

        assertTrue(info.isKnown());
        assertEquals("+4915112345678", info.getPhone());
        assertEquals(42, info.getVotes());
        assertEquals(120, info.getVotesWildcard());
        assertEquals(PhoneBlockClient.Rating.E_ADVERTISING, info.getRating());
        assertFalse(info.isWhiteListed());
        assertFalse(info.isBlackListed());
        assertFalse(info.isArchived());
        assertEquals("(DE) 0151 12345678", info.getLabel());
        assertEquals("Mobilfunk", info.getLocation());

        PhoneBlockClient.PhoneInfo unknown = PhoneBlockClient.parsePhoneInfo(
                new StringReader(resource("phoneblock_check_unknown.json")));
        assertFalse(unknown.isKnown());
        assertEquals(0, unknown.getVotes());
        assertEquals(PhoneBlockClient.Rating.A_LEGITIMATE, unknown.getRating());
        assertNull(unknown.getLabel());
    }

    @Test
    public void sha1MatchesPhoneBlockDocumentation() {
        // example from phoneblock.json / INTEGRATIONS.md
        assertEquals("3D1D76F0C3664E1E818C6ECCFD8843AD1F4091CC",
                PhoneBlockClient.sha1Hex("+4917650642602"));
    }

    @Test
    public void fetchFullBlocklistRequest() throws IOException {
        FakeTransport transport = new FakeTransport()
                .respond(200, resource("phoneblock_blocklist_full.json"));

        PhoneBlockClient.Blocklist blocklist = client(transport).fetchBlocklist(" " + TOKEN + " ", 0);

        assertEquals(6, blocklist.getEntries().size());
        assertEquals("https://phoneblock.net/phoneblock/api/blocklist?format=json",
                transport.urls.get(0));
        Map<String, String> headers = transport.headers.get(0);
        assertEquals("Bearer " + TOKEN, headers.get("Authorization"));
        assertEquals("YACB-test/1.0", headers.get("User-Agent"));
    }

    @Test
    public void fetchIncrementalRequest() throws IOException {
        FakeTransport transport = new FakeTransport()
                .respond(200, resource("phoneblock_blocklist_update.json"));

        new PhoneBlockClient(transport, "https://example.org/pb-test/api/", null)
                .fetchBlocklist(TOKEN, 4217);

        assertEquals("https://example.org/pb-test/api/blocklist?format=json&since=4217",
                transport.urls.get(0));
        assertFalse(transport.headers.get(0).containsKey("User-Agent"));
    }

    @Test
    public void checkSendsOnlyTheHash() throws IOException {
        FakeTransport transport = new FakeTransport()
                .respond(200, resource("phoneblock_check_spam.json"));

        PhoneBlockClient.PhoneInfo info = client(transport).check(TOKEN, "+4917650642602");

        assertEquals(42, info.getVotes());
        String url = transport.urls.get(0);
        assertEquals("https://phoneblock.net/phoneblock/api/check?sha1="
                + "3D1D76F0C3664E1E818C6ECCFD8843AD1F4091CC&format=json", url);
        assertFalse(url.contains("4917650642602"));
    }

    @Test
    public void authErrors() throws IOException {
        FakeTransport transport = new FakeTransport()
                .respond(401, "Please provide login credentials.");
        try {
            client(transport).testConnection(TOKEN);
            fail();
        } catch (PhoneBlockClient.ApiException e) {
            assertTrue(e.isAuthError());
            assertEquals(401, e.getHttpCode());
            assertTrue(e.getMessage(), e.getMessage().contains("Please provide login credentials."));
        }

        transport.respond(429, null);
        try {
            client(transport).fetchBlocklist(TOKEN, 0);
            fail();
        } catch (PhoneBlockClient.ApiException e) {
            assertTrue(e.isRateLimited());
            assertFalse(e.isAuthError());
        }

        transport.respond(200, "ok");
        client(transport).testConnection(TOKEN);
        assertEquals("https://phoneblock.net/phoneblock/api/test", transport.urls.get(2));
    }

    @Test(expected = IllegalArgumentException.class)
    public void requiresToken() throws IOException {
        client(new FakeTransport()).fetchBlocklist("  ", 0);
    }

    @Test
    public void normalizePhone() {
        assertEquals("+4930123456", PhoneBlockClient.normalizePhone(" +4930123456 "));
        assertEquals("+4930123456", PhoneBlockClient.normalizePhone("004930123456"));
        assertNull(PhoneBlockClient.normalizePhone("030123456"));
        assertNull(PhoneBlockClient.normalizePhone("+49 30 123456"));
        assertNull(PhoneBlockClient.normalizePhone("+0301234"));
        assertNull(PhoneBlockClient.normalizePhone("unknown"));
        assertNull(PhoneBlockClient.normalizePhone(null));
    }

}
