package dummydomain.yetanothercallblocker.data.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import dummydomain.yetanothercallblocker.data.sources.PhoneBlockClient;

/**
 * Online provider that looks up a number with the PhoneBlock {@code /check} API.
 *
 * <p>Only the SHA-1 hash of the number is sent (the number itself never leaves the
 * device in plain text, although the hash of a phone number can be reversed by brute
 * force). The provider is disabled by default ({@link #isEnabledByDefault()}) and
 * does nothing without an API key.</p>
 *
 * <p>Rating rules (like the official PhoneBlock app's {@code CallChecker}):</p>
 * <ul>
 *     <li>the number is on the user's personal PhoneBlock blacklist, or it has at least
 *     {@code minVotes} votes &rarr; {@link ProviderResult.Rating#NEGATIVE};</li>
 *     <li>the number is on the global whitelist &rarr; {@link ProviderResult.Rating#POSITIVE};</li>
 *     <li>otherwise no information (null). Range ("wildcard") votes are not used.</li>
 * </ul>
 */
public class PhoneBlockOnlineProvider implements NumberInfoProvider {

    public static final String ID = "phoneblock_online";
    public static final String DISPLAY_NAME = "PhoneBlock (online)";

    private static final Logger LOG = LoggerFactory.getLogger(PhoneBlockOnlineProvider.class);

    private final PhoneBlockClient client;
    private final Supplier<String> tokenSupplier;
    private final IntSupplier minVotesSupplier;

    /**
     * @param client           API client
     * @param tokenSupplier    supplies the current API key (may supply null or "")
     * @param minVotesSupplier supplies the current vote threshold
     */
    public PhoneBlockOnlineProvider(PhoneBlockClient client, Supplier<String> tokenSupplier,
                                    IntSupplier minVotesSupplier) {
        this.client = Objects.requireNonNull(client, "client");
        this.tokenSupplier = Objects.requireNonNull(tokenSupplier, "tokenSupplier");
        this.minVotesSupplier = Objects.requireNonNull(minVotesSupplier, "minVotesSupplier");
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public boolean isOffline() {
        return false;
    }

    @Override
    public boolean isEnabledByDefault() {
        return false;
    }

    /**
     * @return true if an API key is configured
     */
    public boolean hasToken() {
        String token = tokenSupplier.get();
        return token != null && !token.trim().isEmpty();
    }

    @Override
    public ProviderResult lookup(String number) throws Exception {
        String token = tokenSupplier.get();
        if (token == null || token.trim().isEmpty()) return null;

        String e164 = ListedNumbersProvider.toE164(number);
        if (e164 == null) return null;

        PhoneBlockClient.PhoneInfo info = client.check(token, e164);
        LOG.trace("lookup() info={}", info);
        return toResult(info, minVotesSupplier.getAsInt());
    }

    ProviderResult toResult(PhoneBlockClient.PhoneInfo info, int minVotes) {
        String category = info.getRating() != null
                && info.getRating() != PhoneBlockClient.Rating.A_LEGITIMATE
                ? info.getRating().name() : null;

        if (info.isBlackListed() || (info.isKnown() && info.getVotes() >= minVotes
                && info.getRating() != PhoneBlockClient.Rating.A_LEGITIMATE)) {
            return new ProviderResult(ID, ProviderResult.Rating.NEGATIVE, category,
                    null, info.getVotes());
        }
        if (info.isWhiteListed()) {
            return new ProviderResult(ID, ProviderResult.Rating.POSITIVE, null, null,
                    info.getVotes());
        }
        return null;
    }

}
