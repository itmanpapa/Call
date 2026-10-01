package dummydomain.yetanothercallblocker.data.provider;

import java.util.Objects;

/**
 * Immutable information about a number returned by a single {@link NumberInfoProvider}.
 */
public final class ProviderResult {

    /**
     * Rating of a number. Mirrors {@code NumberInfo.Rating}, but is defined here
     * so that this package stays free of app (and Android) dependencies.
     */
    public enum Rating {
        POSITIVE, NEUTRAL, NEGATIVE, UNKNOWN
    }

    /** Value of {@link #getReviewCount()} when the number of reviews is not known. */
    public static final int UNKNOWN_REVIEW_COUNT = -1;

    private final String sourceId;
    private final Rating rating;
    private final String category;
    private final String name;
    private final int reviewCount;

    /**
     * @param sourceId    id of the provider that produced the result, not null
     * @param rating      rating, null is treated as {@link Rating#UNKNOWN}
     * @param category    provider-specific category key, may be null
     * @param name        caller name (company etc.), may be null
     * @param reviewCount number of reviews, or {@link #UNKNOWN_REVIEW_COUNT}
     *                    (any negative value) if not known
     */
    public ProviderResult(String sourceId, Rating rating, String category, String name,
                          int reviewCount) {
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.rating = rating != null ? rating : Rating.UNKNOWN;
        this.category = category;
        this.name = name;
        this.reviewCount = reviewCount >= 0 ? reviewCount : UNKNOWN_REVIEW_COUNT;
    }

    public ProviderResult(String sourceId, Rating rating) {
        this(sourceId, rating, null, null, UNKNOWN_REVIEW_COUNT);
    }

    public String getSourceId() {
        return sourceId;
    }

    public Rating getRating() {
        return rating;
    }

    /**
     * @return a provider-specific category key (for the YACB database it is the
     * {@code NumberCategory} constant name), or null
     */
    public String getCategory() {
        return category;
    }

    public String getName() {
        return name;
    }

    /**
     * @return the number of reviews, or {@link #UNKNOWN_REVIEW_COUNT}
     */
    public int getReviewCount() {
        return reviewCount;
    }

    public boolean hasReviewCount() {
        return reviewCount != UNKNOWN_REVIEW_COUNT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProviderResult)) return false;
        ProviderResult that = (ProviderResult) o;
        return reviewCount == that.reviewCount
                && sourceId.equals(that.sourceId)
                && rating == that.rating
                && Objects.equals(category, that.category)
                && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, rating, category, name, reviewCount);
    }

    @Override
    public String toString() {
        return "ProviderResult{" +
                "sourceId='" + sourceId + '\'' +
                ", rating=" + rating +
                ", category='" + category + '\'' +
                ", name='" + name + '\'' +
                ", reviewCount=" + reviewCount +
                '}';
    }

}
