package dummydomain.yetanothercallblocker.data.donation;

import java.util.Locale;

/**
 * Checks the Bitcoin donation address from res/values/donation.xml before it is shown,
 * so that a typo in the configuration hides the button instead of sending money nowhere.
 * Only native SegWit addresses (bech32 / bech32m, "bc1...") are accepted.
 */
public final class BitcoinAddress {

    private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
    private static final int BECH32_CONST = 1;
    private static final int BECH32M_CONST = 0x2bc830a3;

    private BitcoinAddress() {}

    /** @return the address in lower case, or {@code null} if it is not a valid bc1 address */
    public static String normalize(String address) {
        if (address == null) return null;
        address = address.trim();
        if (address.length() < 14 || address.length() > 90) return null;
        if (!address.equals(address.toLowerCase(Locale.ROOT))
                && !address.equals(address.toUpperCase(Locale.ROOT))) {
            return null; // mixed case is not allowed
        }
        address = address.toLowerCase(Locale.ROOT);
        if (!address.startsWith("bc1")) return null;

        String hrp = "bc";
        String data = address.substring(3);
        if (data.length() < 6) return null;
        int[] values = new int[data.length()];
        for (int i = 0; i < data.length(); i++) {
            int v = CHARSET.indexOf(data.charAt(i));
            if (v < 0) return null;
            values[i] = v;
        }

        int witnessVersion = values[0];
        int checksum = polymod(hrp, values);
        if (witnessVersion == 0 && checksum != BECH32_CONST) return null;
        if (witnessVersion > 0 && checksum != BECH32M_CONST) return null;
        if (witnessVersion > 16) return null;

        // witness program: (data - version - 6 checksum chars) * 5 bits
        int programBits = (values.length - 7) * 5;
        int programBytes = programBits / 8;
        if (programBits % 8 > 4) return null;
        if (programBytes < 2 || programBytes > 40) return null;
        if (witnessVersion == 0 && programBytes != 20 && programBytes != 32) return null;

        return address;
    }

    /** BIP 21 payment URI, opened by wallet apps. */
    public static String toUri(String normalizedAddress) {
        return "bitcoin:" + normalizedAddress;
    }

    private static int polymod(String hrp, int[] data) {
        int[] gen = {0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3};
        int chk = 1;
        int[] expanded = new int[hrp.length() * 2 + 1 + data.length];
        int n = 0;
        for (int i = 0; i < hrp.length(); i++) expanded[n++] = hrp.charAt(i) >> 5;
        expanded[n++] = 0;
        for (int i = 0; i < hrp.length(); i++) expanded[n++] = hrp.charAt(i) & 31;
        for (int v : data) expanded[n++] = v;
        for (int v : expanded) {
            int top = chk >>> 25;
            chk = ((chk & 0x1ffffff) << 5) ^ v;
            for (int i = 0; i < 5; i++) {
                if (((top >>> i) & 1) != 0) chk ^= gen[i];
            }
        }
        return chk;
    }

}
