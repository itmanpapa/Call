package dummydomain.yetanothercallblocker.data.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Combined information about a number produced by {@link ProviderAggregator}.
 */
public final class AggregatedResult {

    private final ProviderResult.Rating rating;
    private final String ratingSourceId;
    private final String category;
    private final String name;
    private final List<ProviderResult> results;
    private final List<String> failedProviderIds;
    private final List<String> timedOutProviderIds;

    AggregatedResult(ProviderResult.Rating rating, String ratingSourceId,
                     String category, String name, List<ProviderResult> results,
                     List<String> failedProviderIds, List<String> timedOutProviderIds) {
        this.rating = rating;
        this.ratingSourceId = ratingSourceId;
        this.category = category;
        this.name = name;
        this.results = Collections.unmodifiableList(new ArrayList<>(results));
        this.failedProviderIds = Collections.unmodifiableList(new ArrayList<>(failedProviderIds));
        this.timedOutProviderIds
                = Collections.unmodifiableList(new ArrayList<>(timedOutProviderIds));
    }

    /**
     * @return the combined rating, {@link ProviderResult.Rating#UNKNOWN} if no provider knows it
     */
    public ProviderResult.Rating getRating() {
        return rating;
    }

    /**
     * @return id of the provider whose rating was chosen, or null if the rating is unknown
     */
    public String getRatingSourceId() {
        return ratingSourceId;
    }

    /**
     * @return the category of the rating source if it has one,
     * otherwise the first category in provider order; may be null
     */
    public String getCategory() {
        return category;
    }

    /**
     * @return the first non-empty name in provider order, may be null
     */
    public String getName() {
        return name;
    }

    /**
     * @return non-null results of all providers that answered in time, in provider order
     */
    public List<ProviderResult> getResults() {
        return results;
    }

    /**
     * @return ids of the contributing providers (those in {@link #getResults()}), in provider order
     */
    public List<String> getSourceIds() {
        List<String> ids = new ArrayList<>(results.size());
        for (ProviderResult result : results) {
            ids.add(result.getSourceId());
        }
        return ids;
    }

    /**
     * @return ids of providers whose lookup threw an exception or could not be started
     */
    public List<String> getFailedProviderIds() {
        return failedProviderIds;
    }

    /**
     * @return ids of online providers that did not answer within the timeout
     */
    public List<String> getTimedOutProviderIds() {
        return timedOutProviderIds;
    }

    public boolean hasInfo() {
        return !results.isEmpty();
    }

    @Override
    public String toString() {
        return "AggregatedResult{" +
                "rating=" + rating +
                ", ratingSourceId='" + ratingSourceId + '\'' +
                ", category='" + category + '\'' +
                ", name='" + name + '\'' +
                ", results=" + results +
                ", failedProviderIds=" + failedProviderIds +
                ", timedOutProviderIds=" + timedOutProviderIds +
                '}';
    }

}
