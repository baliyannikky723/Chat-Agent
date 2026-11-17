package app.orrery.core.search;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** URL normalisation so the same page found by two searches gets one citation number. */
public final class Urls {

    private static final Set<String> TRACKING = Set.of("gclid", "fbclid", "msclkid", "mc_cid", "mc_eid", "igshid", "ref_src", "si", "_hsenc", "_hsmi");

    private Urls() {}

    public static String canonical(String url) {
        if (url == null) return "";
        try {
            URI u = new URI(url.strip());
            String scheme = u.getScheme() == null ? "https" : u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String query = u.getRawQuery();
            if (query != null) {
                List<String> kept = new ArrayList<>();
                for (String part : query.split("&")) {
                    String key = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
                    if (key.startsWith("utm_") || TRACKING.contains(key) || key.isEmpty()) continue;
                    kept.add(part);
                }
                query = kept.isEmpty() ? null : String.join("&", kept);
            }
            String port = u.getPort() == -1 || u.getPort() == 80 || u.getPort() == 443 ? "" : ":" + u.getPort();
            return scheme + "://" + host + port + path + (query == null ? "" : "?" + query);
        } catch (URISyntaxException e) {
            return url.strip();
        }
    }

    /** "https://www.hubspot.com/pricing" → "hubspot.com" */
    public static String domain(String url) {
        if (url == null) return "";
        try {
            String host = new URI(url.strip()).getHost();
            if (host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (URISyntaxException e) {
            return "";
        }
    }
}
