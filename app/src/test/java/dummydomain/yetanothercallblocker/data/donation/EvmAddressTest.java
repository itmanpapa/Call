package dummydomain.yetanothercallblocker.data.donation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EvmAddressTest {

    /** The example address of EIP-55. */
    private static final String ADDRESS = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed";

    @Test
    public void validAddressIsAccepted() {
        assertEquals(ADDRESS, EvmAddress.normalize(ADDRESS));
        assertEquals(ADDRESS, EvmAddress.normalize(" " + ADDRESS + " "));
    }

    @Test
    public void invalidAddressesRejected() {
        assertNull(EvmAddress.normalize(null));
        assertNull(EvmAddress.normalize(""));
        // without the "0x" prefix
        assertNull(EvmAddress.normalize("5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"));
        // too short
        assertNull(EvmAddress.normalize("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAe"));
        // not a hexadecimal character
        assertNull(EvmAddress.normalize("0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAeG"));
    }

}
