package dummydomain.yetanothercallblocker.data.donation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class EvmAddressTest {

    @Test
    public void projectAddressIsValid() {
        // the EIP-55 checksum of this address was verified when it was added
        String address = "0x2F31dbb7b6068838594385d85373ECd2275F228A";
        assertEquals(address, EvmAddress.normalize(" " + address + " "));
    }

    @Test
    public void invalidAddressesRejected() {
        assertNull(EvmAddress.normalize(null));
        assertNull(EvmAddress.normalize("2F31dbb7b6068838594385d85373ECd2275F228A"));
        assertNull(EvmAddress.normalize("0x2F31dbb7b6068838594385d85373ECd2275F228"));
        assertNull(EvmAddress.normalize("0x2F31dbb7b6068838594385d85373ECd2275F228G"));
    }

}
