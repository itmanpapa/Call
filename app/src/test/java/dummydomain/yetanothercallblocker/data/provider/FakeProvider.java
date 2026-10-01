package dummydomain.yetanothercallblocker.data.provider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configurable provider for tests.
 */
class FakeProvider implements NumberInfoProvider {

    private final String id;
    private final boolean offline;
    private boolean trusted = true;
    private ProviderResult result;
    private RuntimeException exception;
    private long delayMillis;
    private CountDownLatch gate;

    final AtomicInteger calls = new AtomicInteger();

    private FakeProvider(String id, boolean offline) {
        this.id = id;
        this.offline = offline;
    }

    static FakeProvider offline(String id) {
        return new FakeProvider(id, true);
    }

    static FakeProvider online(String id) {
        return new FakeProvider(id, false);
    }

    FakeProvider returning(ProviderResult.Rating rating) {
        return returning(new ProviderResult(id, rating));
    }

    FakeProvider returning(ProviderResult.Rating rating, String category, String name) {
        return returning(new ProviderResult(id, rating, category, name,
                ProviderResult.UNKNOWN_REVIEW_COUNT));
    }

    FakeProvider returning(ProviderResult result) {
        this.result = result;
        return this;
    }

    FakeProvider throwing(RuntimeException exception) {
        this.exception = exception;
        return this;
    }

    FakeProvider untrusted() {
        this.trusted = false;
        return this;
    }

    FakeProvider delayed(long delayMillis) {
        this.delayMillis = delayMillis;
        return this;
    }

    /** The lookup blocks until the latch is released. */
    FakeProvider blockedBy(CountDownLatch gate) {
        this.gate = gate;
        return this;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getDisplayName() {
        return "Fake " + id;
    }

    @Override
    public boolean isOffline() {
        return offline;
    }

    @Override
    public boolean isTrusted() {
        return trusted;
    }

    @Override
    public ProviderResult lookup(String number) throws Exception {
        calls.incrementAndGet();
        if (delayMillis > 0) Thread.sleep(delayMillis);
        if (gate != null && !gate.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("gate was not released");
        }
        if (exception != null) throw exception;
        return result;
    }

}
