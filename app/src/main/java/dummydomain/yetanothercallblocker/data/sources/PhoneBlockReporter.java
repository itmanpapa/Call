package dummydomain.yetanothercallblocker.data.sources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

import dummydomain.yetanothercallblocker.data.UserMark;

/**
 * Reports the user's marks to PhoneBlock: decides what to report for a mark and sends
 * the pending reports of a {@link PhoneBlockReportQueue}. Plain Java (the Android side
 * schedules {@link #sendPending(String)} with WorkManager).
 *
 * <ul>
 *     <li>SPAM mark: {@code POST /rate} with the chosen {@link Category}'s rating
 *     (the number lands on the user's PhoneBlock blacklist);</li>
 *     <li>NOT_SPAM mark: {@code POST /rate} with {@code A_LEGITIMATE} (whitelist);</li>
 *     <li>mark removed: {@code DELETE /blacklist/{phone}} or
 *     {@code DELETE /whitelist/{phone}}, whichever was reported before.</li>
 * </ul>
 * Contacts and hidden numbers are never reported.
 */
public class PhoneBlockReporter {

    /** The categories offered to the user for a spam report. */
    public enum Category {
        /** Advertising, marketing (the default). */
        ADVERTISING(PhoneBlockClient.Rating.E_ADVERTISING),
        /** Polls, surveys. */
        POLL(PhoneBlockClient.Rating.D_POLL),
        /** Fraud. */
        FRAUD(PhoneBlockClient.Rating.G_FRAUD),
        /** The caller hung up right away. */
        PING(PhoneBlockClient.Rating.C_PING),
        /** Gambling, prize notifications. */
        GAMBLE(PhoneBlockClient.Rating.F_GAMBLE),
        /** Spam of another kind ("negative rating without a call type"). */
        OTHER(PhoneBlockClient.Rating.B_MISSED);

        public static final Category DEFAULT = ADVERTISING;

        private final PhoneBlockClient.Rating rating;

        Category(PhoneBlockClient.Rating rating) {
            this.rating = rating;
        }

        public PhoneBlockClient.Rating getRating() {
            return rating;
        }

        /** @return the category with the given name, {@link #DEFAULT} if unknown */
        public static Category fromName(String name) {
            if (name != null) {
                for (Category c : values()) {
                    if (c.name().equals(name)) return c;
                }
            }
            return DEFAULT;
        }
    }

    /** Outcome of {@link #sendPending(String)}. */
    public static final class Result {

        private final int sent;
        private final int rejected;
        private final boolean retry;
        private final boolean authError;
        private final String error;

        Result(int sent, int rejected, boolean retry, boolean authError, String error) {
            this.sent = sent;
            this.rejected = rejected;
            this.retry = retry;
            this.authError = authError;
            this.error = error;
        }

        /** Reports (ratings and removals) sent successfully. */
        public int getSent() {
            return sent;
        }

        /** Reports the server rejected (they are not retried). */
        public int getRejected() {
            return rejected;
        }

        /** True if reports are left that should be retried later (network errors etc.). */
        public boolean shouldRetry() {
            return retry;
        }

        /** True if the API key was rejected; retrying doesn't help until it changes. */
        public boolean isAuthError() {
            return authError;
        }

        /** The last error message, null if none. */
        public String getError() {
            return error;
        }

        @Override
        public String toString() {
            return "Result{sent=" + sent + ", rejected=" + rejected + ", retry=" + retry
                    + ", authError=" + authError + (error != null ? ", error=" + error : "")
                    + '}';
        }
    }

    /** At most this many requests per run, to be gentle with the server. */
    static final int MAX_REQUESTS_PER_RUN = 50;

    private static final Logger LOG = LoggerFactory.getLogger(PhoneBlockReporter.class);

    private final PhoneBlockClient client;
    private final PhoneBlockReportQueue queue;
    private final LongSupplier clock;

    public PhoneBlockReporter(PhoneBlockClient client, PhoneBlockReportQueue queue,
                              LongSupplier clock) {
        this.client = Objects.requireNonNull(client);
        this.queue = Objects.requireNonNull(queue);
        this.clock = Objects.requireNonNull(clock);
    }

    public PhoneBlockReportQueue getQueue() {
        return queue;
    }

    /**
     * @param enabled    the "send my marks to PhoneBlock" setting
     * @param token      the API key, may be null
     * @param inContacts the number is a contact
     * @param hidden     the number is hidden / unknown
     * @param number     the number (normalized), may be null
     * @return whether a mark of the number may be reported
     */
    public static boolean shouldReport(boolean enabled, String token, boolean inContacts,
                                       boolean hidden, String number) {
        if (!enabled || token == null || token.trim().isEmpty()) return false;
        if (inContacts || hidden) return false;
        return PhoneBlockClient.normalizePhone(number) != null;
    }

    /** @return the verdict to report for a mark ({@code null}: the mark was removed) */
    public static PhoneBlockReportQueue.Verdict verdictFor(UserMark.Type type) {
        if (type == null) return PhoneBlockReportQueue.Verdict.NONE;
        return type == UserMark.Type.SPAM
                ? PhoneBlockReportQueue.Verdict.SPAM : PhoneBlockReportQueue.Verdict.LEGITIMATE;
    }

    /**
     * Queues the report of a mark change (the caller checked {@link #shouldReport}).
     *
     * @param type     the new mark, null if the mark was removed
     * @param category the category for a SPAM mark, null for the default
     * @return the queue entry, null if the number is not in E.164 form
     */
    public PhoneBlockReportQueue.Entry onMarkChanged(String number, UserMark.Type type,
                                                     Category category) throws IOException {
        PhoneBlockReportQueue.Verdict verdict = verdictFor(type);
        PhoneBlockClient.Rating rating = verdict == PhoneBlockReportQueue.Verdict.SPAM
                ? (category != null ? category : Category.DEFAULT).getRating() : null;
        return queue.request(number, verdict, rating, clock.getAsLong());
    }

    /**
     * Sends the pending reports. Blocks (network). Stops at the first network or
     * server error (the rest is retried later) and at an authentication error.
     */
    public Result sendPending(String token) {
        List<PhoneBlockReportQueue.Entry> pending = queue.getPending();
        LOG.debug("sendPending() {} pending", pending.size());

        int sent = 0;
        int rejected = 0;
        int requests = 0;
        for (PhoneBlockReportQueue.Entry entry : pending) {
            if (requests >= MAX_REQUESTS_PER_RUN) {
                return new Result(sent, rejected, true, false, null);
            }
            requests++;
            try {
                send(token, entry);
                sent++;
            } catch (PhoneBlockClient.ApiException e) {
                LOG.warn("sendPending() failed: {}", e.getMessage());
                if (e.isAuthError()) {
                    markFailed(entry, e.getMessage(), false);
                    return new Result(sent, rejected, false, true, e.getMessage());
                }
                if (isPermanent(e)) {
                    markFailed(entry, e.getMessage(), true);
                    rejected++;
                    continue;
                }
                markFailed(entry, e.getMessage(), false);
                return new Result(sent, rejected, true, false, e.getMessage());
            } catch (IllegalArgumentException e) {
                // not an E.164 number: can't be sent ever
                markFailed(entry, e.getMessage(), true);
                rejected++;
            } catch (IOException | RuntimeException e) {
                LOG.warn("sendPending() failed", e);
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                markFailed(entry, message, false);
                return new Result(sent, rejected, true, false, message);
            }
        }
        return new Result(sent, rejected, false, false, null);
    }

    private void send(String token, PhoneBlockReportQueue.Entry entry) throws IOException {
        String number = entry.getNumber();
        long now = clock.getAsLong();
        switch (entry.getWanted()) {
            case SPAM:
                client.rate(token, number, entry.getWantedRating(), null);
                queue.markSent(number, PhoneBlockReportQueue.Verdict.SPAM,
                        entry.getWantedRating(), now, true);
                break;

            case LEGITIMATE:
                client.rate(token, number, PhoneBlockClient.Rating.A_LEGITIMATE, null);
                queue.markSent(number, PhoneBlockReportQueue.Verdict.LEGITIMATE, null, now, true);
                break;

            case NONE:
                // withdraw what was reported; "not on the list" (404) is fine as well
                if (entry.getSent() == PhoneBlockReportQueue.Verdict.SPAM) {
                    client.removeFromBlacklist(token, number);
                } else if (entry.getSent() == PhoneBlockReportQueue.Verdict.LEGITIMATE) {
                    client.removeFromWhitelist(token, number);
                }
                queue.markSent(number, PhoneBlockReportQueue.Verdict.NONE, null, now, false);
                break;
        }
    }

    /** 4xx answers other than auth errors and 408/429 won't succeed on retry. */
    static boolean isPermanent(PhoneBlockClient.ApiException e) {
        int code = e.getHttpCode();
        return code >= 400 && code < 500 && code != 408 && code != 429 && !e.isAuthError();
    }

    private void markFailed(PhoneBlockReportQueue.Entry entry, String error, boolean permanent) {
        try {
            queue.markFailed(entry.getNumber(), error, permanent);
        } catch (IOException e) {
            LOG.error("markFailed() failed", e);
        }
    }

}
