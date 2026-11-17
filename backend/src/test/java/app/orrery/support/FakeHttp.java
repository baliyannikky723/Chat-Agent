package app.orrery.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A real local HTTP server for tests, so the model and search clients are exercised
 * over actual sockets with the providers' real wire formats (including SSE streaming).
 */
public final class FakeHttp implements AutoCloseable {

    /** What a route answers. {@code sseEvents} non-null means a text/event-stream response. */
    public record Reply(int status, String body, List<String> sseEvents, Map<String, String> headers) {
        public static Reply json(String body) {
            return new Reply(200, body, null, Map.of());
        }

        public static Reply status(int status, String body) {
            return new Reply(status, body, null, Map.of());
        }

        /** Each entry is written as one SSE frame ("data: …\n\n" unless it already contains "data:"). */
        public static Reply sse(List<String> events) {
            return new Reply(200, null, events, Map.of());
        }
    }

    public record Recorded(String method, String path, String query, String body, Map<String, List<String>> headers) {}

    private final HttpServer server;
    private final Map<String, Function<Recorded, Reply>> routes = new ConcurrentHashMap<>();
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());

    public FakeHttp() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public FakeHttp route(String path, Function<Recorded, Reply> handler) {
        routes.put(path, handler);
        return this;
    }

    public List<Recorded> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    public List<Recorded> requests(String path) {
        return requests().stream().filter(r -> r.path().equals(path)).toList();
    }

    private void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Recorded rec = new Recorded(ex.getRequestMethod(), ex.getRequestURI().getPath(), ex.getRequestURI().getRawQuery(), body,
                Map.copyOf(ex.getRequestHeaders()));
        requests.add(rec);
        Function<Recorded, Reply> handler = routes.get(rec.path());
        Reply reply = handler == null ? Reply.status(404, "{\"error\":\"no route " + rec.path() + "\"}") : handler.apply(rec);
        reply.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        if (reply.sseEvents() != null) {
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                for (String event : reply.sseEvents()) {
                    String frame = event.contains("data:") ? event : "data: " + event;
                    out.write((frame + "\n\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
        } else {
            byte[] bytes = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(bytes);
                }
            } else {
                ex.close();
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
