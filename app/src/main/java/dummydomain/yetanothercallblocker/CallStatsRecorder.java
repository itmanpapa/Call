package dummydomain.yetanothercallblocker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.stats.CallEventStore;
import dummydomain.yetanothercallblocker.data.stats.CallStatClassifier;
import dummydomain.yetanothercallblocker.data.stats.CallStatEvent;

/**
 * Records handled incoming calls for the statistics, off the call path: the file is
 * written on a single background thread (in order, so the store can merge the reports
 * of the call screening service and the phone state listener of the same call).
 */
public final class CallStatsRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(CallStatsRecorder.class);

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "call-stats");
        thread.setDaemon(true);
        return thread;
    });

    private CallStatsRecorder() {
    }

    /**
     * @param numberInfo the info the decision was based on (may be null: nothing recorded)
     * @param blocked    whether the call was actually rejected
     */
    public static void record(NumberInfo numberInfo, boolean blocked) {
        if (numberInfo == null) return;
        CallEventStore store = YacbHolder.getCallEventStore();
        if (store == null) return;

        CallStatEvent event;
        try {
            CallStatClassifier.Facts facts = new CallStatClassifier.Facts();
            facts.blocked = blocked;
            facts.blockingReason = numberInfo.blockingReason != null
                    ? numberInfo.blockingReason.name() : null;
            facts.number = numberInfo.number;
            facts.normalizedNumber = numberInfo.normalizedNumber;
            facts.hiddenNumber = numberInfo.noNumber;
            facts.contact = numberInfo.contactItem != null;
            facts.ruleMatched = numberInfo.matchedRule != null;
            facts.negativeRating = numberInfo.rating == NumberInfo.Rating.NEGATIVE;
            facts.sourceId = numberInfo.sourceId;
            event = CallStatClassifier.classify(facts, System.currentTimeMillis());
        } catch (Exception e) {
            LOG.warn("record() failed to classify the call", e);
            return;
        }

        try {
            EXECUTOR.execute(() -> {
                try {
                    store.record(event);
                } catch (Exception e) {
                    LOG.warn("record() failed to write the event", e);
                }
            });
        } catch (Exception e) {
            LOG.warn("record() failed to schedule", e);
        }
    }

}
