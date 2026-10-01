package dummydomain.yetanothercallblocker.data.sources;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import dummydomain.yetanothercallblocker.data.SourcesManager;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link BnetzaAutoUpdater} with the real {@link OkHttpListDownloader} against a local
 * server: the short link redirects to the page, the page supports conditional requests.
 */
public class BnetzaAutoUpdaterHttpTest {

    private static final String ETAG = "\"page-v1\"";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private MockWebServer server;
    private String page;
    private volatile boolean rejectUnknownAgents = true;

    @Before
    public void setUp() throws IOException {
        page = BnetzaMeasuresParserTest.readResource("bnetza_massnahmenliste_sample.html");

        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String agent = request.getHeader("User-Agent");
                if (rejectUnknownAgents && (agent == null || !agent.startsWith("Mozilla/5.0"))) {
                    return new MockResponse().setResponseCode(403);
                }
                switch (request.getPath()) {
                    case "/massnahmenliste":
                        return new MockResponse().setResponseCode(301)
                                .setHeader("Location", "/DE/Ma%C3%9Fnahmen/start_RM.html");
                    case "/DE/Ma%C3%9Fnahmen/start_RM.html":
                        if (ETAG.equals(request.getHeader("If-None-Match"))) {
                            return new MockResponse().setResponseCode(304).setHeader("ETag", ETAG);
                        }
                        return new MockResponse()
                                .setHeader("Content-Type", "text/html;charset=UTF-8")
                                .setHeader("ETag", ETAG)
                                .setBody(page);
                    default:
                        return new MockResponse().setResponseCode(404);
                }
            }
        });
        server.start();
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private BnetzaAutoUpdater updater(SourcesManager sourcesManager, String userAgent) {
        OkHttpListDownloader downloader = new OkHttpListDownloader(OkHttpClient::new,
                BnetzaAutoUpdater.MAX_PAGE_SIZE, userAgent);
        return new BnetzaAutoUpdater(sourcesManager, downloader, System::currentTimeMillis,
                Collections.singletonList(server.url("/massnahmenliste").toString()),
                server.url("/Liste%d.html").toString());
    }

    @Test
    public void followsRedirectAndUsesConditionalRequests() throws Exception {
        SourcesManager sourcesManager = new SourcesManager(
                new NumberListStore(folder.getRoot()),
                new BnetzaAutoUpdaterTest.MemoryPreferences(), Collections.emptyList());
        BnetzaAutoUpdater updater = updater(sourcesManager, OkHttpListDownloader.BROWSER_USER_AGENT);

        BnetzaAutoUpdater.Result first = updater.update(false);
        assertEquals(first.toString(), BnetzaAutoUpdater.Result.Status.UPDATED, first.getStatus());
        assertEquals(13, first.getEntryCount());

        RecordedRequest shortLink = server.takeRequest();
        assertEquals("/massnahmenliste", shortLink.getPath());
        assertTrue(shortLink.getHeader("User-Agent").startsWith("Mozilla/5.0"));
        RecordedRequest redirected = server.takeRequest();
        assertEquals("/DE/Ma%C3%9Fnahmen/start_RM.html", redirected.getPath());
        assertNull(redirected.getHeader("If-None-Match"));
        // two yearly lists (404)
        server.takeRequest();
        server.takeRequest();

        BnetzaAutoUpdater.Result second = updater.update(false);
        assertEquals(second.toString(), BnetzaAutoUpdater.Result.Status.NOT_MODIFIED,
                second.getStatus());
        assertEquals(13, second.getEntryCount());

        assertEquals(ETAG, server.takeRequest().getHeader("If-None-Match"));
        // the validator survives the redirect
        RecordedRequest conditional = server.takeRequest();
        assertEquals("/DE/Ma%C3%9Fnahmen/start_RM.html", conditional.getPath());
        assertEquals(ETAG, conditional.getHeader("If-None-Match"));
    }

    @Test
    public void unknownUserAgentIsRejected() {
        SourcesManager sourcesManager = new SourcesManager(
                new NumberListStore(folder.getRoot()),
                new BnetzaAutoUpdaterTest.MemoryPreferences(), Collections.emptyList());
        BnetzaAutoUpdater updater = updater(sourcesManager, null);

        BnetzaAutoUpdater.Result result = updater.update(false);

        assertEquals(BnetzaAutoUpdater.Result.Status.FAILED, result.getStatus());
        assertEquals("HTTP 403 Client Error", result.getError());
        assertEquals(Arrays.asList(), sourcesManager.getSources());
    }

}
