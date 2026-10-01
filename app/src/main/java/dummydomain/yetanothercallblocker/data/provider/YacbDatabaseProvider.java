package dummydomain.yetanothercallblocker.data.provider;

import dummydomain.yetanothercallblocker.sia.model.NumberCategory;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabaseItem;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabaseItem;

/**
 * Offline provider backed by the existing YACB (Should I Answer) community
 * and featured databases. The databases are used as is, read-only.
 *
 * <p>The rating is computed exactly like in {@code NumberInfoService.getNumberInfo()};
 * keep both in sync until the call-blocking path is switched to the aggregator.</p>
 */
public class YacbDatabaseProvider implements NumberInfoProvider {

    public static final String ID = "yacb";

    private static final String DISPLAY_NAME = "YACB database";

    private final CommunityDatabase communityDatabase;
    private final FeaturedDatabase featuredDatabase;

    /**
     * @param communityDatabase community database (ratings, categories), may be null
     * @param featuredDatabase  featured database (names), may be null
     */
    public YacbDatabaseProvider(CommunityDatabase communityDatabase,
                                FeaturedDatabase featuredDatabase) {
        this.communityDatabase = communityDatabase;
        this.featuredDatabase = featuredDatabase;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public boolean isOffline() {
        return true;
    }

    @Override
    public ProviderResult lookup(String number) {
        if (number == null || number.isEmpty()) return null;

        CommunityDatabaseItem communityItem = communityDatabase != null
                ? communityDatabase.getDbItemByNumber(number) : null;
        FeaturedDatabaseItem featuredItem = featuredDatabase != null
                ? featuredDatabase.getDbItemByNumber(number) : null;

        if (communityItem == null && featuredItem == null) return null;

        ProviderResult.Rating rating = ProviderResult.Rating.UNKNOWN;
        String category = null;
        int reviewCount = ProviderResult.UNKNOWN_REVIEW_COUNT;

        if (communityItem != null) {
            rating = getRating(communityItem);
            category = getCategory(communityItem);
            reviewCount = communityItem.getPositiveRatingsCount()
                    + communityItem.getNeutralRatingsCount()
                    + communityItem.getNegativeRatingsCount();
        }

        String name = null;
        if (featuredItem != null) {
            String featuredName = featuredItem.getName();
            // same check as TextUtils.isEmpty() in NumberInfoService
            if (featuredName != null && !featuredName.isEmpty()) {
                name = featuredName;
            }
        }

        return new ProviderResult(ID, rating, category, name, reviewCount);
    }

    /**
     * Same rules as in {@code NumberInfoService.getNumberInfo()}.
     */
    static ProviderResult.Rating getRating(CommunityDatabaseItem item) {
        if (item == null || !item.hasRatings()) return ProviderResult.Rating.UNKNOWN;

        int positive = item.getPositiveRatingsCount();
        int neutral = item.getNeutralRatingsCount();
        int negative = item.getNegativeRatingsCount();

        if (negative > positive + neutral) {
            return ProviderResult.Rating.NEGATIVE;
        } else if (positive > neutral + negative) {
            return ProviderResult.Rating.POSITIVE;
        } else {
            return ProviderResult.Rating.NEUTRAL;
        }
    }

    /**
     * @return the {@link NumberCategory} constant name, the raw numeric id
     * if the category is not known to the library, or null for no category
     */
    static String getCategory(CommunityDatabaseItem item) {
        NumberCategory category = NumberCategory.getById(item.getCategory());
        if (category == null) return String.valueOf(item.getCategory());
        if (category == NumberCategory.NONE) return null;
        return category.name();
    }

}
