package app.orrery.core.agent;

import app.orrery.core.llm.LlmModels.AssistantMessage;
import app.orrery.core.llm.LlmModels.ChatMessage;
import app.orrery.core.llm.LlmModels.UserMessage;
import app.orrery.core.store.Store.MessageRow;
import app.orrery.core.store.Store.SourceRow;
import app.orrery.core.util.Citations;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The slice of the current conversation sent with each turn: newest messages first until
 * the character budget is used, oldest dropped. Past answers have their [n] citations
 * rewritten to "(domain)" so the model never re-cites numbers from an earlier turn.
 */
public final class ConversationWindow {

    private ConversationWindow() {}

    public static List<ChatMessage> build(List<MessageRow> history, Map<String, List<SourceRow>> sources, int maxChars) {
        Deque<ChatMessage> window = new ArrayDeque<>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            MessageRow m = history.get(i);
            String content = m.content() == null ? "" : m.content().strip();
            if (content.isEmpty()) continue;
            ChatMessage msg;
            if ("assistant".equals(m.role())) {
                Map<Integer, String> domains = new HashMap<>();
                for (SourceRow s : sources.getOrDefault(m.id(), List.of())) domains.put(s.n(), s.domain());
                content = Citations.rewriteToDomains(content, domains);
                if ("stopped".equals(m.status())) content = content + "\n\n(This answer was stopped before it finished.)";
                msg = new AssistantMessage(content);
            } else {
                msg = new UserMessage(content);
            }
            if (used + content.length() > maxChars && !window.isEmpty()) break;
            used += content.length();
            window.addFirst(msg);
        }
        // The window must start with a user message, and roles must alternate.
        List<ChatMessage> out = new ArrayList<>();
        for (ChatMessage m : window) {
            if (out.isEmpty() && m instanceof AssistantMessage) continue;
            ChatMessage prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (prev instanceof UserMessage pu && m instanceof UserMessage mu) {
                out.set(out.size() - 1, new UserMessage(pu.content() + "\n\n" + mu.content()));
            } else if (prev instanceof AssistantMessage pa && m instanceof AssistantMessage ma) {
                out.set(out.size() - 1, new AssistantMessage(pa.content() + "\n\n" + ma.content()));
            } else {
                out.add(m);
            }
        }
        return out;
    }
}
