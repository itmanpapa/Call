package dummydomain.yetanothercallblocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receives incoming SMS (android.provider.Telephony.SMS_RECEIVED) to warn about spam
 * senders, see {@link SmsWarnings}. The app is not the default SMS app: the message is
 * delivered as usual, nothing is blocked or deleted.
 *
 * <p>The component is disabled in the manifest and only enabled while the
 * "check SMS senders" setting is on; it ignores everything when the setting is off.
 * Message texts are never stored, logged or sent anywhere: only with the optional link
 * check they are scanned in memory for links.</p>
 */
public class SmsReceiver extends BroadcastReceiver {

    private static final Logger LOG = LoggerFactory.getLogger(SmsReceiver.class);

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }

        Settings settings = App.getSettings();
        if (settings == null || !settings.getSmsWarnings()) {
            LOG.debug("onReceive() SMS warnings are off");
            return;
        }
        if (!PermissionHelper.hasSmsPermission(context)) {
            LOG.debug("onReceive() no SMS permission");
            return;
        }

        boolean linkCheck = settings.getSmsLinkWarnings();

        // sender -> text of all parts (the text only with the link check)
        Map<String, StringBuilder> messages = new LinkedHashMap<>();
        try {
            SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (parts == null) return;
            for (SmsMessage part : parts) {
                if (part == null) continue;
                String sender = part.getDisplayOriginatingAddress();
                if (sender == null) sender = part.getOriginatingAddress();
                if (sender == null) continue;

                StringBuilder text = messages.get(sender);
                if (text == null) {
                    text = new StringBuilder();
                    messages.put(sender, text);
                }
                if (linkCheck) {
                    String body = part.getDisplayMessageBody();
                    if (body != null) text.append(body);
                }
            }
        } catch (Exception e) {
            LOG.warn("onReceive() failed to parse the message", e);
            return;
        }
        if (messages.isEmpty()) return;

        LOG.debug("onReceive() senders={}", messages.size());

        PendingResult pendingResult = goAsync();
        SmsWarnings.checkAsync(context.getApplicationContext(), messages, linkCheck,
                pendingResult::finish);
    }

}
