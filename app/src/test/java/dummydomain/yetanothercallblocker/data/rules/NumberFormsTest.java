package dummydomain.yetanothercallblocker.data.rules;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NumberFormsTest {

    @Test
    public void nationalNumberWithHomeCountry() {
        NumberForms forms = NumberForms.of("0900 123-456", null, "49");
        assertEquals("0900123456", forms.received);
        assertEquals("+49900123456", forms.international);
        assertEquals("0900123456", forms.national);
        assertEquals(Arrays.asList("0900123456", "+49900123456"), forms.all());
    }

    @Test
    public void internationalNumberOfHomeCountry() {
        NumberForms forms = NumberForms.of("+49 900 123456", "+49900123456", "49");
        assertEquals("+49900123456", forms.international);
        assertEquals("0900123456", forms.national);
        assertFalse(forms.isForeign("49"));
    }

    @Test
    public void doubleZeroPrefix() {
        NumberForms forms = NumberForms.of("0044 20 7946 0000", null, "49");
        assertEquals("+442079460000", forms.international);
        assertNull(forms.national);
        assertTrue(forms.isForeign("49"));
    }

    @Test
    public void normalizedNumberIsPreferred() {
        NumberForms forms = NumberForms.of("01511234567", "+491511234567", "49");
        assertEquals("+491511234567", forms.international);
        assertEquals("+491511234567", forms.key());
    }

    @Test
    public void unknownHomeCountry() {
        NumberForms forms = NumberForms.of("0900123", null, null);
        assertNull(forms.international);
        assertNull(forms.national);
        assertFalse(forms.isForeign(null));
        assertEquals("0900123", forms.key());

        NumberForms foreign = NumberForms.of("+44123", null, null);
        assertFalse(foreign.isForeign(null)); // can't tell without the home country
    }

    @Test
    public void shortNumbersAreNotForeign() {
        NumberForms forms = NumberForms.of("11833", null, "49");
        assertNull(forms.international);
        assertFalse(forms.isForeign("49"));
    }

    @Test
    public void callingCodesArePrefixFree() {
        // +1 home: a +1 number from another NANP country is not foreign
        assertFalse(NumberForms.of("+14165550123", null, "1").isForeign("1"));
        assertTrue(NumberForms.of("+447700900123", null, "1").isForeign("1"));
        // +43 (Austria) is foreign for +49, +4930 isn't
        assertTrue(NumberForms.of("+43123", null, "49").isForeign("49"));
        assertFalse(NumberForms.of("+4930123", null, "49").isForeign("49"));
    }

    @Test
    public void cleanKeepsOnlyLeadingPlus() {
        assertEquals("+4930", NumberForms.clean(" +49 (30) "));
        assertEquals("4930", NumberForms.clean("49+30"));
        assertEquals("", NumberForms.clean(null));
    }

    @Test
    public void countryCallingCodes() {
        assertEquals("49", CountryCallingCodes.forRegion("DE"));
        assertEquals("49", CountryCallingCodes.forRegion("de"));
        assertEquals("43", CountryCallingCodes.forRegion("AT"));
        assertEquals("1", CountryCallingCodes.forRegion("US"));
        assertEquals("7", CountryCallingCodes.forRegion("RU"));
        assertEquals("380", CountryCallingCodes.forRegion("UA"));
        assertEquals("44", CountryCallingCodes.forRegion("GB"));
        assertNull(CountryCallingCodes.forRegion("XX"));
        assertNull(CountryCallingCodes.forRegion(""));
        assertNull(CountryCallingCodes.forRegion(null));
    }

}
