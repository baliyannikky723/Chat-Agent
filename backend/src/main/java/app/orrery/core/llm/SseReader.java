package app.orrery.core.llm;

import app.orrery.core.util.CancelToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads a Server-Sent Events stream (the format every model API streams in).
 *
 * <p>Two safety nets: cancelling the turn closes the stream immediately, and a watchdog
 * closes it if the provider goes silent for too long, so a stalled connection can never
 * hang a turn forever.
 */
public final class SseReader {

    /** Return false to stop reading. */
    @FunctionalInterface
    public interface Handler {
        boolean onEvent(String event, String data) throws LlmException;
    }

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-watchdog");
        t.setDaemon(true);
        return t;
    });

    private SseReader() {}

    public static void read(InputStream body, CancelToken cancel, long idleTimeoutMillis, String provider, Handler handler) throws LlmException {
        AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        final boolean[] idleClosed = {false};
        Runnable closer = () -> {
            try {
                body.close();
            } catch (IOException ignored) {
                // already closed
            }
        };
        Runnable unregister = cancel.onCancel(closer);
        ScheduledFuture<?> watchdog = WATCHDOG.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() - lastActivity.get() > idleTimeoutMillis) {
                idleClosed[0] = true;
                closer.run();
            }
        }, 1, 1, TimeUnit.SECONDS);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                lastActivity.set(System.currentTimeMillis());
                if (line.isEmpty()) {
                    if (data.length() > 0 || event != null) {
                        boolean more = handler.onEvent(event, data.toString());
                        event = null;
                        data.setLength(0);
                        if (!more) return;
                    }
                    continue;
                }
                if (line.charAt(0) == ':') continue; // comment / keep-alive
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) value = value.substring(1);
                switch (field) {
                    case "event" -> event = value;
                    case "data" -> {
                        if (data.length() > 0) data.append('\n');
                        data.append(value);
                    }
                    default -> {
                        // id / retry: not needed
                    }
                }
            }
            if (data.length() > 0) handler.onEvent(event, data.toString());
        } catch (IOException e) {
            if (cancel.isCancelled()) throw new LlmException(LlmException.Kind.CANCELLED, "cancelled");
            if (idleClosed[0]) throw new LlmException(LlmException.Kind.UNAVAILABLE, 0, provider + " stopped sending data", 0);
            throw new LlmException(LlmException.Kind.UNAVAILABLE, 0, provider + " stream broke: " + e.getMessage(), 0);
        } finally {
            watchdog.cancel(false);
            unregister.run();
        }
    }
}
