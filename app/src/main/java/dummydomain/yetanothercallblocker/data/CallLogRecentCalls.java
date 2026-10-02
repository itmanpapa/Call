package dummydomain.yetanothercallblocker.data;

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.data.rules.CountryCallingCodes;
import dummydomain.yetanothercallblocker.data.rules.NumberForms;
import dummydomain.yetanothercallblocker.data.rules.RecentCalls;
import dummydomain.yetanothercallblocker.data.rules.RecentCallsMemory;

/**
 * Looks for earlier incoming calls in the system call log. Only used by
 * {@link RecentCallsMemory} for the first minutes after a process start (before that the
 * in-memory record is complete), so the call log is rarely queried on the call path.
 */
class CallLogRecentCalls implements RecentCalls {

    /** The latest calls to look at; the repeat window is short. */
    private static final int MAX_CALLS = 10;

    private static final Logger LOG = LoggerFactory.getLogger(CallLogRecentCalls.class);

    private final Context context;
    private final Settings settings;

    CallLogRecentCalls(Context context, Settings settings) {
        this.context = context;
        this.settings = settings;
    }

    @Override
    public boolean hasCallBetween(String key, long fromMillis, long toMillis) {
        try {
            // returns an empty list without the permission
            List<CallLogItem> calls = CallLogHelper.loadLatestCalls(context, MAX_CALLS);

            // the same normalization as on the call path (see NumberInfoService)
            String countryCode = settings.getCachedAutoDetectedCountryCode();
            String homeCountry = settings.getCountryCode();
            String homeCallingCode = CountryCallingCodes.forRegion(
                    homeCountry != null && !homeCountry.isEmpty() ? homeCountry : countryCode);

            for (CallLogItem call : calls) {
                if (call.timestamp < fromMillis) break; // ordered from the newest
                if (call.timestamp > toMillis) continue;
                if (call.type == CallLogItem.Type.OUTGOING || call.number == null) continue;

                String normalized = NumberUtils.normalizeNumber(call.number, countryCode);
                if (key.equals(NumberForms.of(call.number, normalized, homeCallingCode).key())) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.warn("hasCallBetween() failed to query the call log", e);
        }
        return false;
    }

}
