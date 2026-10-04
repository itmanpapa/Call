package dummydomain.yetanothercallblocker.data.donation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TronAddressTest {

    private static final String ADDRESS = "TEnG8Lbaw2BBjFgaJtJNepkU4UJQW3iR9g";

    @Test
    public void projectAddressIsValid() {
        assertEquals(ADDRESS, TronAddress.normalize(" " + ADDRESS + "\n"));
    }

    @Test
    public void knownAddressIsValid() {
        // USDT contract on TRON
        assertEquals("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
                TronAddress.normalize("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"));
    }

    @Test
    public void invalidAddressesRejected() {
        assertNull(TronAddress.normalize(null));
        assertNull(TronAddress.normalize(""));
        // one character changed
        assertNull(TronAddress.normalize("TEnG8Lbaw2BBjFgaJtJNepkU4UJQW3iR9h"));
        // invalid base58 character (0)
        assertNull(TronAddress.normalize("TEnG8Lbaw2BBjFgaJtJNepkU4UJQW3iR90"));
        // a Bitcoin address
        assertNull(TronAddress.normalize("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"));
    }

}
