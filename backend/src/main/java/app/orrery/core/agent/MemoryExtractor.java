package app.orrery.core.agent;

import app.orrery.core.json.JNode;
import app.orrery.core.json.Json;
import app.orrery.core.llm.LlmException;
import app.orrery.core.llm.LlmModels.LlmRequest;
import app.orrery.core.llm.LlmModels.ToolChoice;
import app.orrery.core.llm.LlmModels.UserMessage;
import app.orrery.core.llm.ModelRouter;
import app.orrery.core.store.Store;
import app.orrery.core.store.Store.MessageRow;
import app.orrery.core.store.Store.NewClaim;
import app.orrery.core.store.Store.SourceRow;
import app.orrery.core.util.CancelToken;
import app.orrery.core.util.Text;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Background job, run after an answer is delivered. The fast model reads the answer and
 * writes down:
 *
 * <ul>
 *   <li><b>claims</b> — time-sensitive facts with their value and source, dated to the
 *       answer (the claim ledger: "HubSpot Professional = $90/seat/month, as of 9 Nov");</li>
 *   <li><b>entities</b> — what the answer was about ("the 5 CRM tools");</li>
 *   <li><b>keywords</b> — synonyms that make full-text recall work ("postgres", "postgresql").</li>
 * </ul>
 *
 * Re-checks then compare facts, not prose. The user never waits for this step.
 */
public final class MemoryExtractor {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractor.class);
    private static final int MAX_CLAIMS = 25;

    private final Store store;
    private final ModelRouter router;

    public MemoryExtractor(Store store, ModelRouter router) {
        this.store = store;
        this.router = router;
    }

    public void extract(String userId, String assistantMessageId) throws LlmException {
        MessageRow answer = store.message(userId, assistantMessageId);
        if (answer == null || !"assistant".equals(answer.role())) return;
        MessageRow question = store.message(userId, answer.turnId());
        List<SourceRow> sources = store.sourcesFor(List.of(answer.id())).getOrDefault(answer.id(), List.of());

        StringBuilder src = new StringBuilder();
        for (SourceRow s : sources) {
            if (src.length() > 3000) break;
            src.append('[').append(s.n()).append("] ").append(s.title()).append(" — ").append(s.url()).append('\n');
        }
        String prompt = """
                Today is %s. Below are a question, the answer an assistant gave, and the answer's numbered sources.

                Return a JSON object with exactly these keys:
                - "entities": the products, companies, people or places the answer is about (canonical names, at most 12)
                - "keywords": 5 to 12 lowercase search keywords and synonyms for the topic (for example "postgres", "postgresql", "managed database", "hosting")
                - "claims": the time-sensitive facts stated in the answer that could change later: prices, plan names and limits, \
                features, versions, release dates, rankings, who holds a role, policies, statistics. Skip opinions, advice and \
                timeless facts. Each claim is {"entity": string, "attribute": string, "value": string, "statement": string, "source": number or null}. \
                "value" is the exact value as stated, with currency, unit and billing period. "statement" is one self-contained sentence. \
                "source" is the citation number used for it. At most %d claims; an empty list is fine.

                QUESTION:
                %s

                ANSWER:
                %s

                SOURCES:
                %s""".formatted(answer.localDate(), MAX_CLAIMS,
                question == null ? "(not available)" : Text.truncate(question.content(), 1500),
                Text.truncate(answer.content(), 9000), src.length() == 0 ? "(none)" : src);

        LlmRequest req = new LlmRequest(null, "You extract structured facts from text. Output JSON only.",
                List.of(new UserMessage(prompt)), List.of(), ToolChoice.AUTO, 3000, router.reasoningEffort(), true);
        String raw = router.completeFast(req, CancelToken.none()).text();
        JNode json = Json.lenientObject(raw);
        if (json == null) {
            log.warn("memory extraction for {} returned no JSON", assistantMessageId);
            return;
        }

        Map<Integer, String> urlByNumber = new java.util.HashMap<>();
        for (SourceRow s : sources) urlByNumber.put(s.n(), s.url());

        List<String> entities = cleanList(json.get("entities"), 12, 80);
        List<String> keywords = cleanList(json.get("keywords"), 12, 40).stream().map(k -> k.toLowerCase(Locale.ROOT)).toList();
        List<NewClaim> claims = new ArrayList<>();
        for (JNode c : json.get("claims").items()) {
            if (claims.size() >= MAX_CLAIMS) break;
            String entity = Text.truncate(Text.collapse(c.get("entity").text()), 80);
            String attribute = Text.truncate(Text.collapse(c.get("attribute").text()), 80);
            String value = Text.truncate(Text.collapse(c.get("value").text()), 200);
            String statement = Text.truncate(Text.collapse(c.get("statement").text()), 400);
            if (entity.isEmpty() || attribute.isEmpty() || value.isEmpty()) continue;
            if (statement.isEmpty()) statement = entity + " " + attribute + ": " + value;
            String url = c.get("source").isNumber() ? urlByNumber.get(c.get("source").asInt(-1)) : null;
            claims.add(new NewClaim(entity, attribute, value, statement, url));
        }

        Set<String> keywordSet = new LinkedHashSet<>(keywords);
        for (String e : entities) keywordSet.add(e.toLowerCase(Locale.ROOT));
        store.setMessageMemory(answer.id(), String.join(" ", keywordSet), entities);
        store.replaceClaims(userId, answer.conversationId(), answer.id(), answer.createdAt(), answer.localDate(), claims);
        log.info("memory: {} claims, {} entities stored for answer {}", claims.size(), entities.size(), answer.id());
    }

    private static List<String> cleanList(JNode node, int max, int maxLen) {
        List<String> out = new ArrayList<>();
        for (JNode item : node.items()) {
            String s = Text.truncate(Text.collapse(item.text()), maxLen);
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
            if (out.size() >= max) break;
        }
        return out;
    }
}
