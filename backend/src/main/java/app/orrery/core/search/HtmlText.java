package app.orrery.core.search;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Readable text from an HTML page, without a DOM library: drops scripts, styles and page
 * chrome (nav, header, footer, aside, forms), prefers the main/article region, keeps
 * paragraph and list structure, and decodes entities. Also finds the title and the
 * publish date from the usual meta tags.
 */
public final class HtmlText {

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;

    private static final Pattern COMMENTS = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern INVISIBLE = Pattern.compile(
            "<(script|style|noscript|svg|template|iframe|canvas|object|picture|video|audio)\\b[^>]*>.*?</\\1\\s*>", F);
    private static final Pattern CHROME = Pattern.compile("<(nav|header|footer|aside|form|button|select|dialog)\\b[^>]*>.*?</\\1\\s*>", F);
    private static final Pattern MAIN = Pattern.compile("<main\\b[^>]*>(.*?)</main\\s*>", F);
    private static final Pattern ARTICLE = Pattern.compile("<article\\b[^>]*>(.*?)</article\\s*>", F);
    private static final Pattern BODY = Pattern.compile("<body\\b[^>]*>(.*)</body\\s*>", F);
    private static final Pattern TITLE = Pattern.compile("<title\\b[^>]*>(.*?)</title\\s*>", F);
    private static final Pattern META = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z_:.-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    private static final Pattern TIME_TAG = Pattern.compile("<time\\b[^>]*\\bdatetime\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern LI = Pattern.compile("<li\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CELL = Pattern.compile("</t[dh]\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK = Pattern.compile(
            "</?(p|div|br|h[1-6]|tr|table|section|ul|ol|dl|dt|dd|blockquote|pre|hr|figure|figcaption|summary|details)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});");

    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""),
            Map.entry("apos", "'"), Map.entry("nbsp", " "), Map.entry("ndash", "–"), Map.entry("mdash", "—"),
            Map.entry("hellip", "…"), Map.entry("rsquo", "’"), Map.entry("lsquo", "‘"), Map.entry("rdquo", "”"),
            Map.entry("ldquo", "“"), Map.entry("euro", "€"), Map.entry("pound", "£"), Map.entry("yen", "¥"),
            Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("trade", "™"), Map.entry("times", "×"),
            Map.entry("middot", "·"), Map.entry("bull", "•"), Map.entry("rarr", "→"), Map.entry("deg", "°"));

    public record Extracted(String title, String text, String publishedAt) {}

    private HtmlText() {}

    public static Extracted extract(String html, int maxChars) {
        if (html == null) return new Extracted("", "", null);
        String title = firstGroup(TITLE, html);
        String published = null;
        Matcher meta = META.matcher(html);
        while (meta.find()) {
            Map<String, String> attrs = attributes(meta.group());
            String key = attrs.getOrDefault("property", attrs.getOrDefault("name", attrs.getOrDefault("itemprop", ""))).toLowerCase(Locale.ROOT);
            String content = attrs.get("content");
            if (content == null) continue;
            if ((title == null || title.isBlank()) && key.equals("og:title")) title = content;
            if (published == null && (key.equals("article:published_time") || key.equals("og:published_time")
                    || key.equals("datepublished") || key.equals("date") || key.equals("pubdate")
                    || key.equals("article:modified_time") || key.equals("og:updated_time"))) {
                published = content.strip();
            }
        }
        if (published == null) published = firstGroup(TIME_TAG, html);

        String work = COMMENTS.matcher(html).replaceAll(" ");
        work = INVISIBLE.matcher(work).replaceAll(" ");
        String region = firstGroup(MAIN, work);
        if (region == null || region.length() < 400) {
            String article = largestGroup(ARTICLE, work);
            if (article != null && article.length() >= 400) region = article;
        }
        if (region == null || region.length() < 400) {
            String body = firstGroup(BODY, work);
            region = body != null ? body : work;
        }
        region = CHROME.matcher(region).replaceAll(" ");
        String text = toText(region);
        if (text.length() > maxChars) text = text.substring(0, maxChars).stripTrailing() + "…";
        return new Extracted(decode(title == null ? "" : title).strip(), text, published);
    }

    /** Snippets from search APIs sometimes carry <strong> tags and entities. */
    public static String stripTags(String s) {
        if (s == null || s.isEmpty()) return "";
        return decode(TAG.matcher(s).replaceAll("")).replaceAll("\\s+", " ").strip();
    }

    private static String toText(String html) {
        String t = LI.matcher(html).replaceAll("\n- ");
        t = CELL.matcher(t).replaceAll(" | ");
        t = BLOCK.matcher(t).replaceAll("\n");
        t = TAG.matcher(t).replaceAll(" ");
        t = decode(t);
        StringBuilder sb = new StringBuilder();
        int blank = 0;
        for (String line : t.split("\n")) {
            String clean = line.replaceAll("[\\t\\x0B\\f\\r ]+", " ").strip();
            if (clean.equals("|") || clean.equals("-")) clean = "";
            if (clean.isEmpty()) {
                if (++blank > 1) continue;
            } else {
                blank = 0;
            }
            sb.append(clean).append('\n');
        }
        return sb.toString().strip();
    }

    static String decode(String s) {
        if (s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String rep;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) rep = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) rep = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else rep = NAMED.getOrDefault(e.toLowerCase(Locale.ROOT), m.group());
            } catch (IllegalArgumentException ex) {
                rep = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Map<String, String> attributes(String tag) {
        Map<String, String> out = new java.util.HashMap<>();
        Matcher m = ATTR.matcher(tag);
        while (m.find()) {
            String v = m.group(3) != null ? m.group(3) : m.group(4) != null ? m.group(4) : m.group(5);
            out.put(m.group(1).toLowerCase(Locale.ROOT), v);
        }
        return out;
    }

    private static String firstGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String largestGroup(Pattern p, String s) {
        Matcher m = p.matcher(s);
        String best = null;
        while (m.find()) if (best == null || m.group(1).length() > best.length()) best = m.group(1);
        return best;
    }
}
