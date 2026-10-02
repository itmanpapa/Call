package dummydomain.yetanothercallblocker.data.stats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Computes the numbers of the statistics screen from the recorded calls.
 *
 * <p>Periods are calendar days in the given time zone, including today: "7 days" is
 * today and the 6 days before. Plain Java, no Android dependencies.</p>
 */
public final class StatsCalculator {

    /** Days in the per-day chart. */
    public static final int CHART_DAYS = 30;
    /** Entries in the "most frequently blocked numbers" list. */
    public static final int TOP_NUMBERS = 10;

    /** A key (reason, source id or number) and how often it occurred. Immutable. */
    public static final class Count {
        private final String key;
        private final int count;

        public Count(String key, int count) {
            this.key = key;
            this.count = count;
        }

        public String getKey() {
            return key;
        }

        public int getCount() {
            return count;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Count)) return false;
            Count that = (Count) o;
            return count == that.count && Objects.equals(key, that.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, count);
        }

        @Override
        public String toString() {
            return key + "=" + count;
        }
    }

    /** The result. Immutable. */
    public static final class Stats {
        private final LocalDate today;
        private final int blockedToday;
        private final int blocked7Days;
        private final int blocked30Days;
        private final int blockedTotal;
        private final int handledTotal;
        private final int listDays;
        private final int[] blockedPerDay;
        private final List<Count> byReason;
        private final List<Count> bySource;
        private final List<Count> topNumbers;

        Stats(LocalDate today, int blockedToday, int blocked7Days, int blocked30Days,
              int blockedTotal, int handledTotal, int listDays, int[] blockedPerDay,
              List<Count> byReason, List<Count> bySource, List<Count> topNumbers) {
            this.today = today;
            this.blockedToday = blockedToday;
            this.blocked7Days = blocked7Days;
            this.blocked30Days = blocked30Days;
            this.blockedTotal = blockedTotal;
            this.handledTotal = handledTotal;
            this.listDays = listDays;
            this.blockedPerDay = blockedPerDay;
            this.byReason = Collections.unmodifiableList(byReason);
            this.bySource = Collections.unmodifiableList(bySource);
            this.topNumbers = Collections.unmodifiableList(topNumbers);
        }

        public LocalDate getToday() {
            return today;
        }

        public int getBlockedToday() {
            return blockedToday;
        }

        public int getBlocked7Days() {
            return blocked7Days;
        }

        public int getBlocked30Days() {
            return blocked30Days;
        }

        /** @return blocked calls of the whole retention period */
        public int getBlockedTotal() {
            return blockedTotal;
        }

        /** @return all recorded calls of the whole retention period */
        public int getHandledTotal() {
            return handledTotal;
        }

        /** @return the period (days) of the reason, source and top number lists */
        public int getListDays() {
            return listDays;
        }

        /**
         * @return blocked calls per day, {@link #CHART_DAYS} values, the oldest day first
         * and today last
         */
        public int[] getBlockedPerDay() {
            return blockedPerDay.clone();
        }

        /** @return the date of a {@link #getBlockedPerDay()} index */
        public LocalDate getChartDate(int index) {
            return today.minusDays(CHART_DAYS - 1 - index);
        }

        /** @return blocked calls by {@link CallStatEvent.Reason} name, most frequent first */
        public List<Count> getByReason() {
            return byReason;
        }

        /** @return blocked calls by source id (only rated calls), most frequent first */
        public List<Count> getBySource() {
            return bySource;
        }

        /** @return the most frequently blocked numbers (no hidden numbers), at most 10 */
        public List<Count> getTopNumbers() {
            return topNumbers;
        }

        public boolean isEmpty() {
            return handledTotal == 0;
        }
    }

    private StatsCalculator() {
    }

    /**
     * @param events   recorded calls (any order)
     * @param now      the current time, millis
     * @param zone     the time zone of the calendar days
     * @param listDays the period of the reason, source and top number lists in days
     *                 (e.g. 30, or 365 for the whole retention period)
     */
    public static Stats calculate(List<CallStatEvent> events, long now, ZoneId zone,
                                  int listDays) {
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();

        int blockedToday = 0;
        int blocked7Days = 0;
        int blocked30Days = 0;
        int blockedTotal = 0;
        int[] perDay = new int[CHART_DAYS];
        Map<String, Integer> byReason = new HashMap<>();
        Map<String, Integer> bySource = new HashMap<>();
        Map<String, Integer> byNumber = new HashMap<>();

        for (CallStatEvent event : events) {
            if (!event.isBlocked()) continue;
            blockedTotal++;

            LocalDate date = Instant.ofEpochMilli(event.getTimestamp()).atZone(zone)
                    .toLocalDate();
            // a call "in the future" (the clock was changed) counts as today
            long age = Math.max(0, ChronoUnit.DAYS.between(date, today));

            if (age == 0) blockedToday++;
            if (age < 7) blocked7Days++;
            if (age < 30) blocked30Days++;
            if (age < CHART_DAYS) perDay[(int) (CHART_DAYS - 1 - age)]++;

            if (age < listDays) {
                increment(byReason, event.getReason().name());
                if (event.getSourceId() != null) increment(bySource, event.getSourceId());
                if (!event.isHiddenNumber()) increment(byNumber, event.getNumber());
            }
        }

        List<Count> top = sorted(byNumber);
        if (top.size() > TOP_NUMBERS) top = new ArrayList<>(top.subList(0, TOP_NUMBERS));

        return new Stats(today, blockedToday, blocked7Days, blocked30Days, blockedTotal,
                events.size(), listDays, perDay, sorted(byReason), sorted(bySource), top);
    }

    private static void increment(Map<String, Integer> map, String key) {
        Integer count = map.get(key);
        map.put(key, count != null ? count + 1 : 1);
    }

    /** @return the counts, most frequent first, then by key */
    private static List<Count> sorted(Map<String, Integer> map) {
        List<Count> list = new ArrayList<>(map.size());
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            list.add(new Count(e.getKey(), e.getValue()));
        }
        list.sort((a, b) -> a.count != b.count ? Integer.compare(b.count, a.count)
                : a.key.compareTo(b.key));
        return list;
    }

}
