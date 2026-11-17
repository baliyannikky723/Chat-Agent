package app.orrery.core.llm;

import app.orrery.core.json.JNode;
import app.orrery.core.json.Json;
import app.orrery.core.json.JsonException;
import app.orrery.core.llm.LlmModels.AssistantMessage;
import app.orrery.core.llm.LlmModels.ChatMessage;
import app.orrery.core.llm.LlmModels.LlmRequest;
import app.orrery.core.llm.LlmModels.LlmResult;
import app.orrery.core.llm.LlmModels.StreamCallbacks;
import app.orrery.core.llm.LlmModels.ToolCall;
import app.orrery.core.llm.LlmModels.ToolResultMessage;
import app.orrery.core.llm.LlmModels.ToolSpec;
import app.orrery.core.llm.LlmModels.Usage;
import app.orrery.core.llm.LlmModels.UserMessage;
import app.orrery.core.util.CancelToken;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Anthropic Messages API, streamed. Tool calls arrive as {@code tool_use} content blocks
 * whose JSON input streams in pieces; tool results go back as {@code tool_result} blocks
 * inside a user message.
 */
public final class AnthropicClient implements LlmClient {

    private static final long IDLE_TIMEOUT_MS = 120_000;
    private static final String API_VERSION = "2023-06-01";

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;

    public AnthropicClient(HttpClient http, String baseUrl, String apiKey) {
        this.http = http;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public String provider() {
        return "anthropic";
    }

    Map<String, Object> body(LlmRequest req) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage m : req.messages()) {
            String role;
            List<Object> blocks = new ArrayList<>();
            if (m instanceof UserMessage u) {
                role = "user";
                if (u.content() != null && !u.content().isBlank()) blocks.add(Json.obj("type", "text", "text", u.content()));
            } else if (m instanceof AssistantMessage a) {
                role = "assistant";
                if (a.content() != null && !a.content().isBlank()) blocks.add(Json.obj("type", "text", "text", a.content()));
                for (ToolCall c : a.toolCalls()) {
                    blocks.add(Json.obj("type", "tool_use", "id", c.id(), "name", c.name(), "input", parseObject(c.arguments())));
                }
            } else if (m instanceof ToolResultMessage t) {
                role = "user";
                blocks.add(Json.obj("type", "tool_result", "tool_use_id", t.toolCallId(), "content", t.content()));
            } else {
                continue;
            }
            if (blocks.isEmpty()) continue;
            Map<String, Object> last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
            if (last != null && last.get("role").equals(role)) {
                @SuppressWarnings("unchecked")
                List<Object> existing = (List<Object>) last.get("content");
                existing.addAll(blocks);
            } else {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", role);
                msg.put("content", blocks);
                messages.add(msg);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("max_tokens", req.maxOutputTokens() > 0 ? req.maxOutputTokens() : 4096);
        String system = req.system() == null ? "" : req.system();
        if (req.jsonOutput()) system = system + "\n\nRespond with a single JSON object and nothing else.";
        if (!system.isBlank()) body.put("system", system.strip());
        body.put("messages", messages);
        if (req.tools() != null && !req.tools().isEmpty()) {
            List<Object> tools = new ArrayList<>();
            for (ToolSpec t : req.tools()) {
                tools.add(Json.obj("name", t.name(), "description", t.description(), "input_schema", t.parameters()));
            }
            body.put("tools", tools);
            body.put("tool_choice", Json.obj("type", switch (req.toolChoice()) {
                case REQUIRED -> "any";
                case NONE -> "none";
                default -> "auto";
            }));
        }
        body.put("stream", true);
        return body;
    }

    private static Object parseObject(String json) {
        try {
            Object v = Json.parse(json == null || json.isBlank() ? "{}" : json);
            return v instanceof Map<?, ?> ? v : new LinkedHashMap<>();
        } catch (JsonException e) {
            return new LinkedHashMap<>();
        }
    }

    @Override
    public LlmResult stream(LlmRequest req, StreamCallbacks callbacks, CancelToken cancel) throws LlmException {
        cancel.throwIfCancelled();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                .timeout(Duration.ofSeconds(90))
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body(req)), StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response = send(request, cancel);
        if (response.statusCode() != 200) {
            String body = OpenAiCompatibleClient.readError(response.body());
            throw LlmException.fromHttp("anthropic", response.statusCode(), body, OpenAiCompatibleClient.retryAfter(response));
        }

        State state = new State();
        SseReader.read(response.body(), cancel, IDLE_TIMEOUT_MS, "anthropic", (event, data) -> {
            if (data.isBlank()) return true;
            JNode d;
            try {
                d = Json.tree(data);
            } catch (JsonException e) {
                return true;
            }
            String type = d.get("type").asString(event == null ? "" : event);
            return state.apply(type, d, callbacks);
        });
        return state.result(req.model());
    }

    private HttpResponse<InputStream> send(HttpRequest request, CancelToken cancel) throws LlmException {
        CompletableFuture<HttpResponse<InputStream>> future = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        Runnable unregister = cancel.onCancel(() -> future.cancel(true));
        try {
            return future.get(95, TimeUnit.SECONDS);
        } catch (CancellationException e) {
            throw new LlmException(LlmException.Kind.CANCELLED, "cancelled");
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmException(LlmException.Kind.UNAVAILABLE, 0, "anthropic did not respond in time", 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.CANCELLED, "interrupted");
        } catch (ExecutionException e) {
            if (cancel.isCancelled()) throw new LlmException(LlmException.Kind.CANCELLED, "cancelled");
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new LlmException(LlmException.Kind.UNAVAILABLE, 0, "Could not reach anthropic: " + c, 0);
        } finally {
            unregister.run();
        }
    }

    static final class State {
        private static final class Block {
            String type;
            String id;
            String name;
            final StringBuilder input = new StringBuilder();
        }

        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, Block> blocks = new TreeMap<>();
        private String stopReason;
        private Long inputTokens;
        private Long outputTokens;

        boolean apply(String type, JNode d, StreamCallbacks callbacks) throws LlmException {
            switch (type) {
                case "message_start" -> {
                    JNode usage = d.path("message", "usage");
                    if (usage.get("input_tokens").isNumber()) inputTokens = usage.get("input_tokens").asLong(0);
                }
                case "content_block_start" -> {
                    Block b = new Block();
                    JNode cb = d.get("content_block");
                    b.type = cb.get("type").text();
                    b.id = cb.get("id").asString(null);
                    b.name = cb.get("name").asString(null);
                    if ("text".equals(b.type) && !cb.get("text").text().isEmpty()) {
                        text.append(cb.get("text").text());
                        callbacks.onText(cb.get("text").text());
                    }
                    blocks.put(d.get("index").asInt(blocks.size()), b);
                }
                case "content_block_delta" -> {
                    JNode delta = d.get("delta");
                    String dt = delta.get("type").text();
                    if (dt.equals("text_delta")) {
                        String t = delta.get("text").text();
                        if (!t.isEmpty()) {
                            text.append(t);
                            callbacks.onText(t);
                        }
                    } else if (dt.equals("input_json_delta")) {
                        Block b = blocks.get(d.get("index").asInt(-1));
                        if (b != null) b.input.append(delta.get("partial_json").text());
                    }
                }
                case "message_delta" -> {
                    String sr = d.path("delta", "stop_reason").asString(null);
                    if (sr != null) stopReason = sr;
                    JNode usage = d.get("usage");
                    if (usage.get("output_tokens").isNumber()) outputTokens = usage.get("output_tokens").asLong(0);
                }
                case "message_stop" -> {
                    return false;
                }
                case "error" -> {
                    String errType = d.path("error", "type").text();
                    String message = "anthropic stream error: " + d.path("error", "message").asString(errType);
                    LlmException.Kind kind = switch (errType) {
                        case "rate_limit_error" -> LlmException.Kind.RATE_LIMITED;
                        case "authentication_error", "permission_error" -> LlmException.Kind.AUTH;
                        case "invalid_request_error" -> LlmException.Kind.BAD_REQUEST;
                        case "not_found_error" -> LlmException.Kind.MODEL_NOT_FOUND;
                        default -> LlmException.Kind.UNAVAILABLE;
                    };
                    throw new LlmException(kind, 0, message, 0);
                }
                default -> {
                    // ping, content_block_stop
                }
            }
            return true;
        }

        LlmResult result(String model) {
            List<ToolCall> calls = new ArrayList<>();
            for (Block b : blocks.values()) {
                if (!"tool_use".equals(b.type) || b.name == null) continue;
                String args = b.input.length() == 0 ? "{}" : b.input.toString();
                calls.add(new ToolCall(b.id, b.name, args, Map.of()));
            }
            String finish = stopReason == null ? (calls.isEmpty() ? "stop" : "tool_calls") : switch (stopReason) {
                case "tool_use" -> "tool_calls";
                case "max_tokens" -> "length";
                case "refusal" -> "content_filter";
                default -> calls.isEmpty() ? "stop" : "tool_calls";
            };
            return new LlmResult(text.toString(), calls, finish, new Usage(inputTokens, outputTokens), model);
        }
    }
}
