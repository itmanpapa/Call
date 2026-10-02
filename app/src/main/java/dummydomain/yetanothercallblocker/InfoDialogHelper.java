package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.SiaNumberCategoryUtils;
import dummydomain.yetanothercallblocker.data.UserMark;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.sia.model.NumberCategory;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabaseItem;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Date;

public class InfoDialogHelper {

    public static void showDialog(Context context, NumberInfo numberInfo,
                                  DialogInterface.OnDismissListener onDismissListener) {
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(!numberInfo.noNumber
                        ? numberInfo.number : context.getString(R.string.no_number));

        @SuppressLint("InflateParams")
        View view = LayoutInflater.from(context).inflate(R.layout.info_dialog, null);
        builder.setView(view);

        TextView categoryView = view.findViewById(R.id.category);

        NumberCategory category = numberInfo.communityDatabaseItem != null
                ? NumberCategory.getById(numberInfo.communityDatabaseItem.getCategory())
                : null;

        if (category != null && category != NumberCategory.NONE) {
            categoryView.setText(SiaNumberCategoryUtils.getName(context, category));
        } else {
            categoryView.setVisibility(View.GONE);
        }

        TextView nameView = view.findViewById(R.id.name);

        String contactName = numberInfo.contactItem != null
                ? numberInfo.contactItem.displayName : null;

        if (!TextUtils.isEmpty(contactName)) {
            nameView.setText(contactName);
        } else {
            nameView.setVisibility(View.GONE);
        }

        TextView featuredNameView = view.findViewById(R.id.featured_name);

        String featuredName = numberInfo.featuredDatabaseItem != null
                ? numberInfo.featuredDatabaseItem.getName() : null;

        if (!TextUtils.isEmpty(featuredName)) {
            featuredNameView.setText(featuredName);
        } else {
            featuredNameView.setVisibility(View.GONE);
        }

        String blacklistName = null;

        TextView inBlacklistView = view.findViewById(R.id.in_blacklist);
        if (numberInfo.blacklistItem != null) {
            blacklistName = numberInfo.blacklistItem.getName();
            if (numberInfo.contactItem != null) {
                inBlacklistView.setText(R.string.info_in_blacklist_contact);
            }
        } else {
            inBlacklistView.setVisibility(View.GONE);
        }

        TextView blacklistNameView = view.findViewById(R.id.blacklist_name);
        if (!TextUtils.isEmpty(blacklistName)) {
            blacklistNameView.setText(blacklistName);
        } else {
            blacklistNameView.setVisibility(View.GONE);
        }

        TextView sourceView = view.findViewById(R.id.source);
        String sourceDescription = NumberInfoUtils.getSourceDescription(context, numberInfo);
        if (!TextUtils.isEmpty(sourceDescription) && numberInfo.userMark == null) {
            sourceView.setText(context.getString(R.string.info_source, sourceDescription));
        } else {
            sourceView.setVisibility(View.GONE);
        }

        ReviewsSummaryHelper.populateSummary(view.findViewById(R.id.reviews_summary),
                numberInfo.communityDatabaseItem);

        initUserMarkViews(context, view, numberInfo);

        if (onDismissListener != null) builder.setOnDismissListener(onDismissListener);

        if (numberInfo.noNumber) {
            builder.show();
            return;
        }

        Runnable reviewsAction = () -> ReviewsActivity.startForNumber(context, numberInfo.number);

        Runnable webReviewAction = () -> {
            Uri uri = Uri.parse(YacbHolder.getWebService().getWebReviewsUrlPart()
                    + numberInfo.number);
            IntentHelper.startActivity(context, new Intent(Intent.ACTION_VIEW, uri));
        };

        Runnable addToBlacklistAction = () -> {
            FeaturedDatabaseItem featuredDatabaseItem = numberInfo.featuredDatabaseItem;
            String name = featuredDatabaseItem != null ? featuredDatabaseItem.getName() : null;
            context.startActivity(EditBlacklistItemActivity
                    .getIntent(context, name, numberInfo.number));
        };

        builder.setPositiveButton(R.string.add_web_review, null)
                .setNeutralButton(R.string.online_reviews, null)
                .setNegativeButton(R.string.add_to_blacklist, (dialog, which)
                        -> addToBlacklistAction.run());

        AlertDialog dialog = builder.create();

        // avoid dismissing the original dialog on button press

        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (numberInfo.contactItem != null) {
                    new MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.are_you_sure)
                            .setMessage(R.string.load_reviews_confirmation_message)
                            .setPositiveButton(R.string.yes, (d1, w) -> {
                                reviewsAction.run();
                                dialog.dismiss();
                            })
                            .setNegativeButton(R.string.no, null)
                            .show();
                } else {
                    reviewsAction.run();
                    dialog.dismiss();
                }
            });

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (numberInfo.contactItem != null) {
                    new MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.are_you_sure)
                            .setMessage(R.string.load_reviews_confirmation_message)
                            .setPositiveButton(R.string.yes, (d1, w) -> {
                                webReviewAction.run();
                                dialog.dismiss();
                            })
                            .setNegativeButton(R.string.no, null)
                            .show();
                } else {
                    webReviewAction.run();
                    dialog.dismiss();
                }
            });
        });

        dialog.show();
    }

    private static void initUserMarkViews(Context context, View view, NumberInfo numberInfo) {
        TextView statusView = view.findViewById(R.id.user_mark_status);
        TextView hintView = view.findViewById(R.id.user_mark_hint);
        View buttons = view.findViewById(R.id.user_mark_buttons);
        Button spamButton = view.findViewById(R.id.user_mark_spam);
        Button notSpamButton = view.findViewById(R.id.user_mark_not_spam);

        if (numberInfo.noNumber || YacbHolder.getUserMarksStore() == null) {
            statusView.setVisibility(View.GONE);
            hintView.setVisibility(View.GONE);
            buttons.setVisibility(View.GONE);
            return;
        }

        Runnable bind = () -> bindUserMark(context, numberInfo,
                statusView, hintView, spamButton, notSpamButton);
        bind.run();

        spamButton.setOnClickListener(v -> {
            toggleUserMark(context, numberInfo, UserMark.Type.SPAM);
            bind.run();
        });
        notSpamButton.setOnClickListener(v -> {
            toggleUserMark(context, numberInfo, UserMark.Type.NOT_SPAM);
            bind.run();
        });
    }

    /** Sets the mark, or clears it if the number already has a mark of this type. */
    private static void toggleUserMark(Context context, NumberInfo numberInfo, UserMark.Type type) {
        UserMark current = numberInfo.userMark;
        UserMark.Type newType = current != null && current.getType() == type ? null : type;

        UserMarkActions.Change change = UserMarkActions.setMark(numberInfo, newType);

        int messageResId;
        if (change == null) {
            messageResId = R.string.user_mark_save_failed;
        } else if (newType == null) {
            messageResId = R.string.user_mark_cleared;
        } else if (newType == UserMark.Type.SPAM) {
            messageResId = R.string.user_mark_set_spam;
        } else {
            messageResId = R.string.user_mark_set_not_spam;
        }
        Toast.makeText(context, messageResId, Toast.LENGTH_SHORT).show();
    }

    private static void bindUserMark(Context context, NumberInfo numberInfo,
                                     TextView statusView, TextView hintView,
                                     Button spamButton, Button notSpamButton) {
        UserMark mark = numberInfo.userMark;

        if (mark != null) {
            String date = android.text.format.DateFormat.getDateFormat(context)
                    .format(new Date(mark.getTimestamp()));
            statusView.setText(context.getString(R.string.user_mark_status,
                    context.getString(mark.isSpam()
                            ? R.string.user_mark_spam : R.string.user_mark_not_spam),
                    date));
            statusView.setVisibility(View.VISIBLE);
        } else {
            statusView.setVisibility(View.GONE);
        }

        // an explicit blacklist entry wins over the "not spam" mark
        boolean blacklistWins = mark != null && mark.isNotSpam()
                && numberInfo.blacklistItem != null && numberInfo.contactItem == null;
        hintView.setVisibility(blacklistWins ? View.VISIBLE : View.GONE);

        spamButton.setText(mark != null && mark.isSpam()
                ? R.string.user_mark_action_clear : R.string.user_mark_action_spam);
        notSpamButton.setText(mark != null && mark.isNotSpam()
                ? R.string.user_mark_action_clear : R.string.user_mark_action_not_spam);
    }

}
