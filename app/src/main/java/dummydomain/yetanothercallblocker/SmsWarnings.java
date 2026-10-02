package dummydomain.yetanothercallblocker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.NumberInfoService;
import dummydomain.yetanothercallblocker.data.UserMark;
import dummydomain.yetanothercallblocker.data.UserMarksStore;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.rules.RuleAction;
import dummydomain.yetanothercallblocker.data.sms.SmsWarningPolicy;
import dummydomain.yetanothercallblocker.data.sms.SmsWarningPolicy.Decision;
import dummydomain.yetanothercallblocker.data.sms.SmsWarningPolicy.LinkScan;
import dummydomain.yetanothercallblocker.data.sms.SmsWarningPolicy.SenderFacts;
import dummydomain.yetanothercallblocker.data.sms.SmsWarningPolicy.SenderKind;
import dummydomain.yetanothercallblocker.utils.PackageManagerUtils;

import static dummydomain.yetanothercallblocker.IntentHelper.pendingActivity;

/**
 * Warnings about spam SMS: checks the senders of incoming messages (offline only, no
 * network) and shows a notification in the {@value #CHANNEL_ID} channel. The decision
 * itself is in {@link SmsWarningPolicy}. Message texts are only scanned in memory for
 * links (optional setting) and never kept, logged or sent.
 */
public final class SmsWarnings {

    public static final String CHANNEL_ID = "sms_warnings";

    private static final String NOTIFICATION_TAG_PREFIX = "smsWarning:";
    private static final int NOTIFICATION_ID = 200;

    /** The whole check must end well within the ~10 s a broadcast receiver may take. */
    private static final long TIMEOUT_MILLIS = 4500;

    private static final Logger LOG = LoggerFactory.getLogger(SmsWarnings.class);

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "sms-check-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private SmsWarnings() {}

    /**
     * Enables or disables the SMS receiver to match the setting. Cheap; called when the
     * setting changes and on app start (e.g. after a restored backup).
     */
    public static void syncReceiverState(Context context, boolean enabled) {
        try {
            PackageManagerUtils.setComponentEnabledOrDefault(context, SmsReceiver.class, enabled);
        } catch (Exception e) {
            LOG.warn("syncReceiverState()", e);
        }
    }

    /**
     * Checks the senders in the background and finishes within {@link #TIMEOUT_MILLIS}.
     *
     * @param messages  sender -> message text (empty unless the link check is on)
     * @param linkCheck the "warn about suspicious links" setting
     * @param onFinish  called when done (also after a timeout)
     */
    static void checkAsync(Context context, Map<String, StringBuilder> messages,
                           boolean linkCheck, Runnable onFinish) {
        Future<?> work;
        try {
            work = EXECUTOR.submit(() -> {
                for (Map.Entry<String, StringBuilder> e : messages.entrySet()) {
                    check(context, e.getKey(), linkCheck ? e.getValue() : null, linkCheck);
                    // drop the text as soon as possible
                    e.getValue().setLength(0);
                }
            });
        } catch (Exception e) {
            LOG.error("checkAsync() failed to start", e);
            onFinish.run();
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                work.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                LOG.warn("checkAsync() timed out");
                work.cancel(true);
            } catch (Exception e) {
                LOG.error("checkAsync() failed", e);
            } finally {
                onFinish.run();
            }
        });
    }

    private static void check(Context context, String rawSender, CharSequence text,
                              boolean linkCheck) {
        SenderKind kind = SmsWarningPolicy.classifySender(rawSender);
        String sender = SmsWarningPolicy.cleanSender(rawSender);
        if (kind == SenderKind.NONE || sender == null) return;

        NumberInfo numberInfo;
        SenderFacts facts;
        if (kind == SenderKind.NUMBER) {
            numberInfo = lookUp(sender);
            if (numberInfo == null) return;
            facts = factsOf(numberInfo);
        } else {
            // a name: only the user's own mark of exactly this sender applies
            numberInfo = new NumberInfo();
            numberInfo.number = sender;
            numberInfo.normalizedNumber = sender;
            numberInfo.userMark = getLiteralMark(sender);
            facts = new SenderFacts()
                    .userMarkSpam(numberInfo.userMark != null && numberInfo.userMark.isSpam())
                    .userMarkNotSpam(numberInfo.userMark != null
                            && numberInfo.userMark.isNotSpam());
        }

        LinkScan links = linkCheck ? SmsWarningPolicy.scanLinks(text) : LinkScan.NONE;
        Decision decision = SmsWarningPolicy.decide(kind, facts, linkCheck, links);
        LOG.debug("check() kind={}, {}, {}", kind, facts, decision);

        if (decision.getWarning() != SmsWarningPolicy.Warning.NONE) {
            show(context, kind, numberInfo, decision);
        }
    }

    private static NumberInfo lookUp(String number) {
        NumberInfoService service = YacbHolder.getNumberInfoService();
        if (service == null) {
            LOG.warn("lookUp() not initialized");
            return null;
        }
        try {
            // offline only: no network on this path
            return service.getNumberInfo(number,
                    App.getSettings().getCachedAutoDetectedCountryCode(), true, false);
        } catch (Exception e) {
            LOG.warn("lookUp() failed", e);
            return null;
        }
    }

    private static UserMark getLiteralMark(String sender) {
        UserMarksStore store = YacbHolder.getUserMarksStore();
        if (store == null) return null;
        try {
            return store.get(sender);
        } catch (Exception e) {
            LOG.warn("getLiteralMark() failed", e);
            return null;
        }
    }

    static SenderFacts factsOf(NumberInfo info) {
        UserMark mark = info.userMark;
        boolean reported = info.communityDatabaseItem != null
                && info.communityDatabaseItem.getNegativeRatingsCount() > 0;
        return new SenderFacts()
                .contact(info.contactItem != null)
                .userMarkSpam(mark != null && mark.isSpam())
                .userMarkNotSpam(mark != null && mark.isNotSpam())
                .blacklisted(info.blacklistItem != null)
                .blockRule(info.matchedRule != null
                        && info.matchedRule.getAction() == RuleAction.BLOCK)
                .allowRule(info.matchedRule != null
                        && info.matchedRule.getAction() == RuleAction.ALLOW)
                .ratingNegative(info.rating == NumberInfo.Rating.NEGATIVE)
                .reported(reported);
    }

    // notifications

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.sms_warning_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.sms_warning_channel_description));
        manager.createNotificationChannel(channel);
    }

    /** Creates the channel, so it can be configured right after enabling the feature. */
    public static void initChannel(Context context) {
        try {
            createChannel(context);
        } catch (Exception e) {
            LOG.warn("initChannel()", e);
        }
    }

    @SuppressLint("MissingPermission") // checked below
    private static void show(Context context, SenderKind kind, NumberInfo numberInfo,
                             Decision decision) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                LOG.info("show() no notification permission");
                return;
            }
            NotificationManagerCompat manager = NotificationManagerCompat.from(context);
            if (!manager.areNotificationsEnabled()) {
                LOG.info("show() notifications are disabled");
                return;
            }
            createChannel(context);

            String tag = NOTIFICATION_TAG_PREFIX + numberInfo.number;
            boolean spam = decision.getWarning() == SmsWarningPolicy.Warning.SPAM;
            String sender = !TextUtils.isEmpty(numberInfo.name)
                    && !numberInfo.name.equals(numberInfo.number)
                    ? numberInfo.name + " (" + numberInfo.number + ")" : numberInfo.number;

            String title;
            StringBuilder text = new StringBuilder();
            if (spam) {
                title = context.getString(R.string.sms_warning_title, sender);
                text.append(getSourceLine(context, numberInfo, decision));
                if (decision.hasLink()) {
                    text.append('\n').append(context.getString(R.string.sms_warning_link_hint));
                }
            } else {
                title = context.getString(R.string.sms_link_warning_title, sender);
                text.append(context.getString(R.string.sms_link_warning_text));
            }
            if (decision.getShortenerHost() != null) {
                text.append('\n').append(context.getString(R.string.sms_link_warning_shortener,
                        decision.getShortenerHost()));
            }
            String fullText = text.toString();
            int newline = fullText.indexOf('\n');
            String firstLine = newline >= 0 ? fullText.substring(0, newline) : fullText;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_warning_24dp)
                    .setColor(UiUtils.getColorInt(context,
                            spam ? R.color.rateNegative : R.color.rateNeutral))
                    .setContentTitle(title)
                    .setContentText(firstLine)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(fullText))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT);

            if (kind == SenderKind.NUMBER) {
                PendingIntent details = pendingActivity(context,
                        InfoDialogActivity.getIntent(context, numberInfo.number));
                builder.setContentIntent(details);
            }

            builder.addAction(R.drawable.ic_thumb_up_24dp,
                    context.getString(R.string.user_mark_action_not_spam),
                    CallNotificationActionReceiver.createPendingIntent(context,
                            CallNotificationActionReceiver.ACTION_NOT_SPAM, numberInfo,
                            tag, NOTIFICATION_ID));

            if (kind == SenderKind.NUMBER) {
                builder.addAction(R.drawable.ic_info_24dp,
                        context.getString(R.string.sms_warning_action_details),
                        pendingActivity(context,
                                InfoDialogActivity.getIntent(context, numberInfo.number)));
            }

            manager.notify(tag, NOTIFICATION_ID, builder.build());
        } catch (Exception e) {
            LOG.warn("show() failed", e);
        }
    }

    /**
     * @return e.g. "PhoneBlock", "Bundesnetzagentur: …", "My mark: spam", "In blacklist"
     */
    private static String getSourceLine(Context context, NumberInfo numberInfo,
                                        Decision decision) {
        SmsWarningPolicy.SpamReason reason = decision.getReason();
        if (reason == null) reason = SmsWarningPolicy.SpamReason.RATING;
        switch (reason) {
            case USER_MARK:
                if (numberInfo.userMark != null) {
                    return NumberInfoUtils.getUserMarkDescription(context, numberInfo.userMark);
                }
                break;
            case BLACKLIST:
                String name = numberInfo.blacklistItem != null
                        ? numberInfo.blacklistItem.getName() : null;
                return context.getString(R.string.info_in_blacklist)
                        + (!TextUtils.isEmpty(name) ? " (" + name + ")" : "");
            case RULE:
                String rule = NumberInfoUtils.getRuleDescription(context, numberInfo);
                if (!TextUtils.isEmpty(rule)) return rule;
                break;
            default:
                break;
        }
        String source = NumberInfoUtils.getSourceDescription(context, numberInfo);
        if (!TextUtils.isEmpty(source)) return source;
        String category = NumberInfoUtils.getCategoryName(context, numberInfo);
        String community = context.getString(R.string.sms_warning_source_community);
        return !TextUtils.isEmpty(category)
                ? context.getString(R.string.info_source_with_category, community, category)
                : community;
    }

}
