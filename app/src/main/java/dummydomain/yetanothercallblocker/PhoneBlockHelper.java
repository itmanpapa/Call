package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.text.TextUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockClient;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockSync;

/**
 * PhoneBlock operations shared by the "Databases" screens and the background sync:
 * key verification and a sync that stores its outcome (the last error) for the UI.
 * The time and the size of the last successful sync are kept by {@link PhoneBlockSync}.
 */
public final class PhoneBlockHelper {

    private static final Logger LOG = LoggerFactory.getLogger(PhoneBlockHelper.class);

    private PhoneBlockHelper() {}

    /**
     * Checks the API key with the PhoneBlock {@code /test} endpoint. Blocks (network).
     *
     * @throws IOException if the key is rejected ({@link PhoneBlockClient.ApiException})
     *                     or the server can't be reached
     */
    public static void verifyKey(String token) throws IOException {
        PhoneBlockSync sync = requireSync();
        sync.getClient().testConnection(token);
    }

    /**
     * Synchronizes the list with the stored key and threshold. Blocks (network).
     * A failure is stored (see {@link Settings#getPhoneBlockLastError()}), a success
     * clears the stored error.
     *
     * @param manual true for a sync requested by the user
     * @return the result
     * @throws IOException on errors (the message is stored already)
     */
    public static PhoneBlockSync.SyncResult sync(Context context, boolean manual)
            throws IOException {
        Settings settings = App.getSettings();
        PhoneBlockSync sync = requireSync();

        String token = settings.getPhoneBlockToken();
        if (TextUtils.isEmpty(token)) {
            throw new IOException(context.getString(R.string.phoneblock_status_no_key));
        }

        int minVotes = settings.getPhoneBlockMinVotes();
        try {
            PhoneBlockSync.SyncResult result = sync.sync(token, minVotes, manual);
            // a skipped sync still applies a changed threshold
            if (result.isSkipped() && manual) sync.rebuildList(minVotes);
            settings.setPhoneBlockLastError(null, 0);
            return result;
        } catch (IOException | RuntimeException e) {
            LOG.warn("sync() failed", e);
            settings.setPhoneBlockLastError(describeError(context, e),
                    System.currentTimeMillis());
            throw e;
        }
    }

    /**
     * @return a user-facing description of a PhoneBlock error
     */
    public static String describeError(Context context, Exception e) {
        if (e instanceof PhoneBlockClient.ApiException) {
            PhoneBlockClient.ApiException apiException = (PhoneBlockClient.ApiException) e;
            if (apiException.isAuthError()) {
                return context.getString(R.string.phoneblock_sync_auth_error);
            }
            if (apiException.isRateLimited()) {
                return context.getString(R.string.phoneblock_sync_rate_limited);
            }
        }
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private static PhoneBlockSync requireSync() throws IOException {
        PhoneBlockSync sync = YacbHolder.getPhoneBlockSync();
        if (sync == null) throw new IOException("PhoneBlock is not initialized");
        return sync;
    }

}
