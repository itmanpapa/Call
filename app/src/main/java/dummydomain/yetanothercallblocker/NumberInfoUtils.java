package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.text.TextUtils;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.SiaNumberCategoryUtils;
import dummydomain.yetanothercallblocker.data.sources.MeasureType;
import dummydomain.yetanothercallblocker.sia.model.NumberCategory;

public class NumberInfoUtils {

    public static String getShortDescription(Context context, NumberInfo numberInfo) {
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
        if (TextUtils.isEmpty(numberInfo.sourceName)) return null;

        String category = getSourceCategoryName(context, numberInfo.sourceCategory);
        if (TextUtils.isEmpty(category)) return numberInfo.sourceName;

        return context.getString(R.string.info_source_with_category,
                numberInfo.sourceName, category);
    }

    /**
     * @return a localized name for {@link MeasureType} categories, the category itself
     * for free-text categories, or null
     */
    public static String getSourceCategoryName(Context context, String category) {
        if (TextUtils.isEmpty(category)) return null;

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

}
