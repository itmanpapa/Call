package dummydomain.yetanothercallblocker;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dummydomain.yetanothercallblocker.data.NumberInfo;
import dummydomain.yetanothercallblocker.data.UserMark;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.utils.PhoneUtils;

/**
 * Handles the actions of the call notifications: "Block" (adds the number to the
 * blacklist and ends the ringing call if possible) and "Not spam" (sets the
 * NOT_SPAM mark). Not exported: only reachable through our immutable PendingIntents.
 */
public class CallNotificationActionReceiver extends BroadcastReceiver {

    public static final String ACTION_BLOCK
            = "dummydomain.yetanothercallblocker.action.CALL_NOTIFICATION_BLOCK";
    public static final String ACTION_NOT_SPAM
            = "dummydomain.yetanothercallblocker.action.CALL_NOTIFICATION_NOT_SPAM";

    private static final String EXTRA_NUMBER = "number";
    private static final String EXTRA_NORMALIZED_NUMBER = "normalizedNumber";
    private static final String EXTRA_NAME = "name";
    private static final String EXTRA_NOTIFICATION_TAG = "notificationTag";
    private static final String EXTRA_NOTIFICATION_ID = "notificationId";

    private static final Logger LOG = LoggerFactory.getLogger(CallNotificationActionReceiver.class);

    public static PendingIntent createPendingIntent(Context context, String action,
                                                    NumberInfo numberInfo,
                                                    String notificationTag, int notificationId) {
        Intent intent = new Intent(context, CallNotificationActionReceiver.class)
                .setAction(action)
                .putExtra(EXTRA_NUMBER, numberInfo.number)
                .putExtra(EXTRA_NORMALIZED_NUMBER, numberInfo.normalizedNumber)
                .putExtra(EXTRA_NAME, getName(numberInfo))
                .putExtra(EXTRA_NOTIFICATION_TAG, notificationTag)
                .putExtra(EXTRA_NOTIFICATION_ID, notificationId);

        // distinct request codes, so the extras of different notifications don't mix
        int requestCode = (action + '|' + numberInfo.number + '|' + notificationTag).hashCode();

        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static String getName(NumberInfo numberInfo) {
        if (!TextUtils.isEmpty(numberInfo.name)) return numberInfo.name;
        if (numberInfo.featuredDatabaseItem != null) return numberInfo.featuredDatabaseItem.getName();
        return null;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String number = intent.getStringExtra(EXTRA_NUMBER);
        LOG.info("onReceive() action={}", action);

        if (TextUtils.isEmpty(number)
                || !(ACTION_BLOCK.equals(action) || ACTION_NOT_SPAM.equals(action))) {
            return;
        }

        String normalizedNumber = intent.getStringExtra(EXTRA_NORMALIZED_NUMBER);
        String name = intent.getStringExtra(EXTRA_NAME);
        String tag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG);
        int id = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0);

        Context appContext = context.getApplicationContext();

        // the notification is no longer needed whatever the outcome
        NotificationHelper.cancel(appContext, tag, id);

        if (ACTION_BLOCK.equals(action)) {
            // end the call right away, the blacklist write happens in the background
            endRingingCall(appContext);
        }

        PendingResult pendingResult = goAsync();
        Thread thread = new Thread(() -> {
            try {
                int messageResId;
                if (ACTION_BLOCK.equals(action)) {
                    messageResId = UserMarkActions.addToBlacklist(number, name)
                            ? R.string.notification_block_added
                            : R.string.notification_block_failed;
                } else {
                    String key = !TextUtils.isEmpty(normalizedNumber) ? normalizedNumber : number;
                    messageResId = UserMarkActions.setMark(key, number,
                            UserMark.Type.NOT_SPAM) != null
                            ? R.string.user_mark_set_not_spam
                            : R.string.user_mark_save_failed;
                }
                showToast(appContext, messageResId);
            } catch (Exception e) {
                LOG.error("onReceive() failed", e);
            } finally {
                pendingResult.finish();
            }
        }, "call-notification-action");
        thread.start();
    }

    private static void endRingingCall(Context context) {
        // never end a call while another one is active: TelecomManager would end
        // the active call instead of the ringing one (see PhoneUtils)
        PhoneStateHandler phoneStateHandler = YacbHolder.getPhoneStateHandler();
        boolean offHook = phoneStateHandler != null && phoneStateHandler.isOffHook();
        try {
            boolean ended = PhoneUtils.endCall(context, offHook);
            LOG.debug("endRingingCall() ended={}", ended);
        } catch (Exception e) {
            LOG.warn("endRingingCall() failed", e);
        }
    }

    private static void showToast(Context context, int messageResId) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context, messageResId, Toast.LENGTH_SHORT).show());
    }

}
