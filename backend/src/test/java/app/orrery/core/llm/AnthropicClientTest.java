package app.orrery.core.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import app.orrery.core.util.CancelToken;
import app.orrery.support.FakeHttp;
import app.orrery.support.FakeHttp.Reply;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnthropicClientTest {

    private static final ToolSpec SEARCH = new ToolSpec("web_search", "search", Json.obj("type", "object", "properties", Json.obj()));

    @Test
    void streamsTextThenAToolUseBlock() throws Exception {
        try (FakeHttp server = new FakeHttp()) {
            server.route("/v1/messages", req -> Reply.sse(List.of(
                    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":120,\"output_tokens\":1}}}",
                    "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Let me check.\"}}",
                    "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"web_search\",\"input\":{}}}",
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\": \\\"neon\"}}",
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\" pricing\\\"}\"}}",
                    "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":1}",
                    "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":31}}",
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}")));
            AnthropicClient client = new AnthropicClient(HttpClient.newHttpClient(), server.baseUrl(), "sk-ant");
            LlmRequest req = new LlmRequest("claude-sonnet-5-5", "sys", List.of(new UserMessage("neon?")), List.of(SEARCH), ToolChoice.REQUIRED, 2000, "low", false);
            LlmResult r = client.stream(req, d -> {}, CancelToken.none());
            assertEquals("Let me check.", r.text());
            assertEquals(1, r.toolCalls().size());
            assertEquals("{\"query\": \"neon pricing\"}", r.toolCalls().get(0).arguments());
            assertEquals("tool_calls", r.finishReason());
            assertEquals(120L, r.usage().inputTokens());
            assertEquals(31L, r.usage().outputTokens());

            JNode body = Json.tree(server.requests().get(0).body());
            assertEquals("any", body.path("tool_choice", "type").text());
            assertEquals("sys", body.get("system").text());
            assertEquals("2023-06-01", server.requests().get(0).headers().get("Anthropic-version").get(0));
        }
    }

    @Test
    void toolResultsBecomeOneUserMessage() {
        AnthropicClient client = new AnthropicClient(HttpClient.newHttpClient(), "http://unused", "k");
        List<ToolCall> calls = List.of(new ToolCall("t1", "web_search", "{\"query\":\"a\"}", Map.of()),
                new ToolCall("t2", "web_search", "{\"query\":\"b\"}", Map.of()));
        LlmRequest req = new LlmRequest("m", "s", List.of(new UserMessage("q"), new AssistantMessage("", calls),
                new ToolResultMessage("t1", "web_search", "A"), new ToolResultMessage("t2", "web_search", "B")),
                List.of(SEARCH), ToolChoice.AUTO, 100, null, false);
        JNode messages = Json.tree(Json.write(client.body(req))).get("messages");
        assertEquals(3, messages.size());
        assertEquals("assistant", messages.at(1).get("role").text());
        assertEquals("tool_use", messages.at(1).get("content").at(0).get("type").text(), "no empty text block");
        assertEquals("a", messages.at(1).get("content").at(0).path("input", "query").text());
        assertEquals(2, messages.at(2).get("content").size());
        assertTrue(messages.at(2).get("content").at(1).get("type").text().equals("tool_result"));
    }
}
