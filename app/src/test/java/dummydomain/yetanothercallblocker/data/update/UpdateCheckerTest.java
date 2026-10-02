package dummydomain.yetanothercallblocker.data.update;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UpdateCheckerTest {

    static class MemoryStore implements UpdateChecker.Store {
        long lastCheckTime;
        String latestVersion;
        String notifiedVersion;

        @Override public long getLastCheckTime() { return lastCheckTime; }
        @Override public void setLastCheckTime(long time) { lastCheckTime = time; }
        @Override public String getLatestVersion() { return latestVersion; }
        @Override public void setLatestVersion(String version) { latestVersion = version; }
        @Override public String getNotifiedVersion() { return notifiedVersion; }
        @Override public void setNotifiedVersion(String version) { notifiedVersion = version; }
    }

    private static final long NOW = 1_790_900_000_000L;

    private final MemoryStore store = new MemoryStore();

    private UpdateChecker checker(GitHubReleaseClientTest.FakeTransport transport,
                                  String current) {
        return new UpdateChecker(new GitHubReleaseClient(transport, "test"), store, current,
                () -> NOW);
    }

    @Test
    public void newerReleaseFound() throws IOException {
        GitHubReleaseClientTest.FakeTransport transport = new GitHubReleaseClientTest.FakeTransport()
                .respond(200, GitHubReleaseClientTest.releaseJson());
        UpdateChecker checker = checker(transport, "0.11.0");

        UpdateChecker.Result result = checker.check();

        assertTrue(result.isUpdateAvailable());
        assertTrue(result.isNotificationDue());
        assertEquals("0.12.0", result.getRelease().getVersion());
        assertEquals(NOW, store.lastCheckTime);
        assertEquals("0.12.0", store.latestVersion);
        assertEquals("0.12.0", UpdateChecker.getKnownUpdate(store, "0.11.0"));

        checker.markNotified("0.12.0");
        assertEquals("0.12.0", store.notifiedVersion);
    }

    @Test
    public void notifiedOnlyOncePerVersion() throws IOException {
        store.notifiedVersion = "0.12.0";
        GitHubReleaseClientTest.FakeTransport transport = new GitHubReleaseClientTest.FakeTransport()
                .respond(200, GitHubReleaseClientTest.releaseJson());

        UpdateChecker.Result result = checker(transport, "0.11.0").check();

        assertTrue(result.isUpdateAvailable());
        assertFalse(result.isNotificationDue());
    }

    @Test
    public void upToDate() throws IOException {
        GitHubReleaseClientTest.FakeTransport transport = new GitHubReleaseClientTest.FakeTransport()
                .respond(200, GitHubReleaseClientTest.releaseJson());

        UpdateChecker.Result result = checker(transport, "0.12.0-debug").check();

        assertFalse(result.isUpdateAvailable());
        assertFalse(result.isNotificationDue());
        assertEquals("0.12.0", store.latestVersion);
        assertNull(UpdateChecker.getKnownUpdate(store, "0.12.0-debug"));
    }

    @Test
    public void noRelease() throws IOException {
        store.latestVersion = "0.12.0";
        GitHubReleaseClientTest.FakeTransport transport = new GitHubReleaseClientTest.FakeTransport()
                .respond(404, "{\"message\":\"Not Found\"}");

        UpdateChecker.Result result = checker(transport, "0.11.0").check();

        assertNull(result.getRelease());
        assertFalse(result.isUpdateAvailable());
        assertNull(store.latestVersion);
        assertEquals(NOW, store.lastCheckTime);
    }

    @Test
    public void failureKeepsState() {
        store.lastCheckTime = 5;
        store.latestVersion = "0.12.0";
        GitHubReleaseClientTest.FakeTransport transport = new GitHubReleaseClientTest.FakeTransport();
        transport.failure = new IOException("offline");
        try {
            checker(transport, "0.11.0").check();
            fail();
        } catch (IOException e) {
            assertEquals(5, store.lastCheckTime);
            assertEquals("0.12.0", store.latestVersion);
        }
    }

    @Test
    public void knownUpdateAfterAppWasUpdated() {
        store.latestVersion = "0.12.0";
        assertEquals("0.12.0", UpdateChecker.getKnownUpdate(store, "0.11.0"));
        assertNull(UpdateChecker.getKnownUpdate(store, "0.12.0"));
        assertNull(UpdateChecker.getKnownUpdate(store, "0.13.0"));
        store.latestVersion = null;
        assertNull(UpdateChecker.getKnownUpdate(store, "0.11.0"));
    }

}
