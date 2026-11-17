package app.orrery.web;

import app.orrery.core.AgentRuntime;
import app.orrery.core.events.AgentEvent.SourceRef;
import app.orrery.core.json.JNode;
import app.orrery.core.json.Json;
import app.orrery.core.json.JsonException;
import app.orrery.core.store.Store;
import app.orrery.core.store.Store.ConversationRow;
import app.orrery.core.store.Store.MessageRow;
import app.orrery.core.store.Store.SearchLog;
import app.orrery.core.store.Store.SourceRow;
import app.orrery.core.util.Ids;
import app.orrery.core.util.Text;
import app.orrery.web.ApiDtos.ConversationDetailDto;
import app.orrery.web.ApiDtos.ConversationDto;
import app.orrery.web.ApiDtos.MessageDto;
import app.orrery.web.ApiDtos.OkDto;
import app.orrery.web.ApiDtos.RenameRequest;
import app.orrery.web.ApiDtos.SearchDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final Store store;

    public ConversationController(AgentRuntime runtime) {
        this.store = runtime.store();
    }

    @GetMapping
    public List<ConversationDto> list(HttpServletRequest request) {
        UserContext user = UserContext.of(request);
        return store.listConversations(user.id(), 300).stream()
                .map(c -> new ConversationDto(c.id(), c.title(), c.createdAt(), c.updatedAt(), c.lastLocalDate(), c.turns()))
                .toList();
    }

    @GetMapping("/{id}")
    public ConversationDetailDto get(@PathVariable("id") String id, HttpServletRequest request) {
        UserContext user = UserContext.of(request);
        ConversationRow conv = requireConversation(user, id);
        List<MessageRow> messages = store.conversationMessages(conv.id(), 400);
        List<String> answerIds = messages.stream().filter(m -> "assistant".equals(m.role())).map(MessageRow::id).toList();
        Map<String, List<SourceRow>> sources = store.sourcesFor(answerIds);
        Map<String, List<SearchLog>> searches = store.searchesFor(answerIds);
        List<MessageDto> out = new ArrayList<>(messages.size());
        for (MessageRow m : messages) {
            List<SourceRow> rows = sources.getOrDefault(m.id(), List.of());
            out.add(new MessageDto(m.id(), m.role(), m.content(), m.status(), m.createdAt(), m.localDate().toString(),
                    rows.stream().map(s -> new SourceRef(s.n(), s.url(), s.title(), s.domain(), s.publishedAt(), s.snippet())).toList(),
                    rows.stream().filter(SourceRow::cited).map(SourceRow::n).toList(),
                    searches.getOrDefault(m.id(), List.of()).stream().map(ConversationController::search).toList(),
                    parse(m.metricsJson()), parse(m.recallJson())));
        }
        String title = conv.title() == null || conv.title().isBlank()
                ? (messages.isEmpty() ? "New conversation" : Text.firstWords(messages.get(0).content(), 7))
                : conv.title();
        return new ConversationDetailDto(conv.id(), title, conv.createdAt(), out);
    }

    @PatchMapping("/{id}")
    public OkDto rename(@PathVariable("id") String id, @RequestBody RenameRequest body, HttpServletRequest request) {
        UserContext user = UserContext.of(request);
        String title = body == null || body.title() == null ? "" : Text.cleanTitle(body.title());
        if (title.isBlank()) throw ApiException.badRequest("Title can't be empty.");
        requireConversation(user, id);
        return new OkDto(store.renameConversation(user.id(), id, title));
    }

    @DeleteMapping("/{id}")
    public OkDto delete(@PathVariable("id") String id, HttpServletRequest request) {
        UserContext user = UserContext.of(request);
        requireConversation(user, id);
        return new OkDto(store.deleteConversation(user.id(), id));
    }

    private ConversationRow requireConversation(UserContext user, String id) {
        if (!Ids.isUuid(id)) throw ApiException.notFound("Conversation");
        ConversationRow conv = store.conversation(user.id(), id);
        if (conv == null) throw ApiException.notFound("Conversation");
        return conv;
    }

    static SearchDto search(SearchLog log) {
        JNode params;
        try {
            params = Json.tree(log.paramsJson() == null ? "{}" : log.paramsJson());
        } catch (JsonException e) {
            params = Json.tree("{}");
        }
        return new SearchDto(log.query(), params.get("since").asString(null), params.get("recency").asString(null), log.resultCount(),
                log.cached(), log.durationMs(), log.error());
    }

    static Object parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return Json.parse(json);
        } catch (JsonException e) {
            return null;
        }
    }
}
