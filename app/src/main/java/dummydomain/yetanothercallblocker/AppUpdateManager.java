package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import dummydomain.yetanothercallblocker.data.sources.OkHttpTransport;
import dummydomain.yetanothercallblocker.data.update.ApkDownloader;
import dummydomain.yetanothercallblocker.data.update.GitHubReleaseClient;
import dummydomain.yetanothercallblocker.data.update.ReleaseInfo;
import dummydomain.yetanothercallblocker.data.update.UpdateChecker;
import dummydomain.yetanothercallblocker.data.update.UpdateSecurity;
import dummydomain.yetanothercallblocker.utils.DeferredInit;
import okhttp3.OkHttpClient;

/**
 * App updates from GitHub Releases: the check (shared by {@code UpdateCheckWorker} and
 * the update screen), its stored state and the APK download, which keeps running when
 * the update screen is closed or recreated. Listeners are called on the main thread.
 */
public final class AppUpdateManager {

    /** The "check for updates" setting (on by default). */
    public static final String PREF_CHECK_ENABLED = "appUpdateCheckEnabled";
    static final String PREF_LAST_CHECK_TIME = "appUpdateLastCheckTime";
    static final String PREF_LATEST_VERSION = "appUpdateLatestVersion";
    static final String PREF_NOTIFIED_VERSION = "appUpdateNotifiedVersion";

    /** Subdirectory of the cache dir with the downloaded APK (see res/xml/file_paths.xml). */
    static final String UPDATES_DIR = "updates";

    static final String USER_AGENT = "CallGuard/" + BuildConfig.VERSION_NAME;

    /** Package name of the release builds (debug builds add ".debug"). */
    static final String RELEASE_PACKAGE_SUFFIX_DEBUG = ".debug";

    private static final Logger LOG = LoggerFactory.getLogger(AppUpdateManager.class);

    /** Receives changes of the download state. */
    public interface Listener {
        void onDownloadStateChanged(DownloadState state);
    }

    /** State of the APK download. Immutable. */
    public static final class DownloadState {

        public enum Status { IDLE, RUNNING, DONE, FAILED }

        static final DownloadState IDLE = new DownloadState(Status.IDLE, null, 0, -1, null, null);

        public final Status status;
        /** The release being downloaded, null when idle. */
        public final ReleaseInfo release;
        public final long downloaded;
        /** Expected size, -1 if unknown. */
        public final long total;
        /** The complete file ({@link Status#DONE}). */
        public final File file;
        /** The error message ({@link Status#FAILED}). */
        public final String error;

        DownloadState(Status status, ReleaseInfo release, long downloaded, long total,
                      File file, String error) {
            this.status = status;
            this.release = release;
            this.downloaded = downloaded;
            this.total = total;
            this.file = file;
            this.error = error;
        }

        public boolean isFor(ReleaseInfo other) {
            return release != null && other != null && release.equals(other);
        }
    }

    @SuppressLint("StaticFieldLeak") // the application context
    private static volatile AppUpdateManager instance;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "app-update");
        thread.setDaemon(true);
        return thread;
    });
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile OkHttpClient httpClient;
    private volatile ReleaseInfo lastRelease;
    private volatile DownloadState downloadState = DownloadState.IDLE;
    private volatile boolean cancelRequested;
    /** Time of the last progress notification, to limit UI updates. */
    private long lastProgressNanos;

    public static AppUpdateManager get(Context context) {
        AppUpdateManager m = instance;
        if (m == null) {
            synchronized (AppUpdateManager.class) {
                m = instance;
                if (m == null) instance = m = new AppUpdateManager(context);
            }
        }
        return m;
    }

    private AppUpdateManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // settings and stored state

    private static Settings settings() {
        return App.getSettings();
    }

    /** @return the "check for updates" setting */
    public boolean isCheckEnabled() {
        return settings().getBoolean(PREF_CHECK_ENABLED, true);
    }

    /**
     * @return true if updates are checked automatically: the setting is on and this is
     * not a debug build (debug builds can still check manually)
     */
    public boolean isAutoCheckActive() {
        return isAutoCheckAllowed() && isCheckEnabled();
    }

    /**
     * @return false for the "fdroid" flavor: it is updated by its app store and never
     * checks for, downloads or installs updates itself (all update UI is hidden)
     */
    public static boolean isSelfUpdateEnabled() {
        return BuildConfig.SELF_UPDATE;
    }

    /**
     * @return false for debug builds (they are never checked automatically) and for
     * builds without the self-updater
     */
    public static boolean isAutoCheckAllowed() {
        return isSelfUpdateEnabled() && !BuildConfig.DEBUG;
    }

    public long getLastCheckTime() {
        return store.getLastCheckTime();
    }

    /** @return the version of a newer release found by the last check, or null */
    public String getKnownUpdate() {
        return UpdateChecker.getKnownUpdate(store, BuildConfig.VERSION_NAME);
    }

    /** @return the release found by the last check in this process, or null */
    public ReleaseInfo getLastRelease() {
        return lastRelease;
    }

    private final UpdateChecker.Store store = new UpdateChecker.Store() {
        @Override
        public long getLastCheckTime() {
            return settings().getLong(PREF_LAST_CHECK_TIME, 0);
        }

        @Override
        public void setLastCheckTime(long time) {
            settings().setLong(PREF_LAST_CHECK_TIME, time);
        }

        @Override
        public String getLatestVersion() {
            return settings().getString(PREF_LATEST_VERSION);
        }

        @Override
        public void setLatestVersion(String version) {
            settings().setString(PREF_LATEST_VERSION, version);
        }

        @Override
        public String getNotifiedVersion() {
            return settings().getString(PREF_NOTIFIED_VERSION);
        }

        @Override
        public void setNotifiedVersion(String version) {
            settings().setString(PREF_NOTIFIED_VERSION, version);
        }
    };

    // check

    private OkHttpClient getHttpClient() {
        OkHttpClient client = httpClient;
        if (client == null) {
            synchronized (this) {
                client = httpClient;
                if (client == null) {
                    DeferredInit.initNetwork();
                    httpClient = client = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return client;
    }

    private UpdateChecker createChecker() {
        GitHubReleaseClient client = new GitHubReleaseClient(
                new OkHttpTransport(this::getHttpClient), USER_AGENT);
        return new UpdateChecker(client, store, BuildConfig.VERSION_NAME,
                System::currentTimeMillis);
    }

    /**
     * Checks GitHub for the latest release. Blocks: don't call on the main thread.
     */
    public UpdateChecker.Result check() throws IOException {
        UpdateChecker.Result result = createChecker().check();
        lastRelease = result.getRelease();
        return result;
    }

    /** Remembers that the user was notified about the version. */
    public void markNotified(String version) {
        store.setNotifiedVersion(version);
    }

    // download

    private ApkDownloader createDownloader() {
        return new ApkDownloader(this::getHttpClient, getUpdatesDir(), USER_AGENT);
    }

    File getUpdatesDir() {
        return new File(context.getCacheDir(), UPDATES_DIR);
    }

    public DownloadState getDownloadState() {
        return downloadState;
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** Starts downloading the APK of the release unless it is already being downloaded. */
    public void startDownload(ReleaseInfo release) {
        DownloadState state = downloadState;
        if (state.status == DownloadState.Status.RUNNING) {
            LOG.debug("startDownload() already running");
            return;
        }

        cancelRequested = false;
        setState(new DownloadState(DownloadState.Status.RUNNING, release, 0,
                release.getApkSize(), null, null));

        executor.execute(() -> {
            ApkDownloader downloader = createDownloader();
            try {
                File file = downloader.download(release, (downloaded, total) -> {
                    long now = System.nanoTime();
                    if (downloaded < total && now - lastProgressNanos
                            < TimeUnit.MILLISECONDS.toNanos(200)) {
                        return;
                    }
                    lastProgressNanos = now;
                    setState(new DownloadState(DownloadState.Status.RUNNING, release,
                            downloaded, total, null, null));
                }, () -> cancelRequested);

                String error = checkApk(file);
                if (error != null) {
                    downloader.clear();
                    setState(new DownloadState(DownloadState.Status.FAILED, release, 0, -1,
                            null, error));
                    return;
                }

                setState(new DownloadState(DownloadState.Status.DONE, release, file.length(),
                        file.length(), file, null));
            } catch (ApkDownloader.CancelledException e) {
                LOG.info("startDownload() cancelled");
                setState(DownloadState.IDLE);
            } catch (Exception e) {
                LOG.warn("startDownload() failed", e);
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                setState(new DownloadState(DownloadState.Status.FAILED, release, 0, -1,
                        null, message));
            }
        });
    }

    public void cancelDownload() {
        cancelRequested = true;
    }

    /** Forgets a finished or failed download (the file stays until the next download). */
    public void resetDownloadState() {
        if (downloadState.status != DownloadState.Status.RUNNING) setState(DownloadState.IDLE);
    }

    /**
     * Checks that the downloaded file is an APK of this app, signed with the same key
     * as the installed app (debug builds download the release app: they compare with the
     * installed release app and refuse if it is not installed).
     *
     * @return an error message or null if the file is fine
     */
    private String checkApk(File file) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo info = pm.getPackageArchiveInfo(file.getPath(), signatureFlags());
            if (info == null) return context.getString(R.string.update_error_not_apk);

            String expected = context.getPackageName();
            String reference = expected;
            if (expected.endsWith(RELEASE_PACKAGE_SUFFIX_DEBUG)) {
                // debug builds download the release APK (a separate app)
                expected = expected.substring(0,
                        expected.length() - RELEASE_PACKAGE_SUFFIX_DEBUG.length());
                reference = expected;
            }
            if (!expected.equals(info.packageName)) {
                LOG.warn("checkApk() unexpected package {}", info.packageName);
                return context.getString(R.string.update_error_wrong_package);
            }

            PackageInfo installed;
            try {
                installed = pm.getPackageInfo(reference, signatureFlags());
            } catch (PackageManager.NameNotFoundException e) {
                LOG.warn("checkApk() {} is not installed, can't verify the signature", reference);
                return context.getString(R.string.update_error_wrong_package);
            }

            List<String> installedSigners = new ArrayList<>();
            List<String> apkSigners = new ArrayList<>();
            List<String> apkHistory = new ArrayList<>();
            readSigners(installed, installedSigners, null);
            readSigners(info, apkSigners, apkHistory);
            if (!UpdateSecurity.signaturesMatch(installedSigners, apkSigners, apkHistory)) {
                LOG.warn("checkApk() signature mismatch: installed {}, apk {}",
                        installedSigners, apkSigners);
                return context.getString(R.string.update_error_wrong_package);
            }
        } catch (Exception e) {
            LOG.warn("checkApk()", e);
            return context.getString(R.string.update_error_not_apk);
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static int signatureFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    /**
     * Adds the SHA-256 digests of the current signers (and, if requested, of the
     * certificate history, oldest first) of the package.
     */
    @SuppressWarnings("deprecation")
    private static void readSigners(PackageInfo info, List<String> signers,
                                    List<String> history) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SigningInfo signingInfo = info.signingInfo;
            if (signingInfo == null) return;
            if (signingInfo.hasMultipleSigners()) {
                addDigests(signingInfo.getApkContentsSigners(), signers);
            } else {
                Signature[] chain = signingInfo.getSigningCertificateHistory();
                if (chain != null && chain.length > 0) {
                    // the last entry is the current signer
                    addDigests(new Signature[]{chain[chain.length - 1]}, signers);
                    if (history != null) addDigests(chain, history);
                }
            }
        } else {
            addDigests(info.signatures, signers);
        }
    }

    private static void addDigests(Signature[] signatures, List<String> out) {
        if (signatures == null) return;
        for (Signature signature : signatures) {
            if (signature != null) out.add(UpdateSecurity.sha256Hex(signature.toByteArray()));
        }
    }

    private void setState(DownloadState state) {
        downloadState = state;
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onDownloadStateChanged(downloadState);
            }
        });
    }

}
