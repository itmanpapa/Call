package dummydomain.yetanothercallblocker.data.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/**
 * Owns the call rules: keeps them cached in memory for the call path (evaluation does
 * no I/O once loaded), persists changes through {@link RulesStore} and remembers recent
 * incoming calls for {@link RuleType#REPEATED_CALLER} rules.
 *
 * <p>Thread-safe. Mutations write the file, call them off the main thread.</p>
 */
public class RulesManager {

    public interface Listener {
        /** Called after the rules were loaded or changed (on the calling thread). */
        void onRulesChanged(List<CallRule> rules);
    }

    public interface Clock {
        long currentTimeMillis();

        /** @return local time of the instant, minutes since midnight */
        int minuteOfDay(long millis);
    }

    /** The system clock in the default time zone. */
    public static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }

        @Override
        public int minuteOfDay(long millis) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(millis);
            return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
        }
    };

    private static final Logger LOG = LoggerFactory.getLogger(RulesManager.class);

    private final RulesStore store;
    private final Clock clock;
    private final RecentCallsMemory recentCalls;

    private final Object lock = new Object();

    private volatile List<CallRule> rules = Collections.emptyList();
    private volatile boolean loaded;
    private volatile Listener listener;

    /**
     * @param store            where the rules are kept
     * @param clock            time source
     * @param recentCallsFallback asked about calls before this manager was created
     *                         (e.g. the system call log), may be null
     */
    public RulesManager(RulesStore store, Clock clock, RecentCalls recentCallsFallback) {
        this.store = store;
        this.clock = clock;
        this.recentCalls = new RecentCallsMemory(clock.currentTimeMillis(), recentCallsFallback);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Loads the rules from the store unless already loaded. */
    public void ensureLoaded() {
        if (loaded) return;
        synchronized (lock) {
            if (loaded) return;
            List<CallRule> list;
            try {
                list = store.load();
            } catch (IOException e) {
                // keep working without rules rather than crash the call path;
                // the broken file stays until the rules are changed
                LOG.error("ensureLoaded() failed to load rules", e);
                list = new ArrayList<>();
            }
            rules = Collections.unmodifiableList(assignMissingIds(list));
            loaded = true;
            LOG.debug("ensureLoaded() loaded {} rules", rules.size());
        }
        notifyListener();
    }

    private static List<CallRule> assignMissingIds(List<CallRule> list) {
        long maxId = 0;
        for (CallRule rule : list) maxId = Math.max(maxId, rule.getId());

        List<CallRule> result = new ArrayList<>(list.size());
        List<Long> seen = new ArrayList<>();
        for (CallRule rule : list) {
            if (rule.getId() <= 0 || seen.contains(rule.getId())) {
                rule = rule.toBuilder().id(++maxId).build();
            }
            seen.add(rule.getId());
            result.add(rule);
        }
        return result;
    }

    /** @return the rules in priority order (unmodifiable snapshot) */
    public List<CallRule> getRules() {
        ensureLoaded();
        return rules;
    }

    /** @return whether any enabled rule can block calls */
    public boolean hasBlockingRules() {
        return hasBlockingRules(getRules());
    }

    public static boolean hasBlockingRules(List<CallRule> rules) {
        for (CallRule rule : rules) {
            if (rule.isEnabled() && rule.isBlocking() && rule.isValid()) return true;
        }
        return false;
    }

    // evaluation

    /**
     * @param facts        the call
     * @param incomingCall true for a call being screened right now: enables repeated-caller
     *                     rules; false when describing numbers (call log, lookup)
     * @return the first matching rule, or null
     */
    public CallRule evaluate(CallFacts facts, boolean incomingCall) {
        List<CallRule> list = getRules();
        if (list.isEmpty()) return null;

        long now = clock.currentTimeMillis();
        CallRule rule = RuleEngine.evaluate(list, facts, now, clock.minuteOfDay(now),
                incomingCall ? recentCalls : null);
        LOG.debug("evaluate() {} -> {}", facts, rule);
        return rule;
    }

    /** Remembers an incoming call (after it was evaluated) for repeated-caller rules. */
    public void recordIncomingCall(CallFacts facts) {
        if (facts.hidden) return;
        recentCalls.record(facts.forms.key(), clock.currentTimeMillis());
    }

    // changes

    /**
     * Appends the rule (lowest priority).
     *
     * @return the added rule with its new id
     */
    public CallRule add(CallRule rule) throws IOException {
        synchronized (lock) {
            ensureLoaded();
            List<CallRule> list = new ArrayList<>(rules);
            long maxId = 0;
            for (CallRule r : list) maxId = Math.max(maxId, r.getId());
            CallRule added = rule.toBuilder().id(maxId + 1).build();
            list.add(added);
            replace(list);
            return added;
        }
    }

    /** Replaces the rule with the same id; unknown ids are ignored. */
    public void update(CallRule rule) throws IOException {
        synchronized (lock) {
            ensureLoaded();
            List<CallRule> list = new ArrayList<>(rules);
            int index = indexOf(list, rule.getId());
            if (index < 0) return;
            list.set(index, rule);
            replace(list);
        }
    }

    public void setEnabled(long id, boolean enabled) throws IOException {
        synchronized (lock) {
            ensureLoaded();
            List<CallRule> list = new ArrayList<>(rules);
            int index = indexOf(list, id);
            if (index < 0 || list.get(index).isEnabled() == enabled) return;
            list.set(index, list.get(index).toBuilder().enabled(enabled).build());
            replace(list);
        }
    }

    /** @return the removed rule, or null */
    public CallRule delete(long id) throws IOException {
        synchronized (lock) {
            ensureLoaded();
            List<CallRule> list = new ArrayList<>(rules);
            int index = indexOf(list, id);
            if (index < 0) return null;
            CallRule removed = list.remove(index);
            replace(list);
            return removed;
        }
    }

    /**
     * Inserts a rule at the position (to undo a deletion); keeps its id if free.
     */
    public void insert(int position, CallRule rule) throws IOException {
        synchronized (lock) {
            ensureLoaded();
            List<CallRule> list = new ArrayList<>(rules);
            if (indexOf(list, rule.getId()) >= 0 || rule.getId() <= 0) {
                long maxId = 0;
                for (CallRule r : list) maxId = Math.max(maxId, r.getId());
                rule = rule.toBuilder().id(maxId + 1).build();
            }
            list.add(Math.max(0, Math.min(position, list.size())), rule);
            replace(list);
        }
    }

    /**
     * Sets the priority order. Ids missing from {@code orderedIds} keep their relative
     * order after the listed ones; unknown ids are ignored.
     */
    public void reorder(List<Long> orderedIds) throws IOException {
        synchronized (lock) {
            ensureLoaded();
            List<CallRule> remaining = new ArrayList<>(rules);
            List<CallRule> list = new ArrayList<>(remaining.size());
            for (Long id : orderedIds) {
                int index = indexOf(remaining, id);
                if (index >= 0) list.add(remaining.remove(index));
            }
            list.addAll(remaining);
            if (list.equals(rules)) return;
            replace(list);
        }
    }

    private static int indexOf(List<CallRule> list, long id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId() == id) return i;
        }
        return -1;
    }

    private void replace(List<CallRule> list) throws IOException {
        store.save(list); // the cache only changes if the file was written
        rules = Collections.unmodifiableList(new ArrayList<>(list));
        notifyListener();
    }

    private void notifyListener() {
        Listener l = listener;
        if (l != null) {
            try {
                l.onRulesChanged(rules);
            } catch (Exception e) {
                LOG.warn("notifyListener() listener failed", e);
            }
        }
    }

}
