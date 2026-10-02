package dummydomain.yetanothercallblocker.data.update;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

public class ApkDownloaderTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private MockWebServer server;
    private File dir;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        dir = new File(temp.getRoot(), "updates");
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    private static byte[] apkBytes(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) bytes[i] = (byte) (i * 31 + 7);
        bytes[0] = 'P';
        bytes[1] = 'K';
        return bytes;
    }

    private ReleaseInfo release(String version, long size) {
        String name = "callguard-v" + version + ".apk";
        return new ReleaseInfo("v" + version, version, null, "", null, name,
                server.url("/download/v" + version + "/" + name).toString(), size, 0);
    }

    private ApkDownloader downloader() {
        return new ApkDownloader(OkHttpClient::new, dir, "CallGuard-test");
    }

    @Test
    public void downloadsWithProgressAndDeletesOldFiles() throws Exception {
        byte[] apk = apkBytes(200_000);
        server.enqueue(new MockResponse().setBody(new Buffer().write(apk)));

        assertTrue(dir.mkdirs());
        File old = new File(dir, "callguard-v0.11.0.apk");
        try (FileOutputStream out = new FileOutputStream(old)) {
            out.write(1);
        }
        File oldPart = new File(dir, "callguard-v0.12.0.apk.part");
        assertTrue(oldPart.createNewFile());

        List<long[]> progress = new ArrayList<>();
        ReleaseInfo release = release("0.12.0", apk.length);
        File file = downloader().download(release,
                (downloaded, total) -> progress.add(new long[]{downloaded, total}), () -> false);

        assertEquals(new File(dir, "callguard-v0.12.0.apk"), file);
        assertArrayEquals(apk, Files.readAllBytes(file.toPath()));
        assertFalse(old.exists());
        assertFalse(oldPart.exists());
        assertEquals(1, dir.listFiles().length);

        assertTrue(progress.size() >= 2);
        assertEquals(0, progress.get(0)[0]);
        long[] last = progress.get(progress.size() - 1);
        assertEquals(apk.length, last[0]);
        assertEquals(apk.length, last[1]);

        RecordedRequest request = server.takeRequest();
        assertEquals("/download/v0.12.0/callguard-v0.12.0.apk", request.getPath());
        assertEquals("CallGuard-test", request.getHeader("User-Agent"));

        // a complete download is reused
        assertEquals(file, downloader().findDownloaded(release));
        assertEquals(file, downloader().download(release, null, null));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void followsRedirect() throws Exception {
        byte[] apk = apkBytes(1000);
        server.enqueue(new MockResponse().setResponseCode(302)
                .setHeader("Location", server.url("/objects/blob").toString()));
        server.enqueue(new MockResponse().setBody(new Buffer().write(apk)));

        File file = downloader().download(release("0.12.0", apk.length), null, null);
        assertArrayEquals(apk, Files.readAllBytes(file.toPath()));
    }

    @Test
    public void unknownSize() throws Exception {
        byte[] apk = apkBytes(5000);
        server.enqueue(new MockResponse().setBody(new Buffer().write(apk)));

        File file = downloader().download(release("0.12.0", -1), null, null);
        assertEquals(5000, file.length());
    }

    @Test
    public void contentLengthMismatch() {
        server.enqueue(new MockResponse().setBody(new Buffer().write(apkBytes(1000))));
        try {
            downloader().download(release("0.12.0", 2000), null, null);
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("Unexpected size"));
        }
        assertEquals(0, dir.listFiles().length);
    }

    @Test
    public void truncatedDownloadRemoved() {
        // the server announces the expected size but closes the connection early
        server.enqueue(new MockResponse().setBody(new Buffer().write(apkBytes(1000)))
                .setHeader("Content-Length", "2000")
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        try {
            downloader().download(release("0.12.0", 2000), null, null);
            fail();
        } catch (IOException e) {
            // expected
        }
        assertEquals(0, dir.listFiles().length);
    }

    @Test
    public void httpError() {
        server.enqueue(new MockResponse().setResponseCode(404));
        try {
            downloader().download(release("0.12.0", 100), null, null);
            fail();
        } catch (IOException e) {
            assertEquals("HTTP 404", e.getMessage());
        }
        assertEquals(0, dir.listFiles().length);
    }

    @Test
    public void cancelled() {
        server.enqueue(new MockResponse().setBody(new Buffer().write(apkBytes(300_000))));
        try {
            downloader().download(release("0.12.0", 300_000), null, () -> true);
            fail();
        } catch (ApkDownloader.CancelledException e) {
            // expected
        } catch (IOException e) {
            fail(e.toString());
        }
        assertEquals(0, dir.listFiles().length);
    }

    @Test
    public void tooBig() {
        try {
            downloader().download(release("0.12.0", ApkDownloader.MAX_SIZE + 1), null, null);
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().startsWith("The APK is too big"));
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void noApk() {
        ReleaseInfo release = new ReleaseInfo("v0.12.0", "0.12.0", null, "", null,
                null, null, -1, 0);
        try {
            downloader().download(release, null, null);
            fail();
        } catch (IOException e) {
            assertEquals("The release has no APK", e.getMessage());
        }
        assertNull(downloader().findDownloaded(release));
    }

    @Test
    public void partialFileNotReused() throws IOException {
        assertTrue(dir.mkdirs());
        File file = new File(dir, "callguard-v0.12.0.apk");
        Files.write(file.toPath(), new byte[10]);
        assertNull(downloader().findDownloaded(release("0.12.0", 20)));
    }

    @Test
    public void clear() throws IOException {
        assertTrue(dir.mkdirs());
        assertTrue(new File(dir, "a.apk").createNewFile());
        downloader().clear();
        assertEquals(0, dir.listFiles().length);
    }

    @Test
    public void safeFileNames() {
        assertEquals("callguard-v0.12.0.apk", ApkDownloader.fileName(
                new ReleaseInfo("v0.12.0", "0.12.0", null, "", null, null, "u", -1, 0)));
        assertEquals("_.._evil.apk", ApkDownloader.fileName(
                new ReleaseInfo("v1", "1.0.0", null, "", null, "../evil", "u", -1, 0)));
        assertEquals(Arrays.asList("x_y.apk"), Arrays.asList(ApkDownloader.fileName(
                new ReleaseInfo("v1", "1.0.0", null, "", null, "x y.apk", "u", -1, 0))));
    }

}
