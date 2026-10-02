package dummydomain.yetanothercallblocker.data.update;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import dummydomain.yetanothercallblocker.data.sources.PhoneBlockClient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GitHubReleaseClientTest {

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
        try (InputStream in = GitHubReleaseClientTest.class.getResourceAsStream("/update/" + name)) {
            if (in == null) throw new IOException("Missing resource " + name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String releaseJson() throws IOException {
        return resource("github_release_latest.json");
    }

    @Test
    public void parsesRealisticRelease() throws IOException {
        ReleaseInfo release = GitHubReleaseClient.parseRelease(new StringReader(releaseJson()));

        assertEquals("v0.12.0", release.getTag());
        assertEquals("0.12.0", release.getVersion());
        assertEquals("v0.12.0", release.getName());
        assertEquals("https://github.com/itmanpapa/Call/releases/tag/v0.12.0",
                release.getPageUrl());
        // the APK, not the checksums listed before it
        assertEquals("callguard-v0.12.0.apk", release.getApkName());
        assertEquals("https://github.com/itmanpapa/Call/releases/download/v0.12.0/"
                + "callguard-v0.12.0.apk", release.getApkUrl());
        assertEquals(6815744L, release.getApkSize());
        assertTrue(release.hasApk());
        // 2026-10-01T18:07:44Z
        assertEquals(1790878064000L, release.getPublishedAt());

        assertTrue(release.getNotes().startsWith("## Что нового\r\n"));
        assertTrue(release.isNewerThan("0.11.0"));
        assertTrue(release.isNewerThan("0.11.0-debug"));
        assertFalse(release.isNewerThan("0.12.0"));
        assertFalse(release.isNewerThan("0.12.0-debug"));
        assertFalse(release.isNewerThan("0.13.0"));
    }

    @Test
    public void plainNotes() throws IOException {
        ReleaseInfo release = GitHubReleaseClient.parseRelease(new StringReader(releaseJson()));

        assertEquals("Что нового\n"
                + "\n"
                + "• Обновления приложения: проверка новых версий на GitHub"
                + " и установка из приложения\n"
                + "• Карточка звонящего: исправлено положение на Android 16"
                + " (TYPE_APPLICATION_OVERLAY)\n"
                + "• Подробнее: список изменений\n"
                + "\n"
                + "Full Changelog: https://github.com/itmanpapa/Call/compare/v0.11.0...v0.12.0",
                release.getPlainNotes());
    }

    @Test
    public void fetchLatestRelease() throws IOException {
        FakeTransport transport = new FakeTransport().respond(200, releaseJson());
        GitHubReleaseClient client = new GitHubReleaseClient(transport, "CallGuard/0.11.0");

        ReleaseInfo release = client.fetchLatestRelease();

        assertEquals("0.12.0", release.getVersion());
        assertEquals("https://api.github.com/repos/itmanpapa/Call/releases/latest",
                transport.urls.get(0));
        Map<String, String> headers = transport.headers.get(0);
        assertEquals("CallGuard/0.11.0", headers.get("User-Agent"));
        assertEquals("application/vnd.github+json", headers.get("Accept"));
    }

    @Test
    public void customBaseUrl() throws IOException {
        FakeTransport transport = new FakeTransport().respond(200, releaseJson());
        GitHubReleaseClient client = new GitHubReleaseClient(transport,
                "http://localhost:8080/", "owner", "repo", null);
        client.fetchLatestRelease();
        assertEquals("http://localhost:8080/repos/owner/repo/releases/latest",
                transport.urls.get(0));
        assertEquals("CallGuard", transport.headers.get(0).get("User-Agent"));
    }

    @Test
    public void noReleaseYet() throws IOException {
        FakeTransport transport = new FakeTransport().respond(404,
                "{\"message\":\"Not Found\",\"documentation_url\":\"https://docs.github.com/"
                        + "rest/releases/releases#get-the-latest-release\",\"status\":\"404\"}");
        assertNull(new GitHubReleaseClient(transport, "test").fetchLatestRelease());
    }

    @Test
    public void rateLimit() {
        FakeTransport transport = new FakeTransport().respond(403,
                "{\"message\":\"API rate limit exceeded for 203.0.113.7. (But here's the good"
                        + " news: Authenticated requests get a higher rate limit.)\","
                        + "\"documentation_url\":\"https://docs.github.com/rest/overview/"
                        + "resources-in-the-rest-api#rate-limiting\"}");
        try {
            new GitHubReleaseClient(transport, "test").fetchLatestRelease();
            fail();
        } catch (GitHubReleaseClient.ApiException e) {
            assertEquals(403, e.getHttpCode());
            assertTrue(e.isRateLimited());
            assertTrue(e.getMessage(), e.getMessage().startsWith("HTTP 403: {\"message\":\"API rate"));
        } catch (IOException e) {
            fail(e.toString());
        }
    }

    @Test
    public void serverError() {
        FakeTransport transport = new FakeTransport().respond(502, null);
        try {
            new GitHubReleaseClient(transport, "test").fetchLatestRelease();
            fail();
        } catch (GitHubReleaseClient.ApiException e) {
            assertEquals("HTTP 502", e.getMessage());
            assertFalse(e.isRateLimited());
        } catch (IOException e) {
            fail(e.toString());
        }
    }

    @Test
    public void networkError() {
        FakeTransport transport = new FakeTransport();
        IOException failure = new IOException("offline");
        transport.failure = failure;
        try {
            new GitHubReleaseClient(transport, "test").fetchLatestRelease();
            fail();
        } catch (IOException e) {
            assertSame(failure, e);
        }
    }

    @Test
    public void invalidJson() {
        FakeTransport transport = new FakeTransport().respond(200, "{\"tag_name\": ");
        try {
            new GitHubReleaseClient(transport, "test").fetchLatestRelease();
            fail();
        } catch (IOException e) {
            // expected
        }
    }

    @Test
    public void notAnObject() {
        try {
            GitHubReleaseClient.parseRelease(new StringReader("[]"));
            fail();
        } catch (IOException e) {
            assertEquals("Not a release object", e.getMessage());
        }
    }

    @Test
    public void missingTag() {
        try {
            GitHubReleaseClient.parseRelease(new StringReader("{\"name\":\"x\"}"));
            fail();
        } catch (IOException e) {
            assertEquals("Release without a tag", e.getMessage());
        }
    }

    @Test
    public void versionFromNameIfTagIsNotAVersion() throws IOException {
        ReleaseInfo release = GitHubReleaseClient.parseRelease(new StringReader(
                "{\"tag_name\":\"release-2026\",\"name\":\"CallGuard 0.13.1\"}"));
        assertEquals("release-2026", release.getTag());
        assertEquals("0.13.1", release.getVersion());
    }

    @Test
    public void unrecognizedTag() {
        try {
            GitHubReleaseClient.parseRelease(new StringReader(
                    "{\"tag_name\":\"latest\",\"name\":\"nightly\"}"));
            fail();
        } catch (IOException e) {
            assertTrue(e.getMessage().startsWith("Unrecognized release tag"));
        }
    }

    @Test
    public void releaseWithoutAssets() throws IOException {
        ReleaseInfo release = GitHubReleaseClient.parseRelease(new StringReader(
                "{\"tag_name\":\"v0.12.0\",\"body\":null,\"assets\":[],"
                        + "\"published_at\":null}"));
        assertFalse(release.hasApk());
        assertNull(release.getApkName());
        assertEquals(-1, release.getApkSize());
        assertEquals(0, release.getPublishedAt());
        assertEquals("", release.getNotes());
        assertEquals("", release.getPlainNotes());
    }

    @Test
    public void otherApkNameUsedAsFallback() throws IOException {
        ReleaseInfo release = GitHubReleaseClient.parseRelease(new StringReader(
                "{\"tag_name\":\"v0.12.0\",\"assets\":["
                        + "{\"name\":\"app-release.APK\",\"state\":\"uploaded\",\"size\":10,"
                        + "\"browser_download_url\":\"https://example.org/a.apk\"}]}"));
        assertEquals("app-release.APK", release.getApkName());
        assertEquals(10, release.getApkSize());
    }

    @Test
    public void fdroidFlavorApkNeverUsed() throws IOException {
        ReleaseInfo release = GitHubReleaseClient.parseRelease(new StringReader(
                "{\"tag_name\":\"v0.12.1\",\"assets\":["
                        + "{\"name\":\"callguard_fdroid-v0.12.1.apk\",\"state\":\"uploaded\","
                        + "\"size\":5,\"browser_download_url\":\"https://example.org/f.apk\"},"
                        + "{\"name\":\"callguard-v0.12.1.apk\",\"state\":\"uploaded\","
                        + "\"size\":7,\"browser_download_url\":\"https://example.org/g.apk\"}]}"));
        assertEquals("callguard-v0.12.1.apk", release.getApkName());
        assertEquals(7, release.getApkSize());

        ReleaseInfo onlyFdroid = GitHubReleaseClient.parseRelease(new StringReader(
                "{\"tag_name\":\"v0.12.1\",\"assets\":["
                        + "{\"name\":\"callguard_fdroid-v0.12.1.apk\",\"state\":\"uploaded\","
                        + "\"size\":5,\"browser_download_url\":\"https://example.org/f.apk\"}]}"));
        assertFalse(onlyFdroid.hasApk());
    }

    @Test
    public void incompleteUploadIgnored() throws IOException {
        ReleaseInfo release = GitHubReleaseClient.parseRelease(new StringReader(
                "{\"tag_name\":\"v0.12.0\",\"assets\":["
                        + "{\"name\":\"callguard-v0.12.0.apk\",\"state\":\"starter\",\"size\":0,"
                        + "\"browser_download_url\":\"https://example.org/a.apk\"}]}"));
        assertFalse(release.hasApk());
    }

    @Test
    public void invalidTime() {
        assertEquals(0, GitHubReleaseClient.parseTime("yesterday"));
        assertEquals(0, GitHubReleaseClient.parseTime(null));
        assertEquals(0L, GitHubReleaseClient.parseTime("1970-01-01T00:00:00Z"));
    }

}
