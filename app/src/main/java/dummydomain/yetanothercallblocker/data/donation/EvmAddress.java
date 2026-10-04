package dummydomain.yetanothercallblocker.data.donation;

/**
 * Checks the BNB Smart Chain (BEP20) donation address from res/values/donation.xml:
 * "0x" and 40 hex digits. The mixed-case EIP-55 checksum is not verified here (that
 * needs Keccak-256); the configured address is checked by EvmAddressTest instead.
 */
public final class EvmAddress {

    private EvmAddress() {}

    /** @return the trimmed address, or {@code null} if it is malformed */
    public static String normalize(String address) {
        if (address == null) return null;
        address = address.trim();
        if (address.length() != 42 || !address.startsWith("0x")) return null;
        for (int i = 2; i < address.length(); i++) {
            if (Character.digit(address.charAt(i), 16) < 0) return null;
        }
        return address;
    }

}
