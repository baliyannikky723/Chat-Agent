package app.orrery.core.search;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * SSRF protection for the page reader. Search results are untrusted input: a result URL
 * must never make the server fetch localhost, the cloud metadata endpoint or a private
 * network address. Every redirect hop is checked again.
 */
public final class UrlGuard {

    private UrlGuard() {}

    public static final class UnsafeUrlException extends Exception {
        private static final long serialVersionUID = 1L;

        public UnsafeUrlException(String message) {
            super(message);
        }
    }

    /** Parses and checks a URL; throws if it is not a public http(s) address. */
    public static URI check(String url) throws UnsafeUrlException {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            throw new UnsafeUrlException("malformed URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new UnsafeUrlException("only http and https are allowed");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new UnsafeUrlException("URL has no host");
        String h = host.toLowerCase(Locale.ROOT);
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")) {
            throw new UnsafeUrlException("local hostnames are not allowed");
        }
        if (uri.getUserInfo() != null) throw new UnsafeUrlException("credentials in URLs are not allowed");
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443 && port != 8080 && port != 8443) {
            throw new UnsafeUrlException("port " + port + " is not allowed");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(h.startsWith("[") ? h.substring(1, h.length() - 1) : h);
        } catch (UnknownHostException e) {
            throw new UnsafeUrlException("host does not resolve");
        }
        for (InetAddress a : addresses) {
            if (!isPublic(a)) throw new UnsafeUrlException("address " + a.getHostAddress() + " is not public");
        }
        return uri;
    }

    public static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            if (b0 == 0) return false; // 0.0.0.0/8
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return false; // carrier-grade NAT 100.64/10
            if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) return false; // 192.0.0.0/24
            if (b0 == 198 && (b1 == 18 || b1 == 19)) return false; // benchmarking 198.18/15
            if (b0 >= 240) return false; // reserved + broadcast
            return true;
        }
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff;
            if ((b0 & 0xfe) == 0xfc) return false; // unique local fc00::/7
            if (b0 == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) return false; // docs 2001:db8::/32
            boolean v4Mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) v4Mapped = false;
            if (v4Mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
