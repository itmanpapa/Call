package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.color.DynamicColors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dummydomain.yetanothercallblocker.data.Config;
import dummydomain.yetanothercallblocker.utils.DebuggingUtils;
import dummydomain.yetanothercallblocker.utils.SystemUtils;
import dummydomain.yetanothercallblocker.work.BnetzaUpdateWorker;
import dummydomain.yetanothercallblocker.work.PhoneBlockSyncWorker;
import dummydomain.yetanothercallblocker.work.UpdateCheckWorker;

public class App extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(App.class);

    private static App instance;

    @SuppressLint("StaticFieldLeak")
    private static Settings settings;

    public static App getInstance() {
        return instance;
    }

    public static Settings getSettings() {
        return settings;
    }

    public static void setUiMode(int uiMode) {
        AppCompatDelegate.setDefaultNightMode(uiMode);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        instance = this;

        DebuggingUtils.setUpCrashHandler();

        DynamicColors.applyToActivitiesIfAvailable(this);

        new DeviceProtectedStorageMigrator().migrate(this);

        settings = new Settings(getDeviceProtectedStorageContext());
        settings.init();

        Config.init(getDeviceProtectedStorageContext(), settings);

        try {
            // WorkManager keeps its database in the credential-protected storage
            if (SystemUtils.isUserUnlocked(this)) {
                PhoneBlockSyncWorker.updateSchedule(this, false);
                // reports of marks queued while WorkManager was unavailable
                PhoneBlockReports.scheduleIfPending(this);
            }
        } catch (Exception e) {
            LOG.warn("onCreate() failed to schedule the PhoneBlock sync", e);
        }

        try {
            // the official list is enabled by default and kept up to date automatically
            if (SystemUtils.isUserUnlocked(this)) {
                BnetzaUpdateWorker.updateSchedule(this);
            }
        } catch (Exception e) {
            LOG.warn("onCreate() failed to schedule the Bundesnetzagentur list update", e);
        }

        try {
            // daily check for a new app release (never in debug builds)
            if (SystemUtils.isUserUnlocked(this)) {
                UpdateCheckWorker.updateSchedule(this);
            }
        } catch (Exception e) {
            LOG.warn("onCreate() failed to schedule the app update check", e);
        }

        setUiMode(settings.getUiMode());

        // the SMS receiver follows the setting (it may have been restored from a backup)
        SmsWarnings.syncReceiverState(this, settings.getSmsWarnings());

        if (settings.getUseMonitoringService()) {
            CallMonitoringService.start(this);
        }
    }

    private Context getDeviceProtectedStorageContext() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return createDeviceProtectedStorageContext();
        } else {
            return this;
        }
    }

}
