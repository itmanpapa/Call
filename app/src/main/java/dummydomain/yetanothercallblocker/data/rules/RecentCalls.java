package dummydomain.yetanothercallblocker.data.rules;

/**
 * Knows about earlier incoming calls, for {@link RuleType#REPEATED_CALLER} rules.
 */
public interface RecentCalls {

    /**
     * @param key        the caller key, see {@link NumberForms#key()}
     * @param fromMillis start of the interval (inclusive)
     * @param toMillis   end of the interval (inclusive)
     * @return whether an incoming call from the number happened in the interval
     */
    boolean hasCallBetween(String key, long fromMillis, long toMillis);

}
