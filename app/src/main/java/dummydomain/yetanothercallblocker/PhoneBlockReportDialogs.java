package dummydomain.yetanothercallblocker;

import android.content.Context;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.UserMark;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockReporter;

/**
 * Dialogs of "send my marks to PhoneBlock": the one-time explanation with the choice
 * and the category of a spam report.
 */
public final class PhoneBlockReportDialogs {

    /** Labels of {@link PhoneBlockReporter.Category}, in the same order. */
    private static final int[] CATEGORY_LABELS = {
            R.string.pbreport_category_advertising,
            R.string.pbreport_category_poll,
            R.string.pbreport_category_fraud,
            R.string.pbreport_category_ping,
            R.string.pbreport_category_gamble,
            R.string.pbreport_category_other};

    private PhoneBlockReportDialogs() {}

    /**
     * Continues after the user set a mark in the UI (the mark is saved already):
     * shows the explanation the first time, then the category for a SPAM mark (the
     * report of a SPAM mark set with {@code deferSpam} is queued here).
     *
     * @param numberInfo the number, with the new mark
     * @param type       the new mark, null if it was removed
     * @param onChanged  called on the main thread when a report was queued or dropped
     *                   (to refresh the status), may be null
     */
    public static void afterMarkChanged(Context context, NumberInfo numberInfo,
                                        UserMark.Type type, Runnable onChanged) {
        if (!PhoneBlockReports.canReport(numberInfo)) return;
        String key = UserMarkActions.getMarkKey(numberInfo);

        Runnable next = () -> {
            if (type == UserMark.Type.SPAM) {
                showCategoryDialog(context, key, onChanged);
            } else {
                PhoneBlockReports.runAfterQueued(onChanged);
            }
        };

        if (PhoneBlockReports.isExplained()) {
            next.run();
        } else if (type != null) {
            showExplanation(context, next, onChanged);
        }
    }

    /**
     * The one-time explanation: "Send" keeps the setting on (and sends what is queued),
     * "Don't send" switches it off and drops the queue.
     *
     * @param onAccepted called after the user agreed
     */
    static void showExplanation(Context context, Runnable onAccepted, Runnable onDeclined) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.pbreport_explain_title)
                .setMessage(R.string.pbreport_explain_message)
                .setCancelable(false)
                .setPositiveButton(R.string.pbreport_explain_send, (d, w) -> {
                    PhoneBlockReports.setEnabled(context, true);
                    if (onAccepted != null) onAccepted.run();
                })
                .setNegativeButton(R.string.pbreport_explain_dont_send, (d, w) -> {
                    PhoneBlockReports.setEnabled(context, false);
                    PhoneBlockReports.runAfterQueued(onDeclined);
                })
                .show();
    }

    /**
     * Asks for the category of a spam report and queues it. Closing the dialog without
     * a choice sends the preselected (last used) category, "Don't send" drops it.
     */
    static void showCategoryDialog(Context context, String key, Runnable onChanged) {
        PhoneBlockReporter.Category[] categories = PhoneBlockReporter.Category.values();
        CharSequence[] labels = new CharSequence[categories.length];
        for (int i = 0; i < categories.length; i++) {
            labels[i] = context.getString(CATEGORY_LABELS[i]);
        }

        PhoneBlockReporter.Category initial = PhoneBlockReports.getCategory();
        int[] selected = {initial.ordinal()};
        boolean[] handled = {false};

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.pbreport_category_title)
                .setSingleChoiceItems(labels, selected[0], (d, which) -> selected[0] = which)
                .setPositiveButton(R.string.pbreport_category_send, (d, w) -> {
                    handled[0] = true;
                    PhoneBlockReporter.Category category = categories[selected[0]];
                    PhoneBlockReports.setCategory(category);
                    PhoneBlockReports.reportSpam(context, key, category);
                })
                .setNegativeButton(R.string.pbreport_category_dont_send, (d, w) -> {
                    handled[0] = true;
                    PhoneBlockReports.cancel(key);
                })
                .setOnDismissListener(d -> {
                    if (!handled[0]) PhoneBlockReports.reportSpam(context, key, initial);
                    PhoneBlockReports.runAfterQueued(onChanged);
                })
                .show();
    }

}
