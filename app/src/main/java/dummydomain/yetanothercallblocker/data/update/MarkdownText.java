package dummydomain.yetanothercallblocker.data.update;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the GitHub Markdown of release notes into readable plain text: headings,
 * emphasis, inline code, links and HTML comments are unwrapped, list markers become
 * bullets. Not a Markdown parser; unknown markup is left as is. Plain Java.
 */
public final class MarkdownText {

    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\(([^)\\s]+)[^)]*\\)");
    private static final Pattern BOLD = Pattern.compile("(\\*\\*|__)(.+?)\\1");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w*])\\*(?!\\s)([^*\\n]+?)\\*(?![\\w*])");
    private static final Pattern CODE = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(\\[[ xX]]\\s+)?");
    private static final Pattern QUOTE = Pattern.compile("^\\s*>\\s?");
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern FENCE = Pattern.compile("^\\s*```.*$");

    private MarkdownText() {}

    /** @return the plain text, never null */
    public static String toPlainText(String markdown) {
        if (markdown == null) return "";
        String s = markdown.replace("\r\n", "\n").replace('\r', '\n');
        s = HTML_COMMENT.matcher(s).replaceAll("");

        StringBuilder out = new StringBuilder();
        boolean blank = false;
        for (String line : s.split("\n", -1)) {
            if (FENCE.matcher(line).matches()) continue;
            if (RULE.matcher(line).matches()) line = "";

            Matcher m = HEADING.matcher(line);
            if (m.matches()) line = m.group(1);

            line = QUOTE.matcher(line).replaceFirst("");

            m = BULLET.matcher(line);
            if (m.find()) line = m.group(1) + "• " + line.substring(m.end());

            line = IMAGE.matcher(line).replaceAll("$1");
            line = LINK.matcher(line).replaceAll("$1");
            line = BOLD.matcher(line).replaceAll("$2");
            line = ITALIC.matcher(line).replaceAll("$1");
            line = CODE.matcher(line).replaceAll("$1");
            line = stripTrailing(line);

            // collapse runs of empty lines
            if (line.isEmpty()) {
                if (blank || out.length() == 0) continue;
                blank = true;
            } else {
                blank = false;
            }
            out.append(line).append('\n');
        }
        return out.toString().trim();
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

}
