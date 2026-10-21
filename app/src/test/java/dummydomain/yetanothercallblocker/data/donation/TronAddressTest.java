package dummydomain.yetanothercallblocker.data.donation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TronAddressTest {

    /** The example address of the USDT contract on TRON. */
    private static final String ADDRESS = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

    @Test
    public void validAddressIsAccepted() {
        assertEquals(ADDRESS, TronAddress.normalize(ADDRESS));
        assertEquals(ADDRESS, TronAddress.normalize(" " + ADDRESS + "\n"));
    }

    @Test
    public void invalidAddressesRejected() {
        assertNull(TronAddress.normalize(null));
        assertNull(TronAddress.normalize(""));
        // one character changed
        assertNull(TronAddress.normalize("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6u"));
        // invalid base58 character (0)
        assertNull(TronAddress.normalize("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj60"));
        // a Bitcoin address
        assertNull(TronAddress.normalize("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"));
    }

}
