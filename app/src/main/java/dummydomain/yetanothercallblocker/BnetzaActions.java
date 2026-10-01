package dummydomain.yetanothercallblocker;

import dummydomain.yetanothercallblocker.data.SourcesManager;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.sources.BnetzaAutoUpdater;

/**
 * The only place where the "Databases" screens talk to the automatic download of the
 * Bundesnetzagentur list.
 *
 * <p>The list itself is stored as the source {@link SourcesManager#BNETZA_SOURCE_ID},
 * so its date and size come from the list metadata. This adapter only answers whether
 * the automatic download exists in this build and starts it.</p>
 *
 * <p>Wiring the updater (once {@code YacbHolder.getBnetzaAutoUpdater()} exists) only
 * changes the bodies of {@link #isAvailable()}, {@link #update(boolean)} and
 * {@link #getLastError()}; the screens need no changes.</p>
 */
final class BnetzaActions {

    /** Page of the official list (shown in the details). */
    static final String SOURCE_PAGE_URL = "https://www.bundesnetzagentur.de/massnahmenliste";

    /** Outcome of an update. Immutable. */
    static final class Result {

        private final boolean success;
        private final String error;

        private Result(boolean success, String error) {
            this.success = success;
            this.error = error;
        }

        static Result success() {
            return new Result(true, null);
        }

        static Result failure(String error) {
            return new Result(false, error);
        }

        boolean isSuccess() {
            return success;
        }

        /** @return the error message of a failed update, null on success */
        String getError() {
            return error;
        }
    }

    private BnetzaActions() {}

    /**
     * @return true if this build can download the list automatically
     */
    static boolean isAvailable() {
        return YacbHolder.getBnetzaAutoUpdater() != null;
    }

    /**
     * Downloads the list now. Blocks (network); call from a background thread.
     *
     * @param force true to download even if the list was updated recently
     */
    static Result update(boolean force) {
        BnetzaAutoUpdater updater = YacbHolder.getBnetzaAutoUpdater();
        if (updater == null) {
            return Result.failure(App.getInstance().getString(R.string.bnetza_status_not_available));
        }
        BnetzaAutoUpdater.Result result = updater.update(force);
        return result.isSuccess() ? Result.success() : Result.failure(result.getError());
    }

    /**
     * @return the persisted error of the last automatic update, or null
     */
    static String getLastError() {
        BnetzaAutoUpdater updater = YacbHolder.getBnetzaAutoUpdater();
        if (updater == null) return null;
        try {
            return updater.getStatus().getLastError();
        } catch (Exception e) {
            return null;
        }
    }

}
