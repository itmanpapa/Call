package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Test;

import java.io.IOException;
import java.util.Map;

import static dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportTestSupport.AUTH_REQUIRED;
import static dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportTestSupport.NOT_IN_LIST;
import static dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportTestSupport.RATE_INVALID;
import static dummydomain.yetanothercallblocker.data.sources.PhoneBlockReportTestSupport.RATE_OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PhoneBlockClientReportTest {

    private static final String TOKEN = "pbt_test-token";

    private static PhoneBlockClient client(PhoneBlockReportTestSupport.FakeTransport transport) {
        return new PhoneBlockClient(transport, null, "YACB-test/1.0");
    }

    @Test
    public void rateRequestMatchesTheApiExample() throws IOException {
        // the example of POST /rate in phoneblock.json
        String expected = BnetzaMeasuresParserTest.readResource("phoneblock_rate_request.json").trim();
        assertEquals(expected, PhoneBlockClient.buildRateRequest("+49123456789",
                PhoneBlockClient.Rating.C_PING, "Anrufer hat sofort aufgelegt."));
    }

    @Test
    public void rateRequestOmitsEmptyCommentAndEscapes() {
        assertEquals("{\"phone\":\"+4930123456\",\"rating\":\"E_ADVERTISING\"}",
                PhoneBlockClient.buildRateRequest("+4930123456",
                        PhoneBlockClient.Rating.E_ADVERTISING, "  "));
        assertEquals("{\"phone\":\"+4930123456\",\"rating\":\"G_FRAUD\","
                        + "\"comment\":\"say \\\"hi\\\"\\n\\\\ \\u0001!\"}",
                PhoneBlockClient.buildRateRequest("+4930123456",
                        PhoneBlockClient.Rating.G_FRAUD, "say \"hi\"\n\\ \u0001!"));
    }

    @Test
    public void ratePostsJsonWithBearerToken() throws IOException {
        PhoneBlockReportTestSupport.FakeTransport transport
                = new PhoneBlockReportTestSupport.FakeTransport().respond(200, RATE_OK);

        client(transport).rate(" " + TOKEN + " ", "004930123456",
                PhoneBlockClient.Rating.E_ADVERTISING, null);

        assertEquals(1, transport.requests.size());
        PhoneBlockReportTestSupport.Request request = transport.requests.get(0);
        assertEquals("POST", request.method);
        assertEquals("https://phoneblock.net/phoneblock/api/rate", request.url);
        assertEquals("application/json; charset=UTF-8", request.contentType);
        // a "00" prefix is sent in the "+" form the API documents
        assertEquals("{\"phone\":\"+4930123456\",\"rating\":\"E_ADVERTISING\"}", request.body);
        Map<String, String> headers = request.headers;
        assertEquals("Bearer " + TOKEN, headers.get("Authorization"));
        assertEquals("YACB-test/1.0", headers.get("User-Agent"));
    }

    @Test
    public void rateRejectsNonE164Numbers() throws IOException {
        PhoneBlockReportTestSupport.FakeTransport transport
                = new PhoneBlockReportTestSupport.FakeTransport();
        for (String number : new String[]{"030123456", "+49 30 123", "", "+0123456"}) {
            try {
                client(transport).rate(TOKEN, number, PhoneBlockClient.Rating.G_FRAUD, null);
                fail("accepted " + number);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    public void rateRequiresToken() throws IOException {
        try {
            client(new PhoneBlockReportTestSupport.FakeTransport()).rate("", "+4930123456",
                    PhoneBlockClient.Rating.G_FRAUD, null);
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void rateErrors() throws IOException {
        PhoneBlockReportTestSupport.FakeTransport transport
                = new PhoneBlockReportTestSupport.FakeTransport()
                .respond(401, AUTH_REQUIRED)
                .respond(400, RATE_INVALID);

        try {
            client(transport).rate(TOKEN, "+4930123456", PhoneBlockClient.Rating.G_FRAUD, null);
            fail();
        } catch (PhoneBlockClient.ApiException e) {
            assertTrue(e.isAuthError());
            assertEquals("HTTP 401: " + AUTH_REQUIRED, e.getMessage());
        }

        try {
            client(transport).rate(TOKEN, "+4930123456", PhoneBlockClient.Rating.G_FRAUD, null);
            fail();
        } catch (PhoneBlockClient.ApiException e) {
            assertEquals(400, e.getHttpCode());
            assertFalse(e.isAuthError());
        }
    }

    @Test
    public void removeFromLists() throws IOException {
        PhoneBlockReportTestSupport.FakeTransport transport
                = new PhoneBlockReportTestSupport.FakeTransport()
                .respond(204, null)
                .respond(404, NOT_IN_LIST)
                .respond(204, null);
        PhoneBlockClient client = new PhoneBlockClient(transport,
                "https://example.org/pb-test/api/", null);

        assertTrue(client.removeFromBlacklist(TOKEN, "+4930123456"));
        assertFalse(client.removeFromBlacklist(TOKEN, "+4930123456"));
        assertTrue(client.removeFromWhitelist(TOKEN, "+4930123456"));

        PhoneBlockReportTestSupport.Request request = transport.requests.get(0);
        assertEquals("DELETE", request.method);
        assertEquals("https://example.org/pb-test/api/blacklist/+4930123456", request.url);
        assertNull(request.body);
        assertNull(request.contentType);
        assertEquals("Bearer " + TOKEN, request.headers.get("Authorization"));
        assertEquals("https://example.org/pb-test/api/whitelist/+4930123456",
                transport.requests.get(2).url);
    }

    @Test
    public void removeFailsOnAuthError() throws IOException {
        PhoneBlockReportTestSupport.FakeTransport transport
                = new PhoneBlockReportTestSupport.FakeTransport().respond(401, AUTH_REQUIRED);
        try {
            client(transport).removeFromWhitelist(TOKEN, "+4930123456");
            fail();
        } catch (PhoneBlockClient.ApiException e) {
            assertTrue(e.isAuthError());
        }
    }

    @Test
    public void readOnlyTransportsDontSupportReports() {
        PhoneBlockClient.HttpTransport transport = (url, headers) -> {
            throw new AssertionError();
        };
        try {
            new PhoneBlockClient(transport, null, null).rate(TOKEN, "+4930123456",
                    PhoneBlockClient.Rating.G_FRAUD, null);
            fail();
        } catch (UnsupportedOperationException expected) {
            // ok
        } catch (IOException e) {
            fail(e.toString());
        }
    }

}
