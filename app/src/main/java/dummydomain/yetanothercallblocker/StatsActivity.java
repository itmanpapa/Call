package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.SourcesManager;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.stats.CallEventStore;
import dummydomain.yetanothercallblocker.data.stats.CallStatClassifier;
import dummydomain.yetanothercallblocker.data.stats.CallStatEvent;
import dummydomain.yetanothercallblocker.data.stats.StatsCalculator;

/**
 * "Statistics": blocked calls today / in 7 / in 30 days, a bar chart of the last
 * 30 days, and blocked calls by reason, by source and by number.
 */
public class StatsActivity extends BaseActivity {

    private static final String STATE_YEAR = "STATE_YEAR";
    private static final int YEAR_DAYS = 365;

    private static final Logger LOG = LoggerFactory.getLogger(StatsActivity.class);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private List<CallStatEvent> events = Collections.emptyList();
    private boolean year;

    private TextView emptyView;
    private TextView todayView;
    private TextView days7View;
    private TextView days30View;
    private StatsBarChartView chartView;
    private TextView chartStartView;
    private TextView chartEndView;
    private ViewGroup reasonsView;
    private View sourcesCard;
    private ViewGroup sourcesView;
    private ViewGroup topView;
    private TextView topHintView;
    private TextView noteView;

    public static Intent getIntent(Context context) {
        return new Intent(context, StatsActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);

        if (savedInstanceState != null) year = savedInstanceState.getBoolean(STATE_YEAR);

        emptyView = findViewById(R.id.stats_empty);
        todayView = findViewById(R.id.stats_today_value);
        days7View = findViewById(R.id.stats_7_days_value);
        days30View = findViewById(R.id.stats_30_days_value);
        chartView = findViewById(R.id.stats_chart);
        chartStartView = findViewById(R.id.stats_chart_start);
        chartEndView = findViewById(R.id.stats_chart_end);
        reasonsView = findViewById(R.id.stats_reasons);
        sourcesCard = findViewById(R.id.stats_sources_card);
        sourcesView = findViewById(R.id.stats_sources);
        topView = findViewById(R.id.stats_top);
        topHintView = findViewById(R.id.stats_top_hint);
        noteView = findViewById(R.id.stats_note);

        MaterialButtonToggleGroup period = findViewById(R.id.stats_period);
        period.check(year ? R.id.stats_period_year : R.id.stats_period_30);
        period.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            year = checkedId == R.id.stats_period_year;
            bind();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        load();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_YEAR, year);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onSupportNavigateUp() {
        // opened from the settings or from the call log: go back to where it came from
        finish();
        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_stats, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_stats_clear) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.stats_clear)
                    .setMessage(R.string.stats_clear_confirm)
                    .setPositiveButton(R.string.stats_clear_button, (d, w) -> clear())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void load() {
        CallEventStore store = YacbHolder.getCallEventStore();
        if (store == null || executor.isShutdown()) return;
        executor.execute(() -> {
            List<CallStatEvent> loaded;
            boolean failed = false;
            try {
                loaded = store.load(System.currentTimeMillis());
            } catch (Exception e) {
                LOG.warn("load() failed", e);
                loaded = Collections.emptyList();
                failed = true;
            }
            List<CallStatEvent> result = loaded;
            boolean error = failed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                events = result;
                bind();
                if (error) {
                    Toast.makeText(this, R.string.stats_load_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void clear() {
        CallEventStore store = YacbHolder.getCallEventStore();
        if (store == null || executor.isShutdown()) return;
        executor.execute(() -> {
            try {
                store.clear();
            } catch (Exception e) {
                LOG.warn("clear() failed", e);
            }
            runOnUiThread(this::load);
        });
    }

    /** Computes the numbers (cheap: at most a year of calls in memory) and shows them. */
    private void bind() {
        StatsCalculator.Stats stats = StatsCalculator.calculate(events,
                System.currentTimeMillis(), ZoneId.systemDefault(),
                year ? YEAR_DAYS : StatsCalculator.CHART_DAYS);

        emptyView.setVisibility(stats.isEmpty() ? View.VISIBLE : View.GONE);
        todayView.setText(String.valueOf(stats.getBlockedToday()));
        days7View.setText(String.valueOf(stats.getBlocked7Days()));
        days30View.setText(String.valueOf(stats.getBlocked30Days()));

        int[] perDay = stats.getBlockedPerDay();
        int max = 0;
        for (int v : perDay) max = Math.max(max, v);
        chartView.setValues(perDay);
        chartView.setContentDescription(getString(R.string.stats_chart_description,
                stats.getBlocked30Days(), max));
        DateFormat dateFormat = android.text.format.DateFormat.getDateFormat(this);
        chartStartView.setText(formatDate(dateFormat, stats, 0));
        chartEndView.setText(formatDate(dateFormat, stats, perDay.length - 1));

        reasonsView.removeAllViews();
        for (StatsCalculator.Count count : stats.getByReason()) {
            addRow(reasonsView, getReasonName(count.getKey()), count.getCount(), null);
        }
        if (stats.getByReason().isEmpty()) addEmptyRow(reasonsView);

        sourcesView.removeAllViews();
        for (StatsCalculator.Count count : stats.getBySource()) {
            addRow(sourcesView, getSourceName(count.getKey()), count.getCount(), null);
        }
        sourcesCard.setVisibility(stats.getBySource().isEmpty() ? View.GONE : View.VISIBLE);

        topView.removeAllViews();
        for (StatsCalculator.Count count : stats.getTopNumbers()) {
            String number = count.getKey();
            addRow(topView, number, count.getCount(), v -> showNumberInfo(number));
        }
        if (stats.getTopNumbers().isEmpty()) addEmptyRow(topView);
        topHintView.setVisibility(stats.getTopNumbers().isEmpty() ? View.GONE : View.VISIBLE);

        noteView.setText(getString(R.string.stats_note,
                stats.getHandledTotal(), stats.getBlockedTotal()));
    }

    private String formatDate(DateFormat format, StatsCalculator.Stats stats, int index) {
        java.time.LocalDate date = stats.getChartDate(index);
        return format.format(new Date(date.atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli()));
    }

    private void addRow(ViewGroup parent, String label, int count,
                        View.OnClickListener listener) {
        View row = LayoutInflater.from(this).inflate(R.layout.stats_row, parent, false);
        ((TextView) row.findViewById(R.id.stats_row_label)).setText(label);
        ((TextView) row.findViewById(R.id.stats_row_count)).setText(String.valueOf(count));
        if (listener != null) {
            TypedValue outValue = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
            row.setBackgroundResource(outValue.resourceId);
            row.setOnClickListener(listener);
        }
        parent.addView(row);
    }

    private void addEmptyRow(ViewGroup parent) {
        View row = LayoutInflater.from(this).inflate(R.layout.stats_row, parent, false);
        TextView label = row.findViewById(R.id.stats_row_label);
        label.setText(R.string.stats_list_empty);
        label.setTextAppearance(com.google.android.material.R.style
                .TextAppearance_Material3_BodyMedium);
        row.findViewById(R.id.stats_row_count).setVisibility(View.GONE);
        parent.addView(row);
    }

    private String getReasonName(String reason) {
        CallStatEvent.Reason r = CallStatEvent.Reason.fromName(reason);
        switch (r) {
            case BLACKLIST: return getString(R.string.stats_reason_blacklist);
            case RATING: return getString(R.string.stats_reason_rating);
            case LIST: return getString(R.string.stats_reason_list);
            case RULE: return getString(R.string.stats_reason_rule);
            case USER_MARK: return getString(R.string.stats_reason_user_mark);
            case HIDDEN: return getString(R.string.stats_reason_hidden);
            case CONTACT: return getString(R.string.stats_reason_contact);
            default: return getString(R.string.stats_reason_other);
        }
    }

    private String getSourceName(String sourceId) {
        if (CallStatClassifier.USER_MARK_SOURCE_ID.equals(sourceId)) {
            return getString(R.string.stats_source_user_mark);
        }
        if (CallStatClassifier.YACB_SOURCE_ID.equals(sourceId)) {
            return getString(R.string.stats_source_yacb);
        }
        SourcesManager manager = YacbHolder.getSourcesManager();
        String name = null;
        try {
            // a deleted list has no name any more: the id is shown then
            if (manager != null && manager.isLoaded()) name = manager.getDisplayName(sourceId);
        } catch (Exception e) {
            LOG.debug("getSourceName() failed for {}", sourceId, e);
        }
        return !TextUtils.isEmpty(name) ? name : sourceId;
    }

    private void showNumberInfo(String number) {
        if (executor.isShutdown()) return;
        executor.execute(() -> {
            NumberInfo info;
            try {
                info = YacbHolder.getNumberInfoService().getNumberInfo(number,
                        App.getSettings().getCachedAutoDetectedCountryCode(), true);
            } catch (Exception e) {
                LOG.warn("showNumberInfo() failed", e);
                return;
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                InfoDialogHelper.showDialog(this, info, null);
            });
        });
    }

}
