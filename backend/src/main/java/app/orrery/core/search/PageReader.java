package app.orrery.core.search;

import app.orrery.core.search.UrlGuard.UnsafeUrlException;
import app.orrery.core.util.CancelToken;
import app.orrery.core.util.TtlCache;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches a result page and extracts its text, for queries that need exact figures
 * (prices, plan limits) that a search snippet leaves out. Guarded against SSRF on every
 * redirect hop, capped at 2 MB and 8 seconds, and cached for 30 minutes.
 */
public final class PageReader {

    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 4;
    private static final long TIMEOUT_MS = 8_000;

    public record Page(String url, String title, String text, String publishedAt) {}

    private final HttpClient client;
    private final boolean allowPrivateHosts;
    private final TtlCache<String, Page> cache = new TtlCache<>(300);

    public PageReader() {
        this(false);
    }

    /** {@code allowPrivateHosts} exists only so tests can read from a local server. */
    public PageReader(boolean allowPrivateHosts) {
        this.allowPrivateHosts = allowPrivateHosts;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public Page read(String url, CancelToken cancel) throws IOException, UnsafeUrlException {
        String key = Urls.canonical(url);
        Page cached = cache.get(key);
        if (cached != null) return cached;

        URI current = allowPrivateHosts ? URI.create(url) : UrlGuard.check(url);
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (cancel.isCancelled()) throw new IOException("cancelled");
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofMillis(Math.max(1000, deadline - System.currentTimeMillis())))
                    .header("User-Agent", "Mozilla/5.0 (compatible; Orrery/1.0; +https://github.com/)")
                    .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5")
                    .header("Accept-Language", "en")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = sendWithDeadline(request, deadline, cancel);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("location").orElse(null);
                response.body().close();
                if (location == null) throw new IOException("redirect without location");
                URI next = current.resolve(location);
                current = allowPrivateHosts ? next : UrlGuard.check(next.toString());
                continue;
            }
            if (status < 200 || status >= 300) {
                response.body().close();
                throw new IOException("HTTP " + status);
            }
            String type = response.headers().firstValue("content-type").orElse("text/html").toLowerCase(Locale.ROOT);
            if (!type.contains("html") && !type.contains("text/plain") && !type.contains("xml")) {
                response.body().close();
                throw new IOException("not a text page (" + type + ")");
            }
            byte[] bytes = readCapped(response.body());
            String body = new String(bytes, charsetOf(type));
            Page page;
            if (type.contains("text/plain")) {
                page = new Page(current.toString(), "", body.length() > 12_000 ? body.substring(0, 12_000) : body, null);
            } else {
                HtmlText.Extracted ex = HtmlText.extract(body, 12_000);
                page = new Page(current.toString(), ex.title(), ex.text(), ex.publishedAt());
            }
            cache.put(key, page, 30 * 60_000L);
            return page;
        }
        throw new IOException("too many redirects");
    }

    private HttpResponse<InputStream> sendWithDeadline(HttpRequest request, long deadline, CancelToken cancel) throws IOException {
        CompletableFuture<HttpResponse<InputStream>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        Runnable unregister = cancel.onCancel(() -> future.cancel(true));
        try {
            return future.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("timed out");
        } catch (CancellationException e) {
            throw new IOException("cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (ExecutionException e) {
            throw new IOException(e.getCause() == null ? "fetch failed" : e.getCause().toString());
        } finally {
            unregister.run();
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            int total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                int take = Math.min(n, MAX_BYTES - total);
                out.write(buf, 0, take);
                total += take;
                if (total >= MAX_BYTES) break;
            }
            return out.toByteArray();
        }
    }

    private static Charset charsetOf(String contentType) {
        int i = contentType.indexOf("charset=");
        if (i < 0) return StandardCharsets.UTF_8;
        String name = contentType.substring(i + 8).split("[;\\s]")[0].replace("\"", "").strip();
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
