package dummydomain.yetanothercallblocker.data;

import android.content.Context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import dummydomain.yetanothercallblocker.BuildConfig;
import dummydomain.yetanothercallblocker.NotificationService;
import dummydomain.yetanothercallblocker.PhoneStateHandler;
import dummydomain.yetanothercallblocker.data.db.BlacklistDao;
import dummydomain.yetanothercallblocker.data.db.YacbDaoSessionFactory;
import dummydomain.yetanothercallblocker.data.provider.InMemoryResultCache;
import dummydomain.yetanothercallblocker.data.provider.PhoneBlockOnlineProvider;
import dummydomain.yetanothercallblocker.data.provider.ProviderAggregator;
import dummydomain.yetanothercallblocker.data.provider.YacbDatabaseProvider;
import dummydomain.yetanothercallblocker.data.sources.BnetzaAutoUpdater;
import dummydomain.yetanothercallblocker.data.sources.NumberListStore;
import dummydomain.yetanothercallblocker.data.sources.OkHttpTransport;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockClient;
import dummydomain.yetanothercallblocker.data.sources.PhoneBlockSync;
import dummydomain.yetanothercallblocker.data.sources.OkHttpListDownloader;
import dummydomain.yetanothercallblocker.sia.Settings;
import dummydomain.yetanothercallblocker.sia.SettingsImpl;
import dummydomain.yetanothercallblocker.sia.Storage;
import dummydomain.yetanothercallblocker.sia.model.CommunityReviewsLoader;
import dummydomain.yetanothercallblocker.sia.model.SiaMetadata;
import dummydomain.yetanothercallblocker.sia.model.database.AbstractDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase;
import dummydomain.yetanothercallblocker.sia.model.database.DbManager;
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabase;
import dummydomain.yetanothercallblocker.sia.network.DbDownloader;
import dummydomain.yetanothercallblocker.sia.network.DbUpdateRequester;
import dummydomain.yetanothercallblocker.sia.network.OkHttpClientFactory;
import dummydomain.yetanothercallblocker.sia.network.WebService;
import dummydomain.yetanothercallblocker.sia.utils.Utils;
import dummydomain.yetanothercallblocker.utils.DbFilteringUtils;
import dummydomain.yetanothercallblocker.utils.DeferredInit;
import dummydomain.yetanothercallblocker.utils.SystemUtils;
import okhttp3.OkHttpClient;

import static dummydomain.yetanothercallblocker.data.SiaConstants.SIA_PATH_PREFIX;
import static dummydomain.yetanothercallblocker.data.SiaConstants.SIA_PROPERTIES;
import static dummydomain.yetanothercallblocker.data.SiaConstants.SIA_SECONDARY_PATH_PREFIX;

public class Config {

    /** Subdirectory of the files dir with the imported number lists. */
    static final String NUMBER_LISTS_DIR = "lists";

    /** Subdirectory of the files dir with the PhoneBlock sync state. */
    static final String PHONEBLOCK_DIR = "phoneblock";
    static final String PHONEBLOCK_STATE_FILE = "state.txt";

    static final String PHONEBLOCK_USER_AGENT_PREFIX = "YetAnotherCallBlocker/";

    /** How long answers of online sources are reused. */
    static final long ONLINE_CACHE_TTL_MILLIS = TimeUnit.DAYS.toMillis(1);

    private static final Logger LOG = LoggerFactory.getLogger(Config.class);

    private static volatile OkHttpClient phoneBlockHttpClient;

    /** Threads for online lookups: daemon, so they never keep the process alive. */
    private static class OnlineLookupThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "online-lookup-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    private static OkHttpClient getPhoneBlockHttpClient() {
        OkHttpClient client = phoneBlockHttpClient;
        if (client == null) {
            synchronized (Config.class) {
                client = phoneBlockHttpClient;
                if (client == null) {
                    DeferredInit.initNetwork();
                    phoneBlockHttpClient = client = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(60, TimeUnit.SECONDS)
                            .build();
                }
            }
        }
        return client;
    }

    private static class WSParameterProvider extends WebService.DefaultWSParameterProvider {
        final dummydomain.yetanothercallblocker.Settings settings;
        final SiaMetadata siaMetadata;
        final CommunityDatabase communityDatabase;

        volatile String appId;
        volatile long appIdTimestamp;

        WSParameterProvider(dummydomain.yetanothercallblocker.Settings settings,
                            SiaMetadata siaMetadata, CommunityDatabase communityDatabase) {
            this.settings = settings;
            this.siaMetadata = siaMetadata;
            this.communityDatabase = communityDatabase;
        }

        @Override
        public String getAppId() {
            String appId = this.appId;
            if (appId != null && System.nanoTime() >
                    appIdTimestamp + TimeUnit.MINUTES.toNanos(5)) {
                appId = null;
            }

            if (appId == null) {
                this.appId = appId = Utils.generateAppId();
                appIdTimestamp = System.nanoTime();
            }

            return appId;
        }

        @Override
        public int getAppVersion() {
            return siaMetadata.getSiaAppVersion();
        }

        @Override
        public String getOkHttpVersion() {
            return siaMetadata.getSiaOkHttpVersion();
        }

        @Override
        public int getDbVersion() {
            return communityDatabase.getEffectiveDbVersion();
        }

        @Override
        public SiaMetadata.Country getCountry() {
            return siaMetadata.getCountry(settings.getCountryCode());
        }
    }

    public static void init(Context context, dummydomain.yetanothercallblocker.Settings settings) {
        Storage storage = new AndroidStorage(context);
        Settings siaSettings
                = new SettingsImpl(new AndroidProperties(context, SIA_PROPERTIES));

        OkHttpClientFactory okHttpClientFactory = () -> {
            DeferredInit.initNetwork();
            return new OkHttpClient();
        };

        CommunityDatabase communityDatabase = new CommunityDatabase(
                storage, AbstractDatabase.Source.ANY, SIA_PATH_PREFIX,
                SIA_SECONDARY_PATH_PREFIX, siaSettings);
        YacbHolder.setCommunityDatabase(communityDatabase);

        SiaMetadata siaMetadata = new SiaMetadata(storage, SIA_PATH_PREFIX,
                communityDatabase::isUsingInternal);
        YacbHolder.setSiaMetadata(siaMetadata);

        FeaturedDatabase featuredDatabase = new FeaturedDatabase(
                storage, AbstractDatabase.Source.ANY, SIA_PATH_PREFIX);
        YacbHolder.setFeaturedDatabase(featuredDatabase);

        WSParameterProvider wsParameterProvider = new WSParameterProvider(
                settings, siaMetadata, communityDatabase);

        WebService webService = new WebService(wsParameterProvider, okHttpClientFactory);
        YacbHolder.setWebService(webService);

        YacbHolder.setDbManager(new DbManager(storage, SIA_PATH_PREFIX,
                new DbDownloader(okHttpClientFactory), new DbUpdateRequester(webService),
                communityDatabase));

        YacbHolder.getDbManager().setNumberFilter(DbFilteringUtils.getNumberFilter(settings));

        YacbHolder.setCommunityReviewsLoader(new CommunityReviewsLoader(webService));

        YacbDaoSessionFactory daoSessionFactory = new YacbDaoSessionFactory(context, "YACB");

        BlacklistDao blacklistDao = new BlacklistDao(daoSessionFactory::getDaoSession);
        YacbHolder.setBlacklistDao(blacklistDao);

        BlacklistService blacklistService = new BlacklistService(
                settings::setBlacklistIsNotEmpty, blacklistDao);
        YacbHolder.setBlacklistService(blacklistService);

        ContactsProvider contactsProvider = new ContactsProvider() {
            @Override
            public ContactItem get(String number) {
                return settings.getUseContacts() ? ContactsHelper.getContact(context, number) : null;
            }

            @Override
            public boolean isInLimitedMode() {
                return !SystemUtils.isUserUnlocked(context);
            }
        };

        PhoneBlockClient phoneBlockClient = new PhoneBlockClient(
                new OkHttpTransport(Config::getPhoneBlockHttpClient), null,
                PHONEBLOCK_USER_AGENT_PREFIX + BuildConfig.VERSION_NAME);
        PhoneBlockOnlineProvider phoneBlockOnlineProvider = new PhoneBlockOnlineProvider(
                phoneBlockClient, settings::getPhoneBlockToken, settings::getPhoneBlockMinVotes);

        NumberListStore numberListStore = new NumberListStore(
                new File(context.getFilesDir(), NUMBER_LISTS_DIR));
        SourcesManager sourcesManager = new SourcesManager(numberListStore, settings,
                Arrays.asList(
                        new YacbDatabaseProvider(communityDatabase, featuredDatabase),
                        phoneBlockOnlineProvider),
                Executors.newCachedThreadPool(new OnlineLookupThreadFactory()),
                new InMemoryResultCache(ONLINE_CACHE_TTL_MILLIS),
                ProviderAggregator.DEFAULT_ONLINE_TIMEOUT_MILLIS);
        YacbHolder.setSourcesManager(sourcesManager);

        YacbHolder.setPhoneBlockSync(new PhoneBlockSync(phoneBlockClient,
                new File(new File(context.getFilesDir(), PHONEBLOCK_DIR), PHONEBLOCK_STATE_FILE),
                sourcesManager::importList, System::currentTimeMillis));
        YacbHolder.setRemoteListManager(new RemoteListManager(sourcesManager,
                new OkHttpListDownloader(() -> {
                    DeferredInit.initNetwork();
                    return new OkHttpClient();
                })));
        // the Bundesnetzagentur site may reject unknown clients: use a browser user agent;
        // OkHttp follows the redirect of the short link by default
        YacbHolder.setBnetzaAutoUpdater(new BnetzaAutoUpdater(sourcesManager,
                new OkHttpListDownloader(() -> {
                    DeferredInit.initNetwork();
                    return new OkHttpClient();
                }, BnetzaAutoUpdater.MAX_PAGE_SIZE, OkHttpListDownloader.BROWSER_USER_AGENT)));

        // the lists are small, but don't read them on the main thread;
        // a lookup before the loading has finished waits for it
        Thread listLoader = new Thread(() -> {
            try {
                sourcesManager.loadLists();
            } catch (Exception e) {
                LOG.warn("init() failed to load number lists", e);
            }
        }, "number-lists-loader");
        listLoader.setDaemon(true);
        listLoader.start();

        NumberInfoService numberInfoService = new NumberInfoService(
                settings, NumberUtils::isHiddenNumber, NumberUtils::normalizeNumber,
                communityDatabase, featuredDatabase, contactsProvider, blacklistService,
                sourcesManager);
        YacbHolder.setNumberInfoService(numberInfoService);

        NotificationService notificationService = new NotificationService(context);
        YacbHolder.setNotificationService(notificationService);

        YacbHolder.setPhoneStateHandler(
                new PhoneStateHandler(context, settings, numberInfoService, notificationService));
    }

}
