package dummydomain.yetanothercallblocker;

import android.content.Context;

import dummydomain.yetanothercallblocker.data.NumberInfo;

public class NotificationService {

    private final Context context;

    public NotificationService(Context context) {
        this.context = context;
    }

    /** A call rings and is not blocked: the notification and the caller ID card. */
    public void startCallIndication(NumberInfo numberInfo) {
        NotificationHelper.showIncomingCallNotification(context, numberInfo);
        // the dialer's own heads-up hides ours, the card stays visible over it
        CallerIdOverlay.show(context, numberInfo, false);
    }

    /** The call was answered: the card would cover the in-call screen. */
    public void callAnswered() {
        CallerIdOverlay.hide();
    }

    public void stopAllCallsIndication() {
        NotificationHelper.hideIncomingCallNotification(context);
        CallerIdOverlay.onCallEnded();
    }

    public void notifyCallBlocked(NumberInfo numberInfo) {
        NotificationHelper.showBlockedCallNotification(context, numberInfo);
    }

}
