package dummydomain.yetanothercallblocker.data.update;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdateSecurityTest {

    @Test
    public void allowedDownloadUrls() {
        assertTrue(UpdateSecurity.isAllowedDownloadUrl(
                "https://github.com/itmanpapa/Call/releases/download/v0.12.0/callguard-v0.12.0.apk"));
        assertTrue(UpdateSecurity.isAllowedDownloadUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/1?x=y"));
        assertTrue(UpdateSecurity.isAllowedDownloadUrl(
                "https://release-assets.githubusercontent.com/github-production-release-asset/1"));
        assertTrue(UpdateSecurity.isAllowedDownloadUrl("HTTPS://GitHub.com:443/a.apk"));
    }

    @Test
    public void refusedDownloadUrls() {
        assertFalse(UpdateSecurity.isAllowedDownloadUrl(null));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl(""));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl("http://github.com/a.apk"));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl("ftp://github.com/a.apk"));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl("file:///sdcard/a.apk"));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl("https://evil.com/a.apk"));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl("https://github.com.evil.com/a.apk"));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl("https://evilgithub.com/a.apk"));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl("https://raw.githubusercontent.com/a.apk"));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl("https://github.com@evil.com/a.apk"));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl("https://user:pw@github.com/a.apk"));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl("https://github.com:8443/a.apk"));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl("https://evil.com#@github.com/a.apk"));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl("https://evil.com\\@github.com/a.apk"));
        assertFalse(UpdateSecurity.isAllowedDownloadUrl("not a url"));
    }

    @Test
    public void pageUrls() {
        assertTrue(UpdateSecurity.isAllowedPageUrl(
                "https://github.com/itmanpapa/Call/releases/tag/v0.12.0"));
        assertFalse(UpdateSecurity.isAllowedPageUrl("javascript:alert(1)"));
        assertFalse(UpdateSecurity.isAllowedPageUrl("intent://x#Intent;end"));
        assertFalse(UpdateSecurity.isAllowedPageUrl("http://github.com/x"));
        assertFalse(UpdateSecurity.isAllowedPageUrl("https://objects.githubusercontent.com/x"));
    }

    @Test
    public void sha256Hex() {
        // SHA-256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateSecurity.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    public void sameSignerMatches() {
        assertTrue(UpdateSecurity.signaturesMatch(list("a"), list("a"), null));
        assertTrue(UpdateSecurity.signaturesMatch(list("a", "b"), list("b", "a"), null));
    }

    @Test
    public void differentSignerRefused() {
        assertFalse(UpdateSecurity.signaturesMatch(list("a"), list("b"), null));
        assertFalse(UpdateSecurity.signaturesMatch(list("a"), list("b"), list("b")));
        // multiple signers must all match
        assertFalse(UpdateSecurity.signaturesMatch(list("a", "b"), list("a"), null));
        assertFalse(UpdateSecurity.signaturesMatch(list("a"), list("a", "evil"), null));
        // the history is used only for single-signer apps
        assertFalse(UpdateSecurity.signaturesMatch(list("a", "b"), list("c"), list("a", "b", "c")));
    }

    @Test
    public void rotatedKeyMatchesViaHistory() {
        assertTrue(UpdateSecurity.signaturesMatch(list("old"), list("new"), list("old", "new")));
    }

    @Test
    public void missingCertificatesRefused() {
        assertFalse(UpdateSecurity.signaturesMatch(null, list("a"), null));
        assertFalse(UpdateSecurity.signaturesMatch(list("a"), null, null));
        assertFalse(UpdateSecurity.signaturesMatch(Collections.emptyList(),
                Collections.emptyList(), null));
        assertFalse(UpdateSecurity.signaturesMatch(list("a"), Collections.emptyList(),
                list("a")));
        assertFalse(UpdateSecurity.signaturesMatch(list(""), list(""), null));
        assertFalse(UpdateSecurity.signaturesMatch(Arrays.asList((String) null),
                Arrays.asList((String) null), null));
    }

    private static List<String> list(String... values) {
        return Arrays.asList(values);
    }

}
