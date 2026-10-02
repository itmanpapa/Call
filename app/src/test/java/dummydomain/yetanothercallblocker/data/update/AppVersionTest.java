package dummydomain.yetanothercallblocker.data.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AppVersionTest {

    private static int compare(String a, String b) {
        AppVersion va = AppVersion.parse(a);
        AppVersion vb = AppVersion.parse(b);
        assertNotNull(a, va);
        assertNotNull(b, vb);
        return Integer.signum(va.compareTo(vb));
    }

    @Test
    public void numericComparison() {
        assertEquals(1, compare("0.10.0", "0.9.0"));
        assertEquals(1, compare("0.9.10", "0.9.9"));
        assertEquals(-1, compare("0.11.0", "0.12.0"));
        assertEquals(1, compare("1.0.0", "0.99.99"));
        assertEquals(0, compare("0.11.0", "0.11.0"));
    }

    @Test
    public void vPrefixAndWhitespaceIgnored() {
        assertEquals(0, compare("v0.11.0", "0.11.0"));
        assertEquals(0, compare(" V0.11.0 ", "0.11.0"));
        assertEquals(1, compare("v0.12.0", "0.11.0"));
    }

    @Test
    public void missingPartsAreZero() {
        assertEquals(0, compare("1.2", "1.2.0"));
        assertEquals(0, compare("1", "1.0.0"));
        assertEquals(1, compare("1.2.0.1", "1.2"));
        assertEquals(AppVersion.parse("1.2"), AppVersion.parse("1.2.0"));
        assertEquals(AppVersion.parse("1.2").hashCode(), AppVersion.parse("1.2.0").hashCode());
    }

    @Test
    public void buildMarkersIgnored() {
        assertEquals(0, compare("0.11.0-debug", "0.11.0"));
        assertEquals(0, compare("0.11.0-dev", "v0.11.0"));
        assertEquals(0, compare("0.11.0-SNAPSHOT", "0.11.0"));
        assertEquals(0, compare("0.11.0+42", "0.11.0"));
        assertEquals(1, compare("0.12.0", "0.11.0-debug"));
        assertFalse(AppVersion.isNewer("v0.11.0", "0.11.0-debug"));
        assertTrue(AppVersion.isNewer("v0.11.1", "0.11.0-debug"));
    }

    @Test
    public void preReleaseOlderThanRelease() {
        assertEquals(-1, compare("0.12.0-beta1", "0.12.0"));
        assertEquals(1, compare("0.12.0-beta1", "0.11.0"));
        assertEquals(1, compare("0.12.0-rc10", "0.12.0-rc9"));
        assertEquals(1, compare("0.12.0-rc1", "0.12.0-beta2"));
        assertEquals(0, compare("0.12.0-beta1-debug", "0.12.0-beta1"));
        assertTrue(AppVersion.parse("0.12.0-beta1").isPreRelease());
        assertFalse(AppVersion.parse("0.12.0-debug").isPreRelease());
    }

    @Test
    public void displayName() {
        assertEquals("0.11.0", AppVersion.parse("v0.11.0").getDisplayName());
        assertEquals("0.11.0", AppVersion.parse("0.11.0-debug").getDisplayName());
        assertEquals("1.0.0", AppVersion.parse("v1").getDisplayName());
        assertEquals("0.12.0-beta1", AppVersion.parse("v0.12.0-Beta1").getDisplayName());
        assertEquals("v0.11.0", AppVersion.parse(" v0.11.0").getOriginal());
    }

    @Test
    public void invalidVersions() {
        assertNull(AppVersion.parse(null));
        assertNull(AppVersion.parse(""));
        assertNull(AppVersion.parse("v"));
        assertNull(AppVersion.parse("latest"));
        assertNull(AppVersion.parse("99999999999999.1"));
        assertFalse(AppVersion.isNewer("latest", "0.11.0"));
        assertFalse(AppVersion.isNewer("0.12.0", null));
    }

    @Test
    public void naturalCompare() {
        assertEquals(0, AppVersion.compareNatural("rc01", "rc1"));
        assertTrue(AppVersion.compareNatural("a", "b") < 0);
        assertTrue(AppVersion.compareNatural("beta", "beta1") < 0);
    }

}
