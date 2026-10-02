package dummydomain.yetanothercallblocker.data.sources;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The reporting calls over real HTTP (OkHttp against a local server). */
public class OkHttpTransportReportTest {

    private MockWebServer server;
    private PhoneBlockClient client;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/phoneblock/api").toString();
        client = new PhoneBlockClient(new OkHttpTransport(OkHttpClient::new), baseUrl,
                "YACB-test/1.0");
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void ratePostsJson() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/plain;charset=UTF-8")
                .setBody(PhoneBlockReportTestSupport.RATE_OK));

        client.rate("key", "+4930123456", PhoneBlockClient.Rating.D_POLL, "Umfrage ü");

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/phoneblock/api/rate", request.getPath());
        assertEquals("Bearer key", request.getHeader("Authorization"));
        assertEquals("YACB-test/1.0", request.getHeader("User-Agent"));
        assertTrue(request.getHeader("Content-Type").startsWith("application/json"));
        assertTrue(request.getHeader("Content-Type").toLowerCase().contains("charset=utf-8"));
        assertEquals("{\"phone\":\"+4930123456\",\"rating\":\"D_POLL\",\"comment\":\"Umfrage ü\"}",
                request.getBody().readUtf8());
    }

    @Test
    public void deleteKeepsThePlusInThePath() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        server.enqueue(new MockResponse().setResponseCode(404)
                .setBody(PhoneBlockReportTestSupport.NOT_IN_LIST));

        assertTrue(client.removeFromBlacklist("key", "+4930123456"));
        assertFalse(client.removeFromWhitelist("key", "+4930123456"));

        RecordedRequest request = server.takeRequest();
        assertEquals("DELETE", request.getMethod());
        assertEquals("/phoneblock/api/blacklist/+4930123456", request.getPath());
        assertEquals(0, request.getBodySize());
        assertEquals("/phoneblock/api/whitelist/+4930123456", server.takeRequest().getPath());
    }

    @Test
    public void errorBodyIsReported() {
        server.enqueue(new MockResponse().setResponseCode(401)
                .setBody(PhoneBlockReportTestSupport.AUTH_REQUIRED));
        try {
            client.rate("key", "+4930123456", PhoneBlockClient.Rating.G_FRAUD, null);
            fail();
        } catch (PhoneBlockClient.ApiException e) {
            assertTrue(e.isAuthError());
            assertEquals("HTTP 401: " + PhoneBlockReportTestSupport.AUTH_REQUIRED, e.getMessage());
        } catch (IOException e) {
            fail(e.toString());
        }
    }

}
