package dummydomain.yetanothercallblocker;

import android.view.View;
import android.widget.TextView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.stats.CallEventStore;
import dummydomain.yetanothercallblocker.data.stats.CallStatEvent;
import dummydomain.yetanothercallblocker.data.stats.StatsCalculator;

/**
 * The "Blocked in 7 days: N" card at the top of the call log; a tap opens the
 * statistics. The count is read in the background.
 */
final class StatsSummaryCard {

    private static final Logger LOG = LoggerFactory.getLogger(StatsSummaryCard.class);

    private final BaseActivity activity;
    private final View card;
    private final TextView textView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * @param card the card from {@code activity_main.xml}
     */
    StatsSummaryCard(BaseActivity activity, View card) {
        this.activity = activity;
        this.card = card;
        this.textView = card.findViewById(R.id.stats_card_text);
        card.setOnClickListener(v -> activity.startActivity(StatsActivity.getIntent(activity)));
    }

    /** Reads the statistics in the background and updates the card. */
    void refresh() {
        CallEventStore store = YacbHolder.getCallEventStore();
        if (store == null || executor.isShutdown()) return;
        executor.execute(() -> {
            int blocked;
            try {
                long now = System.currentTimeMillis();
                List<CallStatEvent> events = store.load(now);
                blocked = StatsCalculator.calculate(events, now, ZoneId.systemDefault(),
                        StatsCalculator.CHART_DAYS).getBlocked7Days();
            } catch (Exception e) {
                LOG.warn("refresh() failed", e);
                return;
            }
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                textView.setText(activity.getString(R.string.stats_summary_card, blocked));
                card.setContentDescription(activity.getString(R.string.stats_summary_card,
                        blocked) + ". " + activity.getString(R.string.stats_summary_card_hint));
                card.setVisibility(View.VISIBLE);
            });
        });
    }

    /** Call from {@code onDestroy()}. */
    void shutdown() {
        executor.shutdown();
    }

}
