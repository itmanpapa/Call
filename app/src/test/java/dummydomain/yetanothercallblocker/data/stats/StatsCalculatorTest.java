package dummydomain.yetanothercallblocker.data.stats;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dummydomain.yetanothercallblocker.data.stats.CallStatEvent.Outcome;
import dummydomain.yetanothercallblocker.data.stats.CallStatEvent.Reason;
import dummydomain.yetanothercallblocker.data.stats.StatsCalculator.Count;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StatsCalculatorTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    // 2026-10-02 10:00 local time
    private static final long NOW = at(2026, 10, 2, 10, 0);

    private static long at(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(ZONE).toInstant().toEpochMilli();
    }

    private static CallStatEvent blocked(long ts, String number, Reason reason, String source) {
        return new CallStatEvent(ts, number, Outcome.BLOCKED, reason, source);
    }

    @Test
    public void emptyInput() {
        StatsCalculator.Stats stats = StatsCalculator.calculate(
                Collections.emptyList(), NOW, ZONE, 30);
        assertTrue(stats.isEmpty());
        assertEquals(0, stats.getBlockedToday());
        assertEquals(StatsCalculator.CHART_DAYS, stats.getBlockedPerDay().length);
        assertTrue(stats.getTopNumbers().isEmpty());
        assertEquals(LocalDate.of(2026, 10, 2), stats.getToday());
        assertEquals(LocalDate.of(2026, 9, 3), stats.getChartDate(0));
    }

    @Test
    public void periodsAreCalendarDays() {
        List<CallStatEvent> events = Arrays.asList(
                blocked(at(2026, 10, 2, 0, 5), "+491", Reason.LIST, "bnetza"),   // today
                blocked(at(2026, 10, 1, 23, 55), "+491", Reason.LIST, "bnetza"), // yesterday
                blocked(at(2026, 9, 26, 12, 0), "+492", Reason.RULE, null),      // 6 days ago
                blocked(at(2026, 9, 25, 12, 0), "+493", Reason.BLACKLIST, null), // 7 days ago
                blocked(at(2026, 9, 3, 12, 0), "+494", Reason.RATING, "yacb"),   // 29 days ago
                blocked(at(2026, 9, 2, 12, 0), "+495", Reason.RATING, "yacb"),   // 30 days ago
                new CallStatEvent(at(2026, 10, 2, 9, 0), "+496", Outcome.ALLOWED,
                        Reason.NONE, null),
                new CallStatEvent(at(2026, 10, 2, 9, 30), "+497", Outcome.NOTIFIED,
                        Reason.RATING, "yacb"));

        StatsCalculator.Stats stats = StatsCalculator.calculate(events, NOW, ZONE, 30);
        assertEquals(1, stats.getBlockedToday());
        assertEquals(3, stats.getBlocked7Days());
        assertEquals(5, stats.getBlocked30Days());
        assertEquals(6, stats.getBlockedTotal());
        assertEquals(8, stats.getHandledTotal());

        int[] perDay = stats.getBlockedPerDay();
        assertEquals(1, perDay[29]); // today
        assertEquals(1, perDay[28]); // yesterday
        assertEquals(1, perDay[23]);
        assertEquals(1, perDay[22]);
        assertEquals(1, perDay[0]);  // 29 days ago
        int sum = 0;
        for (int v : perDay) sum += v;
        assertEquals(5, sum);
    }

    @Test
    public void listsAreSortedAndLimitedToThePeriod() {
        List<CallStatEvent> events = new ArrayList<>();
        events.add(blocked(at(2026, 10, 2, 8, 0), "+491", Reason.LIST, "bnetza"));
        events.add(blocked(at(2026, 10, 1, 8, 0), "+491", Reason.LIST, "bnetza"));
        events.add(blocked(at(2026, 9, 30, 8, 0), "+491", Reason.LIST, "phoneblock"));
        events.add(blocked(at(2026, 9, 30, 9, 0), "+492", Reason.RULE, null));
        events.add(blocked(at(2026, 9, 30, 10, 0), "", Reason.HIDDEN, null));
        events.add(blocked(at(2026, 9, 30, 11, 0), "", Reason.HIDDEN, null));
        events.add(blocked(at(2026, 9, 30, 12, 0), "", Reason.HIDDEN, null));
        // outside the 7-day list period
        events.add(blocked(at(2026, 9, 1, 8, 0), "+493", Reason.BLACKLIST, null));

        StatsCalculator.Stats stats = StatsCalculator.calculate(events, NOW, ZONE, 7);
        assertEquals(7, stats.getListDays());
        assertEquals(Arrays.asList(new Count("HIDDEN", 3), new Count("LIST", 3),
                new Count("RULE", 1)), stats.getByReason());
        assertEquals(Arrays.asList(new Count("bnetza", 2), new Count("phoneblock", 1)),
                stats.getBySource());
        // hidden numbers are not "numbers"
        assertEquals(Arrays.asList(new Count("+491", 3), new Count("+492", 1)),
                stats.getTopNumbers());

        StatsCalculator.Stats year = StatsCalculator.calculate(events, NOW, ZONE, 365);
        assertTrue(year.getByReason().contains(new Count("BLACKLIST", 1)));
    }

    @Test
    public void topNumbersAreLimited() {
        List<CallStatEvent> events = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            for (int j = 0; j <= i; j++) {
                events.add(blocked(NOW - j * 1000L, "+49" + (100 + i), Reason.LIST, "x"));
            }
        }
        List<Count> top = StatsCalculator.calculate(events, NOW, ZONE, 30).getTopNumbers();
        assertEquals(StatsCalculator.TOP_NUMBERS, top.size());
        assertEquals(new Count("+49114", 15), top.get(0));
        assertEquals(new Count("+49105", 6), top.get(9));
    }

    @Test
    public void futureEventsCountAsToday() {
        StatsCalculator.Stats stats = StatsCalculator.calculate(Collections.singletonList(
                blocked(NOW + 3 * 86_400_000L, "+491", Reason.LIST, "x")), NOW, ZONE, 30);
        assertEquals(1, stats.getBlockedToday());
        assertFalse(stats.isEmpty());
    }

    @Test
    public void chartDatesMatchIndexes() {
        StatsCalculator.Stats stats = StatsCalculator.calculate(
                Collections.emptyList(), NOW, ZONE, 30);
        assertEquals(LocalDate.of(2026, 10, 2), stats.getChartDate(29));
        assertArrayEquals(new int[30], stats.getBlockedPerDay());
    }

}
