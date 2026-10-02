package dummydomain.yetanothercallblocker;

import android.content.Context;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import dummydomain.yetanothercallblocker.data.rules.CallRule;
import dummydomain.yetanothercallblocker.data.rules.RuleAction;
import dummydomain.yetanothercallblocker.data.rules.RuleType;

/**
 * Localized texts of call rules (the "Rules" screen, notifications, the call log).
 */
public class RuleTexts {

    /** @return the rule name: the user's label or a description of what it matches */
    public static String getTitle(Context context, CallRule rule) {
        if (!TextUtils.isEmpty(rule.getLabel())) return rule.getLabel();

        switch (rule.getType()) {
            case NUMBER_PATTERN:
                return rule.getPatterns();
            case HIDDEN_NUMBER:
                return context.getString(R.string.rules_type_hidden);
            case FOREIGN_NUMBER:
                return context.getString(R.string.rules_type_foreign);
            case PREMIUM_DE:
                return context.getString(R.string.rules_type_premium_de);
            case REPEATED_CALLER:
                return context.getResources().getQuantityString(R.plurals.rules_repeated_title,
                        rule.getRepeatWindowMinutes(), rule.getRepeatWindowMinutes());
            default:
                return rule.getType().name();
        }
    }

    /**
     * @return the details: action, contacts, schedule (and the patterns of a labeled rule)
     */
    public static String getSummary(Context context, CallRule rule) {
        List<String> parts = new ArrayList<>();

        if (rule.getType() == RuleType.NUMBER_PATTERN && !TextUtils.isEmpty(rule.getLabel())) {
            parts.add(rule.getPatterns());
        } else if (rule.getType() == RuleType.PREMIUM_DE) {
            parts.add(context.getString(R.string.rules_premium_de_numbers));
        }

        parts.add(context.getString(rule.getAction() == RuleAction.BLOCK
                ? R.string.rules_action_block : R.string.rules_action_allow));

        if (rule.getAction() == RuleAction.BLOCK && rule.getType() != RuleType.HIDDEN_NUMBER) {
            parts.add(context.getString(rule.isExceptContacts()
                    ? R.string.rules_except_contacts_short : R.string.rules_including_contacts));
        }

        if (rule.isScheduleEnabled()) {
            parts.add(getScheduleText(context, rule));
        }

        if (!rule.isValid()) {
            parts.add(context.getString(R.string.rules_invalid));
        }

        return TextUtils.join(" · ", parts);
    }

    /** @return "22:00–07:00" in the user's time format, or "all day" */
    public static String getScheduleText(Context context, CallRule rule) {
        if (rule.getScheduleStart() == rule.getScheduleEnd()) {
            return context.getString(R.string.rules_schedule_all_day);
        }
        return context.getString(R.string.rules_schedule_range,
                formatTime(context, rule.getScheduleStart()),
                formatTime(context, rule.getScheduleEnd()));
    }

    /** @return the time of day (minutes since midnight) in the user's format */
    public static String formatTime(Context context, int minuteOfDay) {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        calendar.set(Calendar.MINUTE, minuteOfDay % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return android.text.format.DateFormat.getTimeFormat(context).format(calendar.getTime());
    }

}
