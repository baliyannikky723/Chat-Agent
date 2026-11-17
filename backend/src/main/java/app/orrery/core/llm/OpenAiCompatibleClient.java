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
import app.orrery.core.util.Ids;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The OpenAI Chat Completions streaming protocol — spoken by OpenAI, Groq, Gemini (via its
 * OpenAI-compatible endpoint), OpenRouter and local servers such as Ollama. One client,
 * with a "flavor" for the few places providers differ:
 *
 * <ul>
 *   <li>token limit field ({@code max_completion_tokens} vs {@code max_tokens}),</li>
 *   <li>which models accept {@code reasoning_effort},</li>
 *   <li>Gemini: tool calls may arrive without index or id, and carry a thought signature in
 *       {@code extra_content} that must be echoed back or the next request fails.</li>
 * </ul>
 */
public final class OpenAiCompatibleClient implements LlmClient {

    public enum Flavor { OPENAI, GROQ, GOOGLE, OPENROUTER, GENERIC }

    private static final long IDLE_TIMEOUT_MS = 120_000;
    private static final Set<String> STANDARD_CALL_KEYS = Set.of("index", "id", "type", "function");

    private final HttpClient http;
    private final String provider;
    private final Flavor flavor;
    private final String baseUrl;
    private final String apiKey;

    public OpenAiCompatibleClient(HttpClient http, String provider, Flavor flavor, String baseUrl, String apiKey) {
        this.http = http;
        this.provider = provider;
        this.flavor = flavor;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public String provider() {
        return provider;
    }

    static boolean acceptsReasoningEffort(Flavor flavor, String model) {
        String m = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return switch (flavor) {
            case OPENAI -> m.startsWith("gpt-5") || m.matches("^o\\d.*");
            case GROQ -> m.contains("gpt-oss");
            case GOOGLE -> m.contains("gemini-2.5") || m.contains("gemini-3") || m.contains("gemini-4") || m.endsWith("-latest");
            default -> false;
        };
    }

    Map<String, Object> body(LlmRequest req) {
        List<Object> messages = new ArrayList<>();
        if (req.system() != null && !req.system().isBlank()) messages.add(Json.obj("role", "system", "content", req.system()));
        for (ChatMessage m : req.messages()) {
            if (m instanceof UserMessage u) {
                messages.add(Json.obj("role", "user", "content", u.content()));
            } else if (m instanceof AssistantMessage a) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "assistant");
                boolean hasCalls = a.toolCalls() != null && !a.toolCalls().isEmpty();
                if (a.content() != null && !a.content().isBlank()) msg.put("content", a.content());
                else if (!hasCalls) msg.put("content", "");
                if (hasCalls) {
                    List<Object> calls = new ArrayList<>();
                    for (ToolCall c : a.toolCalls()) {
                        Map<String, Object> call = new LinkedHashMap<>();
                        call.put("id", c.id());
                        call.put("type", "function");
                        call.put("function", Json.obj("name", c.name(), "arguments", c.arguments()));
                        if (c.extra() != null) call.putAll(c.extra());
                        calls.add(call);
                    }
                    msg.put("tool_calls", calls);
                }
                messages.add(msg);
            } else if (m instanceof ToolResultMessage t) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("role", "tool");
                msg.put("tool_call_id", t.toolCallId());
                if (flavor == Flavor.GOOGLE) msg.put("name", t.toolName());
                msg.put("content", t.content());
                messages.add(msg);
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("messages", messages);
        body.put("stream", true);
        if (req.tools() != null && !req.tools().isEmpty()) {
            List<Object> tools = new ArrayList<>();
            for (ToolSpec t : req.tools()) {
                tools.add(Json.obj("type", "function", "function",
                        Json.obj("name", t.name(), "description", t.description(), "parameters", t.parameters())));
            }
            body.put("tools", tools);
            body.put("tool_choice", switch (req.toolChoice()) {
                case REQUIRED -> "required";
                case NONE -> "none";
                default -> "auto";
            });
        }
        if (req.maxOutputTokens() > 0) {
            body.put(flavor == Flavor.OPENAI || flavor == Flavor.GROQ ? "max_completion_tokens" : "max_tokens", req.maxOutputTokens());
        }
        if (req.reasoningEffort() != null && !req.reasoningEffort().isBlank() && !req.reasoningEffort().equals("default")
                && acceptsReasoningEffort(flavor, req.model())) {
            body.put("reasoning_effort", req.reasoningEffort());
        }
        if (req.jsonOutput() && (req.tools() == null || req.tools().isEmpty()) && flavor != Flavor.GENERIC) {
            body.put("response_format", Json.obj("type", "json_object"));
        }
        if (flavor == Flavor.OPENAI || flavor == Flavor.OPENROUTER) body.put("stream_options", Json.obj("include_usage", true));
        return body;
    }

    @Override
    public LlmResult stream(LlmRequest req, StreamCallbacks callbacks, CancelToken cancel) throws LlmException {
        cancel.throwIfCancelled();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body(req)), StandardCharsets.UTF_8));
        if (flavor == Flavor.OPENROUTER) builder.header("X-Title", "Orrery");

        HttpResponse<InputStream> response = send(builder.build(), cancel);
        if (response.statusCode() != 200) {
            String errorBody = readError(response.body());
            throw LlmException.fromHttp(provider, response.statusCode(), errorBody, retryAfter(response));
        }

        StreamState state = new StreamState();
        SseReader.read(response.body(), cancel, IDLE_TIMEOUT_MS, provider, (event, data) -> {
            String d = data.strip();
            if (d.isEmpty()) return true;
            if (d.equals("[DONE]")) return false;
            JNode chunk;
            try {
                chunk = Json.tree(d);
            } catch (JsonException e) {
                return true; // tolerate a malformed keep-alive line
            }
            state.apply(chunk, callbacks, provider);
            return true;
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
            throw new LlmException(LlmException.Kind.UNAVAILABLE, 0, provider + " did not respond in time", 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.CANCELLED, "interrupted");
        } catch (ExecutionException e) {
            if (cancel.isCancelled()) throw new LlmException(LlmException.Kind.CANCELLED, "cancelled");
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new LlmException(LlmException.Kind.UNAVAILABLE, 0, "Could not reach " + provider + ": " + c, 0);
        } finally {
            unregister.run();
        }
    }

    static String readError(InputStream in) {
        try (in) {
            return new String(in.readNBytes(64 * 1024), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    static long retryAfter(HttpResponse<?> response) {
        String v = response.headers().firstValue("retry-after").orElse(null);
        if (v == null) return 0;
        try {
            return (long) (Double.parseDouble(v.strip()) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Accumulates streamed deltas into text and complete tool calls. */
    static final class StreamState {
        private static final class PendingCall {
            String id;
            String name;
            final StringBuilder args = new StringBuilder();
            final Map<String, Object> extra = new LinkedHashMap<>();
        }

        private final StringBuilder text = new StringBuilder();
        private final Map<String, PendingCall> calls = new LinkedHashMap<>();
        private String lastKey;
        private String finish;
        private Usage usage = Usage.NONE;

        void apply(JNode chunk, StreamCallbacks callbacks, String provider) throws LlmException {
            JNode error = chunk.get("error");
            if (error.isObject() || error.isString()) {
                String message = error.isString() ? error.text() : error.get("message").asString(error.toString());
                throw new LlmException(LlmException.Kind.UNAVAILABLE, 0, provider + " stream error: " + message, 0);
            }
            JNode u = chunk.get("usage").isObject() ? chunk.get("usage") : chunk.path("x_groq", "usage");
            if (u.isObject()) {
                usage = new Usage(u.get("prompt_tokens").isNumber() ? u.get("prompt_tokens").asLong(0) : usage.inputTokens(),
                        u.get("completion_tokens").isNumber() ? u.get("completion_tokens").asLong(0) : usage.outputTokens());
            }
            for (JNode choice : chunk.get("choices").items()) {
                if (choice.get("index").asInt(0) != 0) continue;
                JNode delta = choice.get("delta").isObject() ? choice.get("delta") : choice.get("message");
                String content = delta.get("content").asString(null);
                if (content != null && !content.isEmpty()) {
                    text.append(content);
                    callbacks.onText(content);
                }
                for (JNode tc : delta.get("tool_calls").items()) applyToolCall(tc);
                String fr = choice.get("finish_reason").asString(null);
                if (fr != null && !fr.isBlank()) finish = fr;
            }
        }

        private void applyToolCall(JNode tc) {
            String id = tc.get("id").asString(null);
            boolean hasId = id != null && !id.isBlank();
            String name = tc.path("function", "name").asString(null);
            String key;
            if (tc.get("index").isNumber()) {
                key = "i" + tc.get("index").asInt(0);
            } else if (hasId) {
                key = "id:" + id;
            } else if (lastKey != null && !(name != null && !name.isBlank() && calls.get(lastKey).name != null)) {
                key = lastKey; // continuation of the current call
            } else {
                key = "n" + calls.size(); // a complete call without index or id (Gemini)
            }
            PendingCall pc = calls.computeIfAbsent(key, k -> new PendingCall());
            if (hasId && pc.id == null) pc.id = id;
            if (name != null && !name.isBlank() && pc.name == null) pc.name = name;
            JNode args = tc.path("function", "arguments");
            if (args.isString()) {
                pc.args.append(args.text());
            } else if (args.isObject()) {
                // some servers send the arguments as an object instead of a JSON string
                pc.args.setLength(0);
                pc.args.append(Json.write(args.raw()));
            }
            for (String k : tc.keys()) {
                if (!STANDARD_CALL_KEYS.contains(k)) pc.extra.put(k, tc.get(k).raw());
            }
            lastKey = key;
        }

        LlmResult result(String model) {
            List<ToolCall> out = new ArrayList<>();
            for (PendingCall pc : calls.values()) {
                if (pc.name == null || pc.name.isBlank()) continue;
                String args = pc.args.length() == 0 ? "{}" : pc.args.toString();
                String id = pc.id == null || pc.id.isBlank() ? "call_" + Ids.uuidv7().replace("-", "").substring(0, 20) : pc.id;
                out.add(new ToolCall(id, pc.name, args, pc.extra.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(pc.extra))));
            }
            String fr = finish == null ? (out.isEmpty() ? "stop" : "tool_calls") : switch (finish) {
                case "stop", "end_turn" -> out.isEmpty() ? "stop" : "tool_calls";
                case "tool_calls", "function_call" -> "tool_calls";
                case "length", "max_tokens" -> "length";
                case "content_filter", "safety" -> "content_filter";
                default -> out.isEmpty() ? "other" : "tool_calls";
            };
            return new LlmResult(text.toString(), out, fr, usage, model);
        }
    }
}
