package dummydomain.yetanothercallblocker.data.sources;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OkHttpListDownloaderTest {

    private MockWebServer server;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private OkHttpListDownloader downloader(long maxSize) {
        return new OkHttpListDownloader(OkHttpClient::new, maxSize);
    }

    @Test
    public void downloadWithValidators() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("015112345678\n")
                .setHeader("ETag", "\"v1\"")
                .setHeader("Last-Modified", "Tue, 01 Oct 2026 10:00:00 GMT"));

        RemoteListDownloader.Response response = downloader(1000)
                .download(server.url("/list.txt").toString(), null, null);

        assertFalse(response.isNotModified());
        assertArrayEquals("015112345678\n".getBytes(StandardCharsets.UTF_8), response.getBody());
        assertEquals("\"v1\"", response.getEtag());
        assertEquals("Tue, 01 Oct 2026 10:00:00 GMT", response.getLastModified());

        RecordedRequest request = server.takeRequest();
        assertNull(request.getHeader("If-None-Match"));
        assertNull(request.getHeader("If-Modified-Since"));
        assertTrue(request.getHeader("User-Agent").contains("YetAnotherCallBlocker"));
    }

    @Test
    public void notModified() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(304));

        RemoteListDownloader.Response response = downloader(1000).download(
                server.url("/list.txt").toString(), "\"v1\"", "Tue, 01 Oct 2026 10:00:00 GMT");

        assertTrue(response.isNotModified());
        assertNull(response.getBody());
        // the old validators are kept if the 304 response doesn't repeat them
        assertEquals("\"v1\"", response.getEtag());
        assertEquals("Tue, 01 Oct 2026 10:00:00 GMT", response.getLastModified());

        RecordedRequest request = server.takeRequest();
        assertEquals("\"v1\"", request.getHeader("If-None-Match"));
        assertEquals("Tue, 01 Oct 2026 10:00:00 GMT", request.getHeader("If-Modified-Since"));
    }

    @Test
    public void httpError() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("Not Found"));
        try {
            downloader(1000).download(server.url("/missing").toString(), null, null);
            fail();
        } catch (RemoteListDownloader.HttpStatusException e) {
            assertEquals(404, e.getCode());
            assertTrue(e.getMessage(), e.getMessage().startsWith("HTTP 404"));
        } catch (IOException e) {
            fail(e.toString());
        }
    }

    @Test
    public void tooLargeByContentLength() {
        server.enqueue(new MockResponse().setBody(new String(new char[2000]).replace('\0', '1')));
        try {
            downloader(1000).download(server.url("/big").toString(), null, null);
            fail();
        } catch (RemoteListDownloader.TooLargeException e) {
            assertEquals(1000, e.getLimit());
        } catch (IOException e) {
            fail(e.toString());
        }
    }

    @Test
    public void tooLargeWhileReadingChunkedBody() {
        byte[] data = new byte[5000];
        Arrays.fill(data, (byte) '1');
        server.enqueue(new MockResponse().setChunkedBody(new Buffer().write(data), 512));
        try {
            downloader(1000).download(server.url("/big").toString(), null, null);
            fail();
        } catch (RemoteListDownloader.TooLargeException e) {
            // ok
        } catch (IOException e) {
            fail(e.toString());
        }
    }

    @Test
    public void invalidUrl() {
        try {
            downloader(1000).download("ftp://example.org/x", null, null);
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().startsWith("Invalid URL"));
        }
    }

}
