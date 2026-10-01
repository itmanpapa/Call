package dummydomain.yetanothercallblocker.data.sources;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dummydomain.yetanothercallblocker.data.sources.GermanNumberNormalizer.NumberSpec;

import static dummydomain.yetanothercallblocker.data.sources.GermanNumberNormalizer.normalize;
import static dummydomain.yetanothercallblocker.data.sources.GermanNumberNormalizer.normalizePrefix;
import static dummydomain.yetanothercallblocker.data.sources.GermanNumberNormalizer.parseSpecs;
import static dummydomain.yetanothercallblocker.data.sources.GermanNumberNormalizer.rangeToPrefixes;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GermanNumberNormalizerTest {

    @Test
    public void nationalNumbers() {
        assertEquals("+4915112345678", normalize("015112345678"));
        assertEquals("+4915112345678", normalize("0151 123 456 78"));
        assertEquals("+4915112345678", normalize("0151/12345678"));
        assertEquals("+4915112345678", normalize("0151-1234-5678"));
        assertEquals("+493012345678", normalize("(030) 123 456 78"));
        assertEquals("+493012345678", normalize("030.12345678"));
        assertEquals("+493012345678", normalize("  030 12345678 "));
    }

    @Test
    public void internationalNumbers() {
        assertEquals("+493012345678", normalize("+49 30 12345678"));
        assertEquals("+493012345678", normalize("0049 30 12345678"));
        assertEquals("+493012345678", normalize("+49 (0)30 12345678"));
        assertEquals("+493012345678", normalize("+49 (0) 30 12345678"));
        assertEquals("+493012345678", normalize("+49030 12345678"));
        assertEquals("+375291234567", normalize("00375 29 1234567"));
        assertEquals("+375291234567", normalize("+375 29 123-45-67"));
        // Written without "+" as in some CSV exports
        assertEquals("+4915112345678", normalize("4915112345678"));
    }

    @Test
    public void invalidNumbers() {
        assertNull(normalize(null));
        assertNull(normalize(""));
        assertNull(normalize("   "));
        assertNull(normalize("unbekannt"));
        assertNull(normalize("0800-FLOWERS"));
        assertNull(normalize("110"));
        assertNull(normalize("0151"));
        assertNull(normalize("12345678"));           // no trunk zero: ambiguous
        assertNull(normalize("0151 1234567890123"));  // too long
        assertNull(normalize("++4930123456"));
        assertNull(normalize("+4912"));
        assertNull(normalize("0900 1234 5x"));        // wildcard is not an exact number
    }

    @Test
    public void prefixes() {
        assertEquals("+4990012345", normalizePrefix("0900 1234 5"));
        assertEquals("+49900", normalizePrefix("0900"));
        assertNull(normalizePrefix("09"));
        assertNull(normalizePrefix("+37"));
    }

    @Test
    public void wildcards() {
        assertEquals(specs(NumberSpec.prefix("+4990012345")), parseSpecs("0900 1234 5x", null));
        assertEquals(specs(NumberSpec.prefix("+4990012345")), parseSpecs("0900 1234 5X", null));
        assertEquals(specs(NumberSpec.prefix("+4990012345")), parseSpecs("09001234 5xx", null));
        assertEquals(specs(NumberSpec.prefix("+499001234")), parseSpecs("09001234*", null));
        assertEquals(specs(NumberSpec.prefix("+499001234")), parseSpecs("09001234...", null));
        assertEquals(specs(NumberSpec.prefix("+499001234")), parseSpecs("09001234…", null));
        assertEquals(specs(NumberSpec.prefix("+499001234")), parseSpecs("09001234##", null));

        List<String> errors = new ArrayList<>();
        assertTrue(parseSpecs("0900 12x 45", errors).isEmpty());
        assertEquals(1, errors.size());
    }

    @Test
    public void ranges() {
        assertEquals(specs(NumberSpec.prefix("+499001234")),
                parseSpecs("09001234000-09001234999", null));
        assertEquals(specs(NumberSpec.prefix("+499001234")),
                parseSpecs("09001234000 bis 09001234999", null));
        assertEquals(specs(NumberSpec.prefix("+4990012345")),
                parseSpecs("0900 1234 500 bis 599", null));
        assertEquals(specs(NumberSpec.prefix("+4990012345")),
                parseSpecs("0900 1234 500 – 599", null));
        assertEquals(specs(NumberSpec.prefix("+4990012342"), NumberSpec.prefix("+4990012343")),
                parseSpecs("09001234200-09001234399", null));
        assertEquals(specs(NumberSpec.exact("+4990012345"), NumberSpec.exact("+4990012346")),
                parseSpecs("090012345 bis 46", null));

        // A hyphen followed by an abbreviated bound is read as formatting, not a range
        assertEquals(specs(NumberSpec.exact("+49900123450059")),
                parseSpecs("0900123450-059", null));

        // Too large: two unrelated numbers joined by a hyphen
        List<String> errors = new ArrayList<>();
        assertTrue(parseSpecs("015112345678-016012345678", errors).isEmpty());
        assertEquals(1, errors.size());
    }

    @Test
    public void rangeToPrefixesIsExact() {
        assertEquals(Arrays.asList("12"), rangeToPrefixes("1200", "1299"));
        assertEquals(Arrays.asList("1234"), rangeToPrefixes("1234", "1234"));
        assertEquals(Arrays.asList("1205", "1206", "1207", "1208", "1209", "121", "1220", "1221"),
                rangeToPrefixes("1205", "1221"));
        assertEquals(Arrays.asList("1", "2", "3"), rangeToPrefixes("100", "399"));
        // Needs more than MAX_RANGE_PREFIXES prefixes
        assertNull(rangeToPrefixes("1000001", "8999998"));
    }

    @Test
    public void severalNumbersInOneCell() {
        assertEquals(specs(NumberSpec.exact("+4915112345678"), NumberSpec.exact("+4916012345678")),
                parseSpecs("015112345678, 016012345678", null));
        assertEquals(specs(NumberSpec.exact("+4915112345678"), NumberSpec.exact("+4916012345678")),
                parseSpecs("015112345678\n016012345678", null));
        assertEquals(specs(NumberSpec.exact("+4915112345678"), NumberSpec.exact("+4916012345678")),
                parseSpecs("015112345678 und 016012345678", null));
        // Space-separated complete numbers
        assertEquals(specs(NumberSpec.exact("+4915112345678"), NumberSpec.exact("+4916012345678")),
                parseSpecs("015112345678 016012345678", null));
        // ...but spaces inside one number are kept together
        assertEquals(specs(NumberSpec.exact("+4915112345678")), parseSpecs("0151 12345678", null));
    }

    @Test
    public void invalidPartsAreReported() {
        List<String> errors = new ArrayList<>();
        List<NumberSpec> result = parseSpecs("015112345678, kaputt, 016012345678", errors);
        assertEquals(2, result.size());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("kaputt"));
    }

    private static List<NumberSpec> specs(NumberSpec... specs) {
        return Arrays.asList(specs);
    }

}
