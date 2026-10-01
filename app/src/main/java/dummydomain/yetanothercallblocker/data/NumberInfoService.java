package dummydomain.yetanothercallblocker.data;

import android.text.TextUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

import dummydomain.yetanothercallblocker.Settings;
import dummydomain.yetanothercallblocker.data.provider.ProviderResult;
import dummydomain.yetanothercallblocker.data.provider.YacbDatabaseProvider;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabaseItem;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabaseItem;

public class NumberInfoService {

    public interface HiddenNumberDetector {
        boolean isHiddenNumber(String number);
    }

    public interface NumberNormalizer {
        String normalizeNumber(String number, String countryCode);
    }

    private static final Logger LOG = LoggerFactory.getLogger(NumberInfoService.class);

    protected final Settings settings;

    protected final HiddenNumberDetector hiddenNumberDetector;
    protected final NumberNormalizer numberNormalizer;
    protected final CommunityDatabase communityDatabase;
    protected final FeaturedDatabase featuredDatabase;
    protected final ContactsProvider contactsProvider;
    protected final BlacklistService blacklistService;
    protected final SourcesManager sourcesManager;

    public NumberInfoService(Settings settings, HiddenNumberDetector hiddenNumberDetector,
                             NumberNormalizer numberNormalizer, CommunityDatabase communityDatabase,
                             FeaturedDatabase featuredDatabase, ContactsProvider contactsProvider,
                             BlacklistService blacklistService) {
        this(settings, hiddenNumberDetector, numberNormalizer, communityDatabase,
                featuredDatabase, contactsProvider, blacklistService, null);
    }

    /**
     * @param sourcesManager additional number sources (imported lists), may be null
     */
    public NumberInfoService(Settings settings, HiddenNumberDetector hiddenNumberDetector,
                             NumberNormalizer numberNormalizer, CommunityDatabase communityDatabase,
                             FeaturedDatabase featuredDatabase, ContactsProvider contactsProvider,
                             BlacklistService blacklistService, SourcesManager sourcesManager) {
        this.settings = settings;
        this.hiddenNumberDetector = hiddenNumberDetector;
        this.numberNormalizer = numberNormalizer;
        this.communityDatabase = communityDatabase;
        this.featuredDatabase = featuredDatabase;
        this.contactsProvider = contactsProvider;
        this.blacklistService = blacklistService;
        this.sourcesManager = sourcesManager;
    }

    public NumberInfo getNumberInfo(String number, String countryCode, boolean full) {
        return getNumberInfo(number, countryCode, full, false);
    }

    /**
     * @param allowOnline whether enabled online sources may be queried (blocks for up to
     *                    the online timeout); only for the incoming call path, not for lists
     */
    public NumberInfo getNumberInfo(String number, String countryCode, boolean full,
                                    boolean allowOnline) {
        LOG.debug("getNumberInfo({}, {}, {}, {}) started", number, countryCode, full, allowOnline);

        NumberInfo numberInfo = new NumberInfo();
        numberInfo.number = number;

        if (hiddenNumberDetector != null) {
            numberInfo.isHiddenNumber = hiddenNumberDetector.isHiddenNumber(number);
        }
        LOG.trace("getNumberInfo() isHiddenNumber={}", numberInfo.isHiddenNumber);

        if (numberInfo.isHiddenNumber || TextUtils.isEmpty(number)
                || TextUtils.getTrimmedLength(number) == 0) {
            numberInfo.noNumber = true;
        }
        LOG.trace("getNumberInfo() noNumber={}", numberInfo.noNumber);

        if (numberInfo.noNumber) {
            numberInfo.blockingReason = getBlockingReason(numberInfo);
            LOG.trace("getNumberInfo() blockingReason={}", numberInfo.blockingReason);
            LOG.debug("getNumberInfo() finished early");
            return numberInfo;
        }

        if (contactsProvider != null) {
            numberInfo.contactItem = contactsProvider.get(number);
        }
        LOG.trace("getNumberInfo() contactItem={}", numberInfo.contactItem);

        String normalizedNumber = numberInfo.normalizedNumber
                = numberNormalizer.normalizeNumber(number, countryCode);
        LOG.trace("getNumberInfo() normalizedNumber={}", numberInfo.normalizedNumber);

        // the YACB database can be disabled on the "Data sources" screen (enabled by default)
        boolean useYacbDatabase = sourcesManager == null
                || sourcesManager.isEnabled(YacbDatabaseProvider.ID);
        LOG.trace("getNumberInfo() useYacbDatabase={}", useYacbDatabase);

        if (communityDatabase != null && useYacbDatabase) {
            numberInfo.communityDatabaseItem = communityDatabase.getDbItemByNumber(normalizedNumber);
        }
        LOG.trace("getNumberInfo() communityItem={}", numberInfo.communityDatabaseItem);

        if (featuredDatabase != null && useYacbDatabase) {
            numberInfo.featuredDatabaseItem = featuredDatabase.getDbItemByNumber(normalizedNumber);
        }
        LOG.trace("getNumberInfo() featuredItem={}", numberInfo.featuredDatabaseItem);

        ContactItem contactItem = numberInfo.contactItem;
        FeaturedDatabaseItem featuredItem = numberInfo.featuredDatabaseItem;
        if (contactItem != null && !TextUtils.isEmpty(contactItem.displayName)) {
            numberInfo.name = contactItem.displayName;
        } else if (featuredItem != null && !TextUtils.isEmpty(featuredItem.getName())) {
            numberInfo.name = featuredItem.getName();
        }
        LOG.trace("getNumberInfo() name={}", numberInfo.name);

        CommunityDatabaseItem communityItem = numberInfo.communityDatabaseItem;
        if (communityItem != null && communityItem.hasRatings()) {
            if (communityItem.getNegativeRatingsCount() > communityItem.getPositiveRatingsCount()
                    + communityItem.getNeutralRatingsCount()) {
                numberInfo.rating = NumberInfo.Rating.NEGATIVE;
            } else if (communityItem.getPositiveRatingsCount() > communityItem.getNeutralRatingsCount()
                    + communityItem.getNegativeRatingsCount()) {
                numberInfo.rating = NumberInfo.Rating.POSITIVE;
            } else {
                numberInfo.rating = NumberInfo.Rating.NEUTRAL;
            }
        }
        LOG.trace("getNumberInfo() rating={}", numberInfo.rating);

        if (numberInfo.rating != NumberInfo.Rating.NEGATIVE) {
            applyListedNumbers(numberInfo, normalizedNumber);
        }

        if (allowOnline && numberInfo.rating != NumberInfo.Rating.NEGATIVE
                && numberInfo.contactItem == null) {
            applyOnlineSources(numberInfo, normalizedNumber);
        }

        if (blacklistService != null && settings.getBlacklistIsNotEmpty()) {
            // avoid loading blacklist if blocking for other reason
            if (full || getBlockingReason(numberInfo) == null) {
                numberInfo.blacklistItem = blacklistService.getBlacklistItemForNumber(number);
            }
        }
        LOG.trace("getNumberInfo() blacklistItem={}", numberInfo.blacklistItem);

        numberInfo.blockingReason = getBlockingReason(numberInfo);
        LOG.trace("getNumberInfo() blockingReason={}", numberInfo.blockingReason);

        LOG.debug("getNumberInfo() finished");
        return numberInfo;
    }

    /**
     * Consults the enabled imported lists (offline, synchronous). A listed number gets
     * the NEGATIVE rating, so the "block negative" setting applies to it as well.
     */
    protected void applyListedNumbers(NumberInfo numberInfo, String normalizedNumber) {
        if (sourcesManager == null || TextUtils.isEmpty(normalizedNumber)) return;

        ProviderResult result;
        try {
            result = sourcesManager.lookupListedNumbers(normalizedNumber);
        } catch (Exception e) {
            LOG.warn("applyListedNumbers() lookup failed", e);
            return;
        }
        applySourceResult(numberInfo, result);
    }

    /**
     * Consults the enabled online sources (PhoneBlock); waits at most the online timeout.
     */
    protected void applyOnlineSources(NumberInfo numberInfo, String normalizedNumber) {
        if (sourcesManager == null || TextUtils.isEmpty(normalizedNumber)) return;

        ProviderResult result;
        try {
            if (!sourcesManager.hasEnabledOnlineSources()) return;
            result = sourcesManager.lookupOnline(normalizedNumber);
        } catch (Exception e) {
            LOG.warn("applyOnlineSources() lookup failed", e);
            return;
        }
        applySourceResult(numberInfo, result);
    }

    private void applySourceResult(NumberInfo numberInfo, ProviderResult result) {
        if (result == null || result.getRating() != ProviderResult.Rating.NEGATIVE) return;

        numberInfo.rating = NumberInfo.Rating.NEGATIVE;
        numberInfo.sourceId = result.getSourceId();
        numberInfo.sourceName = sourcesManager.getDisplayName(result.getSourceId());
        if (numberInfo.sourceName == null) numberInfo.sourceName = result.getSourceId();
        numberInfo.sourceCategory = result.getCategory();

        if (numberInfo.name == null && !TextUtils.isEmpty(result.getName())) {
            numberInfo.name = result.getName();
        }

        LOG.trace("applySourceResult() source={}, category={}",
                numberInfo.sourceId, numberInfo.sourceCategory);
    }

    protected NumberInfo.BlockingReason getBlockingReason(NumberInfo numberInfo) {
        if (numberInfo.contactItem != null) return null;

        if (numberInfo.isHiddenNumber && settings.getBlockHiddenNumbers()) {
            return NumberInfo.BlockingReason.HIDDEN_NUMBER;
        }

        if (numberInfo.rating == NumberInfo.Rating.NEGATIVE
                && settings.getBlockNegativeSiaNumbers()
                && canBlock(NumberInfo.BlockingReason.SIA_RATING)) {
            return NumberInfo.BlockingReason.SIA_RATING;
        }

        if (numberInfo.blacklistItem != null && settings.getBlockBlacklisted()
                && canBlock(NumberInfo.BlockingReason.BLACKLISTED)) {
            return NumberInfo.BlockingReason.BLACKLISTED;
        }

        return null;
    }

    protected boolean canBlock(NumberInfo.BlockingReason reason) {
        if (contactsProvider == null || !contactsProvider.isInLimitedMode()) return true;

        if (reason == NumberInfo.BlockingReason.SIA_RATING
                && settings.isBlockingByRatingInLimitedModeAllowed()) {
            LOG.trace("canBlock() allowed: " + reason);
            return true;
        }

        if (reason == NumberInfo.BlockingReason.BLACKLISTED
                && settings.isBlockingBlacklistedInLimitedModeAllowed()) {
            LOG.trace("canBlock() allowed: " + reason);
            return true;
        }

        LOG.trace("canBlock() not allowed: " + reason);
        return false;
    }

    public boolean shouldBlock(NumberInfo numberInfo) {
        return numberInfo.blockingReason != null;
    }

    public void blockedCall(NumberInfo numberInfo) {
        if (blacklistService != null && numberInfo.blacklistItem != null
                && numberInfo.blockingReason == NumberInfo.BlockingReason.BLACKLISTED) {
            blacklistService.addCall(numberInfo.blacklistItem, new Date());
        }
    }

}
