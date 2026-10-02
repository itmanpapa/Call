package dummydomain.yetanothercallblocker.data;

import dummydomain.yetanothercallblocker.data.db.BlacklistItem;
import dummydomain.yetanothercallblocker.data.rules.CallRule;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabaseItem;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabaseItem;

public class NumberInfo {

    public enum BlockingReason {
        HIDDEN_NUMBER, SIA_RATING, BLACKLISTED,
        // a BLOCK call rule, see matchedRule
        RULE
    }

    public enum Rating {
        POSITIVE, NEUTRAL, NEGATIVE, UNKNOWN
    }

    // id
    public String number;
    public String normalizedNumber;

    // info from various sources
    public boolean isHiddenNumber;
    public ContactItem contactItem;
    public CommunityDatabaseItem communityDatabaseItem;
    public FeaturedDatabaseItem featuredDatabaseItem;
    public BlacklistItem blacklistItem;

    // computed rating
    public Rating rating = Rating.UNKNOWN;

    // set if the NEGATIVE rating comes from an imported list (see SourcesManager),
    // null otherwise
    public String sourceId;
    public String sourceName;
    public String sourceCategory;

    // the first matching call rule (see data/rules), null if none;
    // a BLOCK rule blocks like the blacklist, an ALLOW rule beats ratings and lists
    public CallRule matchedRule;

    // precomputed for convenience
    public boolean noNumber;
    public String name;
    public BlockingReason blockingReason;

}
