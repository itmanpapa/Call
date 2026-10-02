package dummydomain.yetanothercallblocker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The "a new version is available" notification, in a channel of its own
 * ({@value #CHANNEL_ID}, low importance: no sound, no heads-up).
 */
public final class UpdateNotifications {

    public static final String CHANNEL_ID = "app_updates";

    private static final String NOTIFICATION_TAG = "appUpdate";
    private static final int NOTIFICATION_ID = 100;

    private static final Logger LOG = LoggerFactory.getLogger(UpdateNotifications.class);

    private UpdateNotifications() {}

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.update_notification_channel_description));
        manager.createNotificationChannel(channel);
    }

    /**
     * Shows the notification about the version.
     *
     * @return true if it was posted (notifications are allowed)
     */
    @SuppressLint("MissingPermission") // checked below
    public static boolean showUpdateAvailable(Context context, String version) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                LOG.info("showUpdateAvailable() no notification permission");
                return false;
            }
            NotificationManagerCompat manager = NotificationManagerCompat.from(context);
            if (!manager.areNotificationsEnabled()) {
                LOG.info("showUpdateAvailable() notifications are disabled");
                return false;
            }

            createChannel(context);

            Intent intent = UpdateActivity.getIntent(context)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent contentIntent = PendingIntent.getActivity(context, NOTIFICATION_ID,
                    intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_file_download_24dp)
                    .setContentTitle(context.getString(R.string.update_available_title, version))
                    .setContentText(context.getString(R.string.update_notification_text))
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                    .setPriority(NotificationCompat.PRIORITY_LOW);

            manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, builder.build());
            return true;
        } catch (Exception e) {
            LOG.warn("showUpdateAvailable()", e);
            return false;
        }
    }

    /** Removes the notification (e.g. when the update screen is opened). */
    public static void cancel(Context context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_TAG, NOTIFICATION_ID);
    }

}
