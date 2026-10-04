package dummydomain.yetanothercallblocker.data.donation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BitcoinAddressTest {

    private static final String ADDRESS = "bc1q5y996877x4svlethgpzsp7yjx2ecsr59mlv7n7";

    @Test
    public void projectAddressIsValid() {
        assertEquals(ADDRESS, BitcoinAddress.normalize(ADDRESS));
        assertEquals(ADDRESS, BitcoinAddress.normalize("  " + ADDRESS + "\n"));
    }

    @Test
    public void bip173AndBip350Vectors() {
        assertEquals("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4",
                BitcoinAddress.normalize("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4"));
        // taproot (bech32m)
        assertEquals("bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0",
                BitcoinAddress.normalize(
                        "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"));
    }

    @Test
    public void invalidAddressesRejected() {
        assertNull(BitcoinAddress.normalize(null));
        assertNull(BitcoinAddress.normalize(""));
        // one character changed
        assertNull(BitcoinAddress.normalize("bc1q5y996877x4svlethgpzsp7yjx2ecsr59mlv7n8"));
        // mixed case
        assertNull(BitcoinAddress.normalize("bc1Q5y996877x4svlethgpzsp7yjx2ecsr59mlv7n7"));
        // testnet
        assertNull(BitcoinAddress.normalize("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"));
        // version 0 with bech32m checksum
        assertNull(BitcoinAddress.normalize("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kemeawh"));
        // legacy addresses are not supported
        assertNull(BitcoinAddress.normalize("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"));
    }

    @Test
    public void uri() {
        assertEquals("bitcoin:" + ADDRESS, BitcoinAddress.toUri(ADDRESS));
    }

}
