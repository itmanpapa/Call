package dummydomain.yetanothercallblocker.data.backup;

import java.io.IOException;

/** A backup file that can't be restored; {@link #getReason()} tells the user why. */
public class BackupException extends IOException {

    public enum Reason {
        /** Not a backup of this app (e.g. another ZIP file). */
        NOT_A_BACKUP,
        /** Made by a newer version of the app. */
        NEWER_VERSION,
        /** Damaged or incomplete. */
        CORRUPT,
        /** Larger than any real backup could be. */
        TOO_LARGE
    }

    private static final long serialVersionUID = 1L;

    private final Reason reason;

    public BackupException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public BackupException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

}
