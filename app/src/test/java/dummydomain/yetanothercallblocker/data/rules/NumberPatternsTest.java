package dummydomain.yetanothercallblocker.data.rules;

import org.junit.Test;

import java.util.Arrays;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NumberPatternsTest {

    private static boolean matches(String patterns, String number) {
        Pattern pattern = NumberPatterns.compile(patterns);
        return pattern != null && pattern.matcher(number).matches();
    }

    @Test
    public void splitNormalizesAndDeduplicates() {
        assertEquals(Arrays.asList("+44*", "0900*", "+49137#"),
                NumberPatterns.split(" 0044 * ; 0900-*,\n+49 (137) ?, +44*,, "));
    }

    @Test
    public void canonicalJoinsWithComma() {
        assertEquals("+44*, 0900*", NumberPatterns.canonical("0044*;0900*"));
        assertEquals("", NumberPatterns.canonical(null));
    }

    @Test
    public void validation() {
        assertTrue(NumberPatterns.isValidList("+44*, 0900*"));
        assertTrue(NumberPatterns.isValidList("*"));
        assertTrue(NumberPatterns.isValidList("0151 1234567"));
        assertFalse(NumberPatterns.isValidList(""));
        assertFalse(NumberPatterns.isValidList(" , ;"));
        assertFalse(NumberPatterns.isValidList("0900*, abc"));
        assertFalse(NumberPatterns.isValidList("49+*"));
    }

    @Test
    public void starMatchesAnyDigitsIncludingNone() {
        assertTrue(matches("0900*", "0900123456"));
        assertTrue(matches("0900*", "0900"));
        assertFalse(matches("0900*", "0901123456"));
        assertFalse(matches("0900*", "+49900123"));
    }

    @Test
    public void leadingStarMatchesInternationalNumbers() {
        assertTrue(matches("*", "+447700900123"));
        assertTrue(matches("*", "030123"));
        assertTrue(matches("*1234", "+491511234"));
        assertTrue(matches("*1234", "01511234"));
        assertFalse(matches("*1234", "+4915112345"));
        assertFalse(matches("0*", "+4930"));
    }

    @Test
    public void hashMatchesExactlyOneDigit() {
        assertTrue(matches("0137#", "01371"));
        assertFalse(matches("0137#", "0137"));
        assertFalse(matches("0137#", "013712"));
        assertTrue(matches("0137?*", "013712345"));
    }

    @Test
    public void withoutWildcardsTheWholeNumberMustMatch() {
        assertTrue(matches("030123456", "030123456"));
        assertFalse(matches("030123456", "0301234567"));
        assertFalse(matches("030123456", "1030123456"));
    }

    @Test
    public void plusIsLiteral() {
        assertTrue(matches("+44*", "+447700900123"));
        assertFalse(matches("+44*", "447700900123"));
        assertTrue(matches("0044*", "+447700900123"));
    }

    @Test
    public void severalPatterns() {
        assertTrue(matches("+44*, 0900*", "0900123"));
        assertTrue(matches("+44*, 0900*", "+44123"));
        assertFalse(matches("+44*, 0900*", "+49123"));
    }

    @Test
    public void invalidPatternsAreSkipped() {
        assertTrue(matches("x1, 0900*", "0900123"));
        assertNull(NumberPatterns.compile("abc"));
        assertNull(NumberPatterns.compile(""));
    }

}
