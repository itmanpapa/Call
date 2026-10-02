package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.text.TextUtils;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.SiaNumberCategoryUtils;
import dummydomain.yetanothercallblocker.data.UserMark;
import dummydomain.yetanothercallblocker.data.sources.MeasureType;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockClient;
import dummydomain.yetanothercallblocker.sia.model.NumberCategory;

public class NumberInfoUtils {

    public static String getShortDescription(Context context, NumberInfo numberInfo) {
        String description = getShortDescriptionWithoutRule(context, numberInfo);

        String rule = getRuleDescription(context, numberInfo);
        if (TextUtils.isEmpty(rule)) return description;
        if (TextUtils.isEmpty(description)) return rule;

        return context.getString(R.string.rules_description_with_details, rule, description);
    }

    /**
     * @return "Rule: …" for the call rule that matched the number, or null
     */
    public static String getRuleDescription(Context context, NumberInfo numberInfo) {
        if (numberInfo.matchedRule == null) return null;
        return context.getString(R.string.rules_matched_rule,
                RuleTexts.getTitle(context, numberInfo.matchedRule));
    }

    private static String getShortDescriptionWithoutRule(Context context, NumberInfo numberInfo) {
        String description = getBaseDescription(context, numberInfo);

        String source = getSourceDescription(context, numberInfo);
        if (TextUtils.isEmpty(source)) return description;
        if (TextUtils.isEmpty(description)) return source;

        return context.getString(R.string.info_description_with_source, description, source);
    }

    private static String getBaseDescription(Context context, NumberInfo numberInfo) {
        if (numberInfo.communityDatabaseItem != null) {
            NumberCategory category = NumberCategory.getById(
                    numberInfo.communityDatabaseItem.getCategory());

            if (category != null && category != NumberCategory.NONE) {
                return SiaNumberCategoryUtils.getName(context, category);
            }
        }

        if (numberInfo.blacklistItem != null && numberInfo.contactItem == null) {
            return context.getString(R.string.info_in_blacklist);
        }

        return null;
    }

    /**
     * @return the imported list that rated the number (with the list category if any),
     * e.g. "Bundesnetzagentur: Number disconnected", or null
     */
    public static String getSourceDescription(Context context, NumberInfo numberInfo) {
        if (numberInfo.userMark != null) return getUserMarkDescription(context, numberInfo.userMark);

        if (TextUtils.isEmpty(numberInfo.sourceName)) return null;

        String category = getSourceCategoryName(context, numberInfo.sourceCategory);
        if (TextUtils.isEmpty(category)) return numberInfo.sourceName;

        return context.getString(R.string.info_source_with_category,
                numberInfo.sourceName, category);
    }

    /**
     * @return e.g. "My mark: spam"
     */
    public static String getUserMarkDescription(Context context, UserMark mark) {
        return context.getString(R.string.info_source_with_category,
                context.getString(R.string.user_mark_source),
                context.getString(mark.isSpam()
                        ? R.string.user_mark_spam : R.string.user_mark_not_spam));
    }

    /**
     * @return a localized name for {@link MeasureType} categories, the category itself
     * for free-text categories, or null
     */
    public static String getSourceCategoryName(Context context, String category) {
        if (TextUtils.isEmpty(category)) return null;

        PhoneBlockClient.Rating phoneBlockRating = PhoneBlockClient.Rating.fromCode(category);
        if (phoneBlockRating != null && category.equals(phoneBlockRating.name())) {
            return getPhoneBlockRatingName(context, phoneBlockRating);
        }

        MeasureType measureType;
        try {
            measureType = MeasureType.valueOf(category);
        } catch (IllegalArgumentException e) {
            return category;
        }

        switch (measureType) {
            case DISCONNECTION:
                return context.getString(R.string.measure_disconnection);
            case BILLING_PROHIBITION:
                return context.getString(R.string.measure_billing_prohibition);
            case OTHER_PROHIBITION:
                return context.getString(R.string.measure_other_prohibition);
            case UNKNOWN:
                return context.getString(R.string.measure_unknown);
            default:
                return null;
        }
    }

    /**
     * @return a localized name of a PhoneBlock rating (the category of PhoneBlock numbers)
     */
    public static String getPhoneBlockRatingName(Context context, PhoneBlockClient.Rating rating) {
        switch (rating) {
            case B_MISSED:
                return context.getString(R.string.phoneblock_rating_b_missed);
            case C_PING:
                return context.getString(R.string.phoneblock_rating_c_ping);
            case D_POLL:
                return context.getString(R.string.phoneblock_rating_d_poll);
            case E_ADVERTISING:
                return context.getString(R.string.phoneblock_rating_e_advertising);
            case F_GAMBLE:
                return context.getString(R.string.phoneblock_rating_f_gamble);
            case G_FRAUD:
                return context.getString(R.string.phoneblock_rating_g_fraud);
            default:
                return null;
        }
    }

}
