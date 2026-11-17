package app.orrery.core.search;

import app.orrery.core.search.SearchException.Kind;
import app.orrery.core.util.CancelToken;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Shared request plumbing for search adapters: timeout, cancellation, status classification. */
final class HttpCalls {

    private HttpCalls() {}

    static String send(HttpClient client, HttpRequest request, String provider, long timeoutMillis, CancelToken cancel)
            throws SearchException {
        if (cancel.isCancelled()) throw new SearchException(Kind.CANCELLED, provider, "cancelled");
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Runnable unregister = cancel.onCancel(() -> future.cancel(true));
        try {
            HttpResponse<String> response = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (status >= 200 && status < 300) return response.body();
            throw new SearchException(SearchException.fromStatus(status), provider, "HTTP " + status + ": " + snippet(response.body()));
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new SearchException(Kind.TIMEOUT, provider, "no response within " + timeoutMillis + " ms");
        } catch (CancellationException e) {
            throw new SearchException(Kind.CANCELLED, provider, "cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SearchException(Kind.CANCELLED, provider, "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new SearchException(Kind.UNAVAILABLE, provider, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } finally {
            unregister.run();
        }
    }

    static String snippet(String body) {
        if (body == null) return "";
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }
}
