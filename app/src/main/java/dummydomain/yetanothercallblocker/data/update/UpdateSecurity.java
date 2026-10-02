package dummydomain.yetanothercallblocker.data.update;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Security rules of the in-app update: which URLs an APK may be downloaded from and
 * whether the signing certificates of a downloaded APK match the installed app.
 * Plain Java (the certificates are passed as encoded bytes or their SHA-256 digests).
 */
public final class UpdateSecurity {

    /**
     * Hosts the APK may come from: {@code github.com} (the {@code browser_download_url}
     * of a release asset) and the GitHub storage hosts it redirects to.
     */
    static final Set<String> DOWNLOAD_HOSTS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("github.com", "objects.githubusercontent.com",
                    "release-assets.githubusercontent.com")));

    /** Host of the release page opened in the browser. */
    static final String PAGE_HOST = "github.com";

    private UpdateSecurity() {
    }

    /**
     * @return true if an APK may be downloaded from the URL (also checked for every
     * redirect): HTTPS to one of {@link #DOWNLOAD_HOSTS}, default port, no user info
     */
    public static boolean isAllowedDownloadUrl(String url) {
        return isHttpsUrlOnHosts(url, DOWNLOAD_HOSTS);
    }

    /** @return true if the URL may be opened as the release page (HTTPS to github.com) */
    public static boolean isAllowedPageUrl(String url) {
        return isHttpsUrlOnHosts(url, Collections.singleton(PAGE_HOST));
    }

    private static boolean isHttpsUrlOnHosts(String url, Set<String> hosts) {
        if (url == null) return false;
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
        if (uri.getRawUserInfo() != null) return false;
        if (uri.getPort() != -1 && uri.getPort() != 443) return false;
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return hosts.contains(host);
    }

    /** @return the SHA-256 digest of the encoded certificate as lowercase hex */
    public static String sha256Hex(byte[] encodedCertificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(encodedCertificate);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Decides whether an APK may replace the installed app, comparing certificate digests
     * (see {@link #sha256Hex}).
     *
     * <p>Accepted if the APK's current signers equal the installed app's current signers,
     * or, for a single-signer app and APK, if the APK's certificate history (APK Signature
     * Scheme v3 key rotation) contains the installed certificate. Empty or missing
     * certificates are never accepted.</p>
     *
     * @param installedSigners current signers of the installed app
     * @param apkSigners       current signers of the downloaded APK
     * @param apkHistory       certificate history of the downloaded APK (oldest first),
     *                         may be null or empty
     */
    public static boolean signaturesMatch(Collection<String> installedSigners,
                                          Collection<String> apkSigners,
                                          List<String> apkHistory) {
        if (installedSigners == null || apkSigners == null) return false;
        Set<String> installed = new HashSet<>(installedSigners);
        Set<String> apk = new HashSet<>(apkSigners);
        if (installed.isEmpty() || apk.isEmpty()) return false;
        if (installed.contains(null) || apk.contains(null)) return false;
        if (installed.contains("") || apk.contains("")) return false;

        if (installed.equals(apk)) return true;

        return installed.size() == 1 && apk.size() == 1
                && apkHistory != null && apkHistory.contains(installed.iterator().next());
    }

}
