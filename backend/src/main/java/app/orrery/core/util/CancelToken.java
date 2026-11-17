package app.orrery.core.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cooperative cancellation for one turn. When the person presses Stop (or the browser
 * disconnects) the web layer cancels the token; open HTTP streams to the model and to
 * search providers are closed through the registered callbacks.
 */
public final class CancelToken {

    private volatile boolean cancelled;
    private volatile String reason;
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public static CancelToken none() {
        return new CancelToken();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public String reason() {
        return reason;
    }

    public void cancel(String why) {
        if (cancelled) return;
        reason = why;
        cancelled = true;
        for (Runnable r : callbacks) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // a failing close must not stop the others
            }
        }
    }

    /** Runs {@code r} on cancel (immediately if already cancelled). Returns a deregistration handle. */
    public Runnable onCancel(Runnable r) {
        callbacks.add(r);
        if (cancelled) r.run();
        return () -> callbacks.remove(r);
    }

    public void throwIfCancelled() {
        if (cancelled) throw new CancelledException(reason);
    }

    public static final class CancelledException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public CancelledException(String reason) {
            super(reason == null ? "cancelled" : reason);
        }
    }
}
