package app.orrery.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.orrery.core.json.JNode;
import app.orrery.core.json.Json;
import app.orrery.core.llm.LlmModels.AssistantMessage;
import app.orrery.core.llm.LlmModels.LlmRequest;
import app.orrery.core.llm.LlmModels.LlmResult;
import app.orrery.core.llm.LlmModels.ToolCall;
import app.orrery.core.llm.LlmModels.ToolChoice;
import app.orrery.core.llm.LlmModels.ToolResultMessage;
import app.orrery.core.llm.LlmModels.ToolSpec;
import app.orrery.core.llm.LlmModels.UserMessage;
import app.orrery.core.llm.OpenAiCompatibleClient.Flavor;
import app.orrery.core.util.CancelToken;
import app.orrery.support.FakeHttp;
import app.orrery.support.FakeHttp.Reply;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleClientTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ToolSpec SEARCH = new ToolSpec("web_search", "search", Json.obj("type", "object", "properties", Json.obj()));

    private static LlmRequest request(String model, List<LlmModels.ChatMessage> messages) {
        return new LlmRequest(model, "be brief", messages, List.of(SEARCH), ToolChoice.AUTO, 1000, "low", false);
    }

    @Test
    void streamsTextAndUsage() throws Exception {
        try (FakeHttp server = new FakeHttp()) {
            server.route("/chat/completions", req -> Reply.sse(List.of(
                    "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo [1]\"},\"finish_reason\":\"stop\"}]}",
                    "{\"choices\":[],\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":3}}",
                    "[DONE]")));
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(HTTP, "openai", Flavor.OPENAI, server.baseUrl(), "sk-test");
            StringBuilder streamed = new StringBuilder();
            LlmResult r = client.stream(request("gpt-5.6-terra", List.of(new UserMessage("hi"))), streamed::append, CancelToken.none());
            assertEquals("Hello [1]", r.text());
            assertEquals("Hello [1]", streamed.toString());
            assertEquals("stop", r.finishReason());
            assertEquals(42L, r.usage().inputTokens());

            JNode body = Json.tree(server.requests().get(0).body());
            assertEquals(1000L, body.get("max_completion_tokens").asLong(0));
            assertEquals("low", body.get("reasoning_effort").text());
            assertTrue(body.get("temperature").isMissing(), "reasoning models reject temperature");
            assertEquals("system", body.get("messages").at(0).get("role").text());
            assertTrue(body.get("stream_options").get("include_usage").asBoolean(false));
        }
    }

    @Test
    void assemblesParallelToolCallsStreamedInPieces() throws Exception {
        try (FakeHttp server = new FakeHttp()) {
            server.route("/chat/completions", req -> Reply.sse(List.of(
                    "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\",\"function\":{\"name\":\"web_search\",\"arguments\":\"\"}}]}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"query\\\":\\\"hub\"}}]}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call_b\",\"type\":\"function\",\"function\":{\"name\":\"web_search\",\"arguments\":\"{\\\"query\\\":\\\"pipedrive\\\"}\"}}]}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"spot pricing\\\"}\"}}]}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                    "[DONE]")));
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(HTTP, "groq", Flavor.GROQ, server.baseUrl(), "gsk");
            LlmResult r = client.stream(request("openai/gpt-oss-120b", List.of(new UserMessage("compare"))), d -> {}, CancelToken.none());
            assertEquals(2, r.toolCalls().size());
            assertEquals("{\"query\":\"hubspot pricing\"}", r.toolCalls().get(0).arguments());
            assertEquals("call_b", r.toolCalls().get(1).id());
            assertEquals("tool_calls", r.finishReason());
        }
    }

    @Test
    void geminiToolCallsWithoutIdsKeepTheirThoughtSignature() throws Exception {
        try (FakeHttp server = new FakeHttp()) {
            server.route("/chat/completions", req -> Reply.sse(List.of(
                    "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"extra_content\":{\"google\":{\"thought_signature\":\"SIG1\"}},\"function\":{\"arguments\":\"{\\\"query\\\":\\\"neon\\\"}\",\"name\":\"web_search\"},\"id\":\"\",\"type\":\"function\"}]}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"query\\\":\\\"supabase\\\"}\",\"name\":\"web_search\"},\"id\":\"\",\"type\":\"function\"}]},\"finish_reason\":\"stop\"}]}",
                    "[DONE]")));
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(HTTP, "google", Flavor.GOOGLE, server.baseUrl(), "AIza");
            LlmResult r = client.stream(request("gemini-flash-latest", List.of(new UserMessage("compare"))), d -> {}, CancelToken.none());
            assertEquals(2, r.toolCalls().size(), "two complete calls without index or id");
            ToolCall first = r.toolCalls().get(0);
            assertFalse(first.id().isBlank(), "a missing id is generated");
            assertEquals("tool_calls", r.finishReason());

            // The signature must go back verbatim with the assistant turn.
            LlmRequest next = request("gemini-flash-latest", List.of(new UserMessage("compare"),
                    new AssistantMessage("", r.toolCalls()), new ToolResultMessage(first.id(), "web_search", "results")));
            JNode body = Json.tree(Json.write(client.body(next)));
            JNode assistant = body.get("messages").at(2);
            assertTrue(assistant.get("content").isMissing(), "Gemini rejects empty content");
            assertEquals("SIG1", assistant.get("tool_calls").at(0).path("extra_content", "google", "thought_signature").text());
            assertEquals("web_search", body.get("messages").at(3).get("name").text());
            assertEquals(1000L, body.get("max_tokens").asLong(0));
            assertEquals("low", body.get("reasoning_effort").text());
        }
    }

    @Test
    void reasoningEffortIsOnlySentToModelsThatAcceptIt() {
        assertTrue(OpenAiCompatibleClient.acceptsReasoningEffort(Flavor.OPENAI, "gpt-5.4-mini"));
        assertFalse(OpenAiCompatibleClient.acceptsReasoningEffort(Flavor.OPENAI, "gpt-4.1-mini"));
        assertTrue(OpenAiCompatibleClient.acceptsReasoningEffort(Flavor.GROQ, "openai/gpt-oss-20b"));
        assertFalse(OpenAiCompatibleClient.acceptsReasoningEffort(Flavor.GROQ, "llama-3.3-70b-versatile"));
        assertFalse(OpenAiCompatibleClient.acceptsReasoningEffort(Flavor.GENERIC, "anything"));
    }

    @Test
    void httpErrorsAreClassified() {
        try (FakeHttp server = new FakeHttp()) {
            server.route("/chat/completions", req -> {
                String model = Json.tree(req.body()).get("model").text();
                return switch (model) {
                    case "missing" -> Reply.status(404, "{\"error\":{\"message\":\"The model `missing` does not exist\",\"code\":\"model_not_found\"}}");
                    case "nokey" -> Reply.status(401, "{\"error\":{\"message\":\"Incorrect API key\"}}");
                    case "broke" -> Reply.status(429, "{\"error\":{\"code\":\"insufficient_quota\"}}");
                    default -> new Reply(429, "{\"error\":{\"message\":\"slow down\"}}", null, Map.of("Retry-After", "2"));
                };
            });
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(HTTP, "openai", Flavor.OPENAI, server.baseUrl(), "sk");
            assertEquals(LlmException.Kind.MODEL_NOT_FOUND, assertThrows(LlmException.class,
                    () -> client.stream(request("missing", List.of(new UserMessage("x"))), d -> {}, CancelToken.none())).kind());
            assertEquals(LlmException.Kind.AUTH, assertThrows(LlmException.class,
                    () -> client.stream(request("nokey", List.of(new UserMessage("x"))), d -> {}, CancelToken.none())).kind());
            assertEquals(LlmException.Kind.AUTH, assertThrows(LlmException.class,
                    () -> client.stream(request("broke", List.of(new UserMessage("x"))), d -> {}, CancelToken.none())).kind());
            LlmException limited = assertThrows(LlmException.class,
                    () -> client.stream(request("busy", List.of(new UserMessage("x"))), d -> {}, CancelToken.none()));
            assertEquals(LlmException.Kind.RATE_LIMITED, limited.kind());
            assertEquals(2000L, limited.retryAfterMillis());
        }
    }

    @Test
    void errorInsideTheStreamIsRaised() {
        try (FakeHttp server = new FakeHttp()) {
            server.route("/chat/completions", req -> Reply.sse(List.of(
                    "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}",
                    "{\"error\":{\"message\":\"overloaded\"}}")));
            OpenAiCompatibleClient client = new OpenAiCompatibleClient(HTTP, "openrouter", Flavor.OPENROUTER, server.baseUrl(), "k");
            LlmException e = assertThrows(LlmException.class,
                    () -> client.stream(request("m", List.of(new UserMessage("x"))), d -> {}, CancelToken.none()));
            assertTrue(e.getMessage().contains("overloaded"));
        }
    }
}
