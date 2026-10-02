package dummydomain.yetanothercallblocker.data.sms;

import org.junit.Test;

import dummydomain.yetanothercallblocker.data.sms.SmsWarningPolicy.Decision;
import dummydomain.yetanothercallblocker.data.sms.SmsWarningPolicy.LinkScan;
import dummydomain.yetanothercallblocker.data.sms.SmsWarningPolicy.SenderFacts;
import dummydomain.yetanothercallblocker.data.sms.SmsWarningPolicy.SenderKind;
import dummydomain.yetanothercallblocker.data.sms.SmsWarningPolicy.SpamReason;
import dummydomain.yetanothercallblocker.data.sms.SmsWarningPolicy.Warning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SmsWarningPolicyTest {

    // sender classification

    @Test
    public void classifiesNumbers() {
        assertEquals(SenderKind.NUMBER, SmsWarningPolicy.classifySender("+491701234567"));
        assertEquals(SenderKind.NUMBER, SmsWarningPolicy.classifySender("0170 123-45 67"));
        assertEquals(SenderKind.NUMBER, SmsWarningPolicy.classifySender("(030) 1234/567"));
        // short codes
        assertEquals(SenderKind.NUMBER, SmsWarningPolicy.classifySender("12345"));
        assertEquals(SenderKind.NUMBER, SmsWarningPolicy.classifySender(" 900 "));
    }

    @Test
    public void classifiesNames() {
        assertEquals(SenderKind.ALPHANUMERIC, SmsWarningPolicy.classifySender("DHL"));
        assertEquals(SenderKind.ALPHANUMERIC, SmsWarningPolicy.classifySender("PayPal"));
        assertEquals(SenderKind.ALPHANUMERIC, SmsWarningPolicy.classifySender("Info 24"));
        assertEquals(SenderKind.ALPHANUMERIC, SmsWarningPolicy.classifySender("O2-Info"));
        assertEquals(SenderKind.ALPHANUMERIC, SmsWarningPolicy.classifySender("#123"));
        assertEquals(SenderKind.ALPHANUMERIC, SmsWarningPolicy.classifySender("49+170"));
    }

    @Test
    public void classifiesMissingSender() {
        assertEquals(SenderKind.NONE, SmsWarningPolicy.classifySender(null));
        assertEquals(SenderKind.NONE, SmsWarningPolicy.classifySender(""));
        assertEquals(SenderKind.NONE, SmsWarningPolicy.classifySender("   "));
        assertEquals(SenderKind.NONE, SmsWarningPolicy.classifySender(" - "));
    }

    @Test
    public void cleansSender() {
        assertEquals("+491701234567", SmsWarningPolicy.cleanSender(" +49 170 123-45 67 "));
        assertEquals("0301234567", SmsWarningPolicy.cleanSender("(030) 1234/567"));
        assertEquals("PayPal", SmsWarningPolicy.cleanSender("  PayPal "));
        assertNull(SmsWarningPolicy.cleanSender(" "));
        assertNull(SmsWarningPolicy.cleanSender(null));
    }

    // links

    @Test
    public void findsShortenerLinks() {
        assertEquals("bit.ly", SmsWarningPolicy.scanLinks(
                "Ihr Paket wartet: https://bit.ly/3AbCdE").getShortenerHost());
        assertEquals("bit.ly", SmsWarningPolicy.scanLinks(
                "Paket: bit.ly/3AbCdE bitte bestaetigen").getShortenerHost());
        assertEquals("t.ly", SmsWarningPolicy.scanLinks("see t.ly/x1").getShortenerHost());
        assertEquals("tinyurl.com", SmsWarningPolicy.scanLinks(
                "HTTPS://WWW.TinyURL.com/abc").getShortenerHost());
        assertEquals("is.gd", SmsWarningPolicy.scanLinks("(is.gd/abc)").getShortenerHost());
        assertEquals("cutt.ly", SmsWarningPolicy.scanLinks(
                "Konto gesperrt!cutt.ly/k2 sofort").getShortenerHost());
        assertEquals("t.co", SmsWarningPolicy.scanLinks("http://t.co").getShortenerHost());
    }

    @Test
    public void shortenerFoundAfterOtherLinks() {
        LinkScan scan = SmsWarningPolicy.scanLinks(
                "Info: https://example.com/a oder kurz: bit.ly/xyz");
        assertTrue(scan.hasLink());
        assertEquals("bit.ly", scan.getShortenerHost());
    }

    @Test
    public void ordinaryLinksAreNotShorteners() {
        LinkScan scan = SmsWarningPolicy.scanLinks("Track: https://www.dhl.de/track?id=1");
        assertTrue(scan.hasLink());
        assertNull(scan.getShortenerHost());

        scan = SmsWarningPolicy.scanLinks("Visit example.com today");
        assertTrue(scan.hasLink());
        assertNull(scan.getShortenerHost());

        // "habit.ly" and "rabbit.ly" contain "bit.ly", but are other hosts
        scan = SmsWarningPolicy.scanLinks("see habit.ly/x and rabbit.ly/y");
        assertTrue(scan.hasLink());
        assertNull(scan.getShortenerHost());

        // a subdomain of a shortener is a different host
        assertNull(SmsWarningPolicy.scanLinks("https://evil.bit.ly.example.com/x")
                .getShortenerHost());
    }

    @Test
    public void shortenerWithoutCodeIsNotFlagged() {
        LinkScan scan = SmsWarningPolicy.scanLinks("Wir nutzen bit.ly fuer Links");
        assertTrue(scan.hasLink());
        assertNull(scan.getShortenerHost());
    }

    @Test
    public void plainTextHasNoLinks() {
        assertFalse(SmsWarningPolicy.scanLinks(null).hasLink());
        assertFalse(SmsWarningPolicy.scanLinks("").hasLink());
        assertFalse(SmsWarningPolicy.scanLinks("Ihr Code lautet 123456.").hasLink());
        assertFalse(SmsWarningPolicy.scanLinks("Termin am 3.10. um 14.30 Uhr").hasLink());
        assertFalse(SmsWarningPolicy.scanLinks("Hallo Mr.Smith, bis morgen.Gruss").hasLink());
        assertFalse(SmsWarningPolicy.scanLinks("Foto: urlaub.jpg, Rechnung.pdf").hasLink());
        assertFalse(SmsWarningPolicy.scanLinks("z.B. so, u.a. auch").hasLink());
        // e-mail addresses are not links
        assertFalse(SmsWarningPolicy.scanLinks("Schreib an info@example.com").hasLink());
        // the "bit.ly" of an e-mail address is not a link either
        assertNull(SmsWarningPolicy.scanLinks("mail me@bit.ly/abc").getShortenerHost());
    }

    @Test
    public void knowsShortenerHosts() {
        assertTrue(SmsWarningPolicy.isShortenerHost("bit.ly"));
        assertTrue(SmsWarningPolicy.isShortenerHost("WWW.Bit.Ly"));
        assertTrue(SmsWarningPolicy.isShortenerHost("tinyurl.com"));
        assertFalse(SmsWarningPolicy.isShortenerHost("example.com"));
        assertFalse(SmsWarningPolicy.isShortenerHost(null));
    }

    // decisions

    private static Decision decide(SenderFacts facts) {
        return SmsWarningPolicy.decide(SenderKind.NUMBER, facts, false, LinkScan.NONE);
    }

    private static Decision decideWithLink(SenderKind kind, SenderFacts facts, String text) {
        return SmsWarningPolicy.decide(kind, facts, true, SmsWarningPolicy.scanLinks(text));
    }

    @Test
    public void unknownSenderGetsNoWarning() {
        assertEquals(Warning.NONE, decide(new SenderFacts()).getWarning());
        assertEquals(Warning.NONE, decide(null).getWarning());
    }

    @Test
    public void spamSourcesWarn() {
        Decision d = decide(new SenderFacts().ratingNegative(true));
        assertEquals(Warning.SPAM, d.getWarning());
        assertEquals(SpamReason.RATING, d.getReason());

        d = decide(new SenderFacts().blacklisted(true));
        assertEquals(SpamReason.BLACKLIST, d.getReason());

        d = decide(new SenderFacts().blockRule(true));
        assertEquals(SpamReason.RULE, d.getReason());

        // the user's SPAM mark also makes the rating NEGATIVE: the mark is named
        d = decide(new SenderFacts().userMarkSpam(true).ratingNegative(true));
        assertEquals(SpamReason.USER_MARK, d.getReason());
    }

    @Test
    public void blacklistIsNamedBeforeRuleAndRating() {
        assertEquals(SpamReason.BLACKLIST, decide(new SenderFacts()
                .blacklisted(true).blockRule(true).ratingNegative(true)).getReason());
        assertEquals(SpamReason.BLACKLIST, decide(new SenderFacts()
                .blacklisted(true).ratingNegative(true)).getReason());
        assertEquals(SpamReason.RULE, decide(new SenderFacts()
                .blockRule(true).ratingNegative(true)).getReason());
    }

    @Test
    public void notSpamMarkSilencesEverythingButTheBlacklist() {
        assertEquals(Warning.NONE, decide(new SenderFacts()
                .userMarkNotSpam(true)).getWarning());
        assertEquals(Warning.NONE, decideWithLink(SenderKind.NUMBER, new SenderFacts()
                .userMarkNotSpam(true), "bit.ly/abc").getWarning());

        Decision d = decide(new SenderFacts().userMarkNotSpam(true).blacklisted(true));
        assertEquals(Warning.SPAM, d.getWarning());
        assertEquals(SpamReason.BLACKLIST, d.getReason());
    }

    @Test
    public void allowRuleSilencesRatingsButNotTheBlacklist() {
        assertEquals(Warning.NONE, decide(new SenderFacts()
                .allowRule(true).ratingNegative(true)).getWarning());
        assertEquals(Warning.NONE, decideWithLink(SenderKind.NUMBER, new SenderFacts()
                .allowRule(true).reported(true), "https://bit.ly/abc").getWarning());
        assertEquals(SpamReason.BLACKLIST, decide(new SenderFacts()
                .allowRule(true).blacklisted(true)).getReason());
    }

    @Test
    public void contactsOnlyWarnForExplicitChoices() {
        assertEquals(Warning.NONE, decide(new SenderFacts()
                .contact(true).ratingNegative(true)).getWarning());
        assertEquals(Warning.NONE, decide(new SenderFacts()
                .contact(true).blacklisted(true)).getWarning());
        assertEquals(Warning.NONE, decideWithLink(SenderKind.NUMBER, new SenderFacts()
                .contact(true), "https://bit.ly/abc").getWarning());

        // a BLOCK rule matches a contact only if it explicitly includes contacts
        assertEquals(SpamReason.RULE, decide(new SenderFacts()
                .contact(true).blockRule(true).blacklisted(true)).getReason());
        assertEquals(SpamReason.USER_MARK, decide(new SenderFacts()
                .contact(true).userMarkSpam(true)).getReason());
    }

    @Test
    public void spamWarningMentionsLinkOnlyWithLinkCheck() {
        SenderFacts spam = new SenderFacts().ratingNegative(true);

        Decision d = decideWithLink(SenderKind.NUMBER, spam, "Gewinn! https://example.com/x");
        assertEquals(Warning.SPAM, d.getWarning());
        assertTrue(d.hasLink());
        assertNull(d.getShortenerHost());

        d = decideWithLink(SenderKind.NUMBER, spam, "Gewinn! bit.ly/x");
        assertEquals("bit.ly", d.getShortenerHost());

        // link check off: the text is ignored even if it was scanned
        d = SmsWarningPolicy.decide(SenderKind.NUMBER, spam, false,
                SmsWarningPolicy.scanLinks("bit.ly/x"));
        assertEquals(Warning.SPAM, d.getWarning());
        assertFalse(d.hasLink());
        assertNull(d.getShortenerHost());
    }

    @Test
    public void shortenerFromUnknownSenderIsSuspicious() {
        Decision d = decideWithLink(SenderKind.NUMBER, new SenderFacts(),
                "Ihr Paket: bit.ly/3AbC");
        assertEquals(Warning.SUSPICIOUS_LINK, d.getWarning());
        assertEquals("bit.ly", d.getShortenerHost());
        assertNull(d.getReason());

        // spoofed sender names are the classic case
        d = decideWithLink(SenderKind.ALPHANUMERIC, new SenderFacts(),
                "DHL: Zustellung fehlgeschlagen, cutt.ly/abc");
        assertEquals(Warning.SUSPICIOUS_LINK, d.getWarning());
    }

    @Test
    public void ordinaryLinkFromUnknownSenderIsNoise() {
        assertEquals(Warning.NONE, decideWithLink(SenderKind.NUMBER, new SenderFacts(),
                "Ihr Termin: https://www.doctolib.de/x").getWarning());
        assertEquals(Warning.NONE, decideWithLink(SenderKind.ALPHANUMERIC, new SenderFacts(),
                "Ihre Sendung: https://www.dhl.de/track").getWarning());
    }

    @Test
    public void ordinaryLinkFromReportedSenderIsSuspicious() {
        Decision d = decideWithLink(SenderKind.NUMBER, new SenderFacts().reported(true),
                "Bitte bestaetigen: https://example.com/login");
        assertEquals(Warning.SUSPICIOUS_LINK, d.getWarning());
        assertNull(d.getShortenerHost());

        // reported, but no link: no warning (not enough for a NEGATIVE rating)
        assertEquals(Warning.NONE, decideWithLink(SenderKind.NUMBER,
                new SenderFacts().reported(true), "Hallo").getWarning());
    }

    @Test
    public void linkCheckOffNeverWarnsAboutLinks() {
        assertEquals(Warning.NONE, SmsWarningPolicy.decide(SenderKind.NUMBER,
                new SenderFacts().reported(true), false,
                SmsWarningPolicy.scanLinks("bit.ly/abc")).getWarning());
    }

    @Test
    public void noSenderNoWarning() {
        assertEquals(Warning.NONE, SmsWarningPolicy.decide(SenderKind.NONE,
                new SenderFacts().ratingNegative(true), true,
                SmsWarningPolicy.scanLinks("bit.ly/abc")).getWarning());
        assertEquals(Warning.NONE, SmsWarningPolicy.decide(null,
                new SenderFacts().ratingNegative(true), false, null).getWarning());
    }

    @Test
    public void alphanumericSenderWithUserMark() {
        Decision d = SmsWarningPolicy.decide(SenderKind.ALPHANUMERIC,
                new SenderFacts().userMarkSpam(true), false, LinkScan.NONE);
        assertEquals(Warning.SPAM, d.getWarning());
        assertEquals(SpamReason.USER_MARK, d.getReason());
    }

}
