package dummydomain.yetanothercallblocker.data.donation;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Checks the TRON (TRC20) donation address from res/values/donation.xml: base58check,
 * 21 bytes starting with 0x41 ("T..."), so that a typo hides the address instead of
 * sending money nowhere.
 */
public final class TronAddress {

    private static final String ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private TronAddress() {}

    /** @return the trimmed address, or {@code null} if it is not a valid TRON address */
    public static String normalize(String address) {
        if (address == null) return null;
        address = address.trim();
        if (address.length() != 34 || address.charAt(0) != 'T') return null;

        byte[] raw = decodeBase58(address);
        if (raw == null || raw.length != 25 || raw[0] != 0x41) return null;

        byte[] payload = Arrays.copyOfRange(raw, 0, 21);
        byte[] checksum = Arrays.copyOfRange(sha256(sha256(payload)), 0, 4);
        if (!Arrays.equals(checksum, Arrays.copyOfRange(raw, 21, 25))) return null;
        return address;
    }

    private static byte[] decodeBase58(String s) {
        BigInteger value = BigInteger.ZERO;
        BigInteger base = BigInteger.valueOf(58);
        for (int i = 0; i < s.length(); i++) {
            int digit = ALPHABET.indexOf(s.charAt(i));
            if (digit < 0) return null;
            value = value.multiply(base).add(BigInteger.valueOf(digit));
        }
        byte[] bytes = value.toByteArray();
        // drop the sign byte of BigInteger; TRON addresses have no leading '1' (zero bytes)
        if (bytes.length > 1 && bytes[0] == 0) bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        return bytes;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
