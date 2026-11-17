package app.orrery.core.agent;

import app.orrery.core.llm.LlmException;
import app.orrery.core.llm.LlmModels.LlmRequest;
import app.orrery.core.llm.LlmModels.ToolChoice;
import app.orrery.core.llm.LlmModels.UserMessage;
import app.orrery.core.llm.ModelRouter;
import app.orrery.core.util.CancelToken;
import app.orrery.core.util.Text;
import java.util.List;

/** Short conversation titles, written by the fast model while the answer streams. */
public final class TitleWriter {

    private TitleWriter() {}

    public static String write(ModelRouter router, String firstMessage, CancelToken cancel) {
        String fallback = Text.firstWords(firstMessage, 7);
        try {
            LlmRequest req = new LlmRequest(null,
                    "You write short, specific titles for chat conversations. Reply with the title only.",
                    List.of(new UserMessage("Write a 3 to 6 word title for a conversation that starts with this message. "
                            + "No quotes, no ending punctuation.\n\nMessage: " + Text.truncate(firstMessage, 800))),
                    List.of(), ToolChoice.AUTO, 400, router.reasoningEffort(), false);
            String title = Text.cleanTitle(router.completeFast(req, cancel).text());
            if (title.isBlank() || title.length() < 3 || title.split(" ").length > 12) return fallback;
            return title;
        } catch (LlmException | RuntimeException e) {
            return fallback;
        }
    }
}
