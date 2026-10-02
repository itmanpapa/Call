package dummydomain.yetanothercallblocker.data.sms;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether an incoming SMS gets a warning notification ("possible spam SMS").
 * The app is not the default SMS app: it can neither block nor delete messages, it only
 * warns. Plain Java, no Android dependencies, so the rules can be unit-tested.
 *
 * <p>Rules (highest priority first):</p>
 * <ol>
 *     <li>No sender at all: nothing to check.</li>
 *     <li>The user's "not spam" mark: no warning, unless the sender is on the explicit
 *     blacklist (the blacklist beats the mark, like for calls).</li>
 *     <li>An ALLOW call rule: no warning, unless blacklisted.</li>
 *     <li>Contacts: only the user's own SPAM mark or a BLOCK rule that explicitly
 *     includes contacts warn (ratings and lists never do, like for calls).</li>
 *     <li>SPAM warning: user mark SPAM, blacklist, BLOCK rule, NEGATIVE rating
 *     (YACB database, PhoneBlock, Bundesnetzagentur, imported lists).</li>
 *     <li>Only with the optional link check: a softer "suspicious link" warning for a
 *     message from an unknown sender with a link through a URL shortener, or with any
 *     link when some source reported the sender (but not enough for a NEGATIVE rating).</li>
 * </ol>
 *
 * <p>Alphanumeric senders ("DHL", "PayPal") can't be looked up in the number sources;
 * blacklist and rule patterns only contain digits, so only a mark the user set for the
 * exact sender name applies to them (plus the link check).</p>
 */
public final class SmsWarningPolicy {

    /** What kind of sender address an SMS has. */
    public enum SenderKind {
        /** No sender (empty or missing). */
        NONE,
        /** A phone number or a short code: looked up like a caller. */
        NUMBER,
        /** A name such as "DHL" (contains letters or other characters). */
        ALPHANUMERIC
    }

    /** The outcome for one message. */
    public enum Warning {
        NONE,
        /** "Possible spam SMS from ..." */
        SPAM,
        /** "Message with a link from an unknown sender: be careful". */
        SUSPICIOUS_LINK
    }

    /** Why a {@link Warning#SPAM} warning is shown (the source line of the notification). */
    public enum SpamReason {
        USER_MARK,
        BLACKLIST,
        RULE,
        RATING
    }

    /** Facts about the sender, from the number lookup (all false if unknown). */
    public static final class SenderFacts {
        boolean contact;
        boolean userMarkSpam;
        boolean userMarkNotSpam;
        boolean blacklisted;
        boolean blockRule;
        boolean allowRule;
        boolean ratingNegative;
        boolean reported;

        public SenderFacts contact(boolean value) {
            contact = value;
            return this;
        }

        /** The user marked the sender as spam ("My mark"). */
        public SenderFacts userMarkSpam(boolean value) {
            userMarkSpam = value;
            return this;
        }

        /** The user marked the sender as not spam. */
        public SenderFacts userMarkNotSpam(boolean value) {
            userMarkNotSpam = value;
            return this;
        }

        /** The sender matches an entry of the blacklist. */
        public SenderFacts blacklisted(boolean value) {
            blacklisted = value;
            return this;
        }

        /** A BLOCK call rule matches the sender. */
        public SenderFacts blockRule(boolean value) {
            blockRule = value;
            return this;
        }

        /** An ALLOW call rule matches the sender. */
        public SenderFacts allowRule(boolean value) {
            allowRule = value;
            return this;
        }

        /** The computed rating is NEGATIVE (databases, lists or the user's SPAM mark). */
        public SenderFacts ratingNegative(boolean value) {
            ratingNegative = value;
            return this;
        }

        /**
         * Some source has negative reports about the sender, but not enough for a
         * NEGATIVE rating (e.g. a few negative reviews in the YACB database).
         */
        public SenderFacts reported(boolean value) {
            reported = value;
            return this;
        }

        @Override
        public String toString() {
            return "SenderFacts{contact=" + contact + ", markSpam=" + userMarkSpam
                    + ", markNotSpam=" + userMarkNotSpam + ", blacklisted=" + blacklisted
                    + ", blockRule=" + blockRule + ", allowRule=" + allowRule
                    + ", negative=" + ratingNegative + ", reported=" + reported + '}';
        }
    }

    /** Links found in a message text. Only flags and the shortener host are kept. */
    public static final class LinkScan {

        public static final LinkScan NONE = new LinkScan(false, null);

        private final boolean hasLink;
        private final String shortenerHost;

        LinkScan(boolean hasLink, String shortenerHost) {
            this.hasLink = hasLink;
            this.shortenerHost = shortenerHost;
        }

        /** @return whether the text contains a web link */
        public boolean hasLink() {
            return hasLink;
        }

        /** @return the host of the first link through a URL shortener, or null */
        public String getShortenerHost() {
            return shortenerHost;
        }

        @Override
        public String toString() {
            return "LinkScan{hasLink=" + hasLink + ", shortener=" + shortenerHost + '}';
        }
    }

    /** The decision for one message. Immutable. */
    public static final class Decision {

        public static final Decision NONE = new Decision(Warning.NONE, null, false, null);

        private final Warning warning;
        private final SpamReason reason;
        private final boolean hasLink;
        private final String shortenerHost;

        Decision(Warning warning, SpamReason reason, boolean hasLink, String shortenerHost) {
            this.warning = warning;
            this.reason = reason;
            this.hasLink = hasLink;
            this.shortenerHost = shortenerHost;
        }

        public Warning getWarning() {
            return warning;
        }

        /** @return the reason of a {@link Warning#SPAM} warning, null otherwise */
        public SpamReason getReason() {
            return reason;
        }

        /** @return whether the message contains a link (only known with the link check) */
        public boolean hasLink() {
            return hasLink;
        }

        /** @return the URL shortener the message links to, or null */
        public String getShortenerHost() {
            return shortenerHost;
        }

        @Override
        public String toString() {
            return "Decision{" + warning + (reason != null ? ", " + reason : "")
                    + ", hasLink=" + hasLink
                    + (shortenerHost != null ? ", shortener=" + shortenerHost : "") + '}';
        }
    }

    /**
     * Well-known public URL shorteners: the link hides the real address, a classic
     * of phishing messages ("your parcel: bit.ly/...").
     */
    public static final Set<String> URL_SHORTENERS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "bit.ly", "bitly.com", "t.ly", "tinyurl.com", "is.gd", "v.gd",
                    "cutt.ly", "goo.gl", "ow.ly", "t.co", "rebrand.ly", "shorturl.at",
                    "rb.gy", "s.id", "tiny.cc", "tiny.one", "buff.ly", "bl.ink",
                    "lnkd.in", "qrco.de", "x.gd", "urlz.fr", "shorturl.asia", "short.gy",
                    "cli.re", "u.to", "clck.ru", "did.li", "1url.cz", "kurzelinks.de",
                    "t1p.de", "0cn.de", "rotf.lol", "2u.pm", "gg.gg", "linktr.ee",
                    "s.free.fr", "trib.al", "soo.gd", "tr.im", "snip.ly", "rlu.ru",
                    "bit.do", "adf.ly", "shorte.st", "ouo.io", "chilp.it", "zpr.io")));

    /** Separators that may appear in a phone number sender. */
    private static final Pattern NUMBER_SEPARATORS = Pattern.compile("[\\s\\-().\\/]");
    private static final Pattern NUMBER = Pattern.compile("\\+?[0-9]+");

    /**
     * A link: an optional scheme, a host name with a top-level domain of letters and an
     * optional path. The look-behind keeps "habit.ly" from matching "bit.ly" and skips
     * e-mail addresses ("info@example.com").
     */
    private static final Pattern LINK = Pattern.compile(
            "(?<![\\p{L}\\p{N}@._-])(https?://)?((?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?\\.)+"
                    + "\\p{L}{2,24})(?::[0-9]{1,5})?(/[^\\s]*)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Top-level domains that look like words or file names in plain text. */
    private static final Set<String> NON_LINK_SUFFIXES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "jpg", "jpeg", "png", "gif", "pdf", "txt", "doc", "docx", "xls", "xlsx",
                    "zip", "exe", "apk", "mp3", "mp4")));

    /** Top-level domains for which a bare host (no scheme, no path) counts as a link. */
    private static final Set<String> COMMON_TLDS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com", "net", "org", "info", "biz", "io", "co", "me", "app", "online",
                    "site", "shop", "store", "top", "xyz", "club", "live", "link", "click",
                    "de", "at", "ch", "eu", "uk", "fr", "it", "es", "nl", "pl", "ru", "ua",
                    "us", "ly", "gd", "cc", "to", "tk", "ml", "ga", "cf", "gq", "pw",
                    "icu", "vip", "buzz", "cn", "in", "br", "pt", "be", "se", "no", "dk",
                    "fi", "cz", "sk", "hu", "ro", "bg", "gr", "tr", "il", "ir", "id", "vn")));

    private SmsWarningPolicy() {
    }

    /** @return the kind of the sender address */
    public static SenderKind classifySender(String sender) {
        if (sender == null) return SenderKind.NONE;
        String trimmed = sender.trim();
        if (trimmed.isEmpty()) return SenderKind.NONE;
        String cleaned = NUMBER_SEPARATORS.matcher(trimmed).replaceAll("");
        if (cleaned.isEmpty()) return SenderKind.NONE;
        return NUMBER.matcher(cleaned).matches() ? SenderKind.NUMBER : SenderKind.ALPHANUMERIC;
    }

    /**
     * @return the sender for the lookup: a number without separators, a name trimmed,
     * null if there is no sender
     */
    public static String cleanSender(String sender) {
        SenderKind kind = classifySender(sender);
        switch (kind) {
            case NUMBER:
                return NUMBER_SEPARATORS.matcher(sender.trim()).replaceAll("");
            case ALPHANUMERIC:
                return sender.trim();
            default:
                return null;
        }
    }

    /**
     * Looks for web links in a message text. The text is not kept.
     *
     * @param text the message text, may be null
     */
    public static LinkScan scanLinks(CharSequence text) {
        if (text == null || text.length() == 0) return LinkScan.NONE;

        boolean hasLink = false;
        Matcher m = LINK.matcher(text);
        while (m.find()) {
            boolean scheme = m.group(1) != null;
            String host = normalizeHost(m.group(2));
            String path = m.group(3);
            boolean withPath = path != null && path.length() > 1;

            String tld = host.substring(host.lastIndexOf('.') + 1);
            boolean plausible;
            if (scheme) {
                plausible = true;
            } else if (NON_LINK_SUFFIXES.contains(tld)) {
                // "photo.jpg", "invoice.pdf"
                plausible = false;
            } else {
                // a bare host: "Mr.Smith" or "today.Call" (missing space) are not links
                plausible = withPath || host.startsWith("www.") || COMMON_TLDS.contains(tld);
            }
            if (!plausible) continue;

            hasLink = true;
            // a shortener link needs a path (the code), otherwise it leads nowhere
            if (isShortenerHost(host) && (withPath || scheme)) {
                return new LinkScan(true, stripWww(host));
            }
        }
        return hasLink ? new LinkScan(true, null) : LinkScan.NONE;
    }

    /** @return whether the host (with or without "www.") is a known URL shortener */
    public static boolean isShortenerHost(String host) {
        if (host == null) return false;
        return URL_SHORTENERS.contains(stripWww(normalizeHost(host)));
    }

    /**
     * Decides about one message.
     *
     * @param kind         the kind of the sender
     * @param facts        what the lookup found about the sender
     * @param linksEnabled the "warn about suspicious links" setting
     * @param links        the links of the message ({@link LinkScan#NONE} if the text was
     *                     not checked)
     */
    public static Decision decide(SenderKind kind, SenderFacts facts, boolean linksEnabled,
                                  LinkScan links) {
        if (kind == null || kind == SenderKind.NONE) return Decision.NONE;
        if (facts == null) facts = new SenderFacts();
        if (links == null || !linksEnabled) links = LinkScan.NONE;

        SpamReason reason = spamReason(facts);
        if (reason != null) {
            return new Decision(Warning.SPAM, reason, links.hasLink(), links.getShortenerHost());
        }

        // the user or a rule explicitly trusts the sender; contacts are known
        if (facts.userMarkNotSpam || facts.allowRule || facts.contact) return Decision.NONE;

        if (linksEnabled && links.hasLink()
                && (links.getShortenerHost() != null || facts.reported)) {
            return new Decision(Warning.SUSPICIOUS_LINK, null, true, links.getShortenerHost());
        }
        return Decision.NONE;
    }

    /** @return why the sender is spam, or null */
    static SpamReason spamReason(SenderFacts facts) {
        // the explicit blacklist beats the "not spam" mark and ALLOW rules
        // (but not contacts, like for calls)
        if (facts.userMarkNotSpam || facts.allowRule) {
            return facts.blacklisted && !facts.contact ? SpamReason.BLACKLIST : null;
        }
        if (facts.userMarkSpam) return SpamReason.USER_MARK;
        // a BLOCK rule only matches a contact if the rule explicitly includes contacts
        if (facts.blockRule) return facts.blacklisted && !facts.contact
                ? SpamReason.BLACKLIST : SpamReason.RULE;
        if (facts.contact) return null;
        if (facts.blacklisted) return SpamReason.BLACKLIST;
        if (facts.ratingNegative) return SpamReason.RATING;
        return null;
    }

    private static String normalizeHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    private static String stripWww(String host) {
        return host.startsWith("www.") ? host.substring(4) : host;
    }

}
