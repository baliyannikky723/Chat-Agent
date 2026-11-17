package app.orrery.core.agent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One turn at a time per conversation, so two tabs can never interleave answers. */
public final class ConversationLocks {

    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    public boolean tryLock(String conversationId) {
        return busy.add(conversationId);
    }

    public void unlock(String conversationId) {
        busy.remove(conversationId);
    }
}
