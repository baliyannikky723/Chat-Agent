package app.orrery.core.jobs;

import app.orrery.core.json.JNode;
import app.orrery.core.json.Json;
import app.orrery.core.store.Store;
import app.orrery.core.store.Store.JobRow;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable background work, stored in the database rather than held in memory: a restart
 * or crash never loses a pending memory extraction. Failed jobs retry with backoff
 * (5 s, 20 s, 80 s) and give up after four attempts.
 */
public final class JobRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
    private static final int MAX_ATTEMPTS = 4;
    private static final int CONCURRENCY = 2;

    @FunctionalInterface
    public interface Handler {
        void handle(JNode payload) throws Exception;
    }

    private final Store store;
    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "job-runner");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger running = new AtomicInteger();
    private volatile boolean started;

    public JobRunner(Store store) {
        this.store = store;
    }

    public JobRunner register(String kind, Handler handler) {
        handlers.put(kind, handler);
        return this;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        int reset = store.resetRunningJobs();
        if (reset > 0) log.info("re-queued {} jobs interrupted by the last shutdown", reset);
        scheduler.scheduleWithFixedDelay(this::tick, 1, 1500, TimeUnit.MILLISECONDS);
    }

    public void enqueue(String kind, Map<String, Object> payload) {
        store.enqueueJob(kind, Json.write(payload), System.currentTimeMillis());
        if (!started || closed) return; // stored either way: the next start picks it up
        try {
            scheduler.execute(this::tick);
        } catch (RejectedExecutionException e) {
            // shutting down
        }
    }

    /** Waits until nothing is pending or running (used by the demo seeder). */
    public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < until) {
            if (running.get() == 0 && store.pendingJobs() == 0) return true;
            Thread.sleep(250);
        }
        return false;
    }

    private synchronized void tick() {
        if (closed) return;
        try {
            int free = CONCURRENCY - running.get();
            if (free <= 0) return;
            for (JobRow job : store.claimJobs(free, System.currentTimeMillis())) {
                running.incrementAndGet();
                try {
                    workers.execute(() -> run(job));
                } catch (RejectedExecutionException e) {
                    running.decrementAndGet(); // shutting down: the job is re-queued on the next start
                    return;
                }
            }
        } catch (RuntimeException e) {
            if (!closed) log.warn("job polling failed: {}", e.toString());
        }
    }

    private void run(JobRow job) {
        try {
            Handler handler = handlers.get(job.kind());
            if (handler == null) {
                store.failJob(job.id(), "no handler for " + job.kind(), null);
                return;
            }
            handler.handle(Json.tree(job.payloadJson()));
            store.finishJob(job.id());
        } catch (Exception e) {
            boolean last = job.attempts() >= MAX_ATTEMPTS;
            long delay = 5_000L << (2 * Math.max(0, job.attempts() - 1));
            log.warn("job {} ({}) attempt {} failed: {}", job.id(), job.kind(), job.attempts(), e.toString());
            store.failJob(job.id(), e.toString(), last ? null : System.currentTimeMillis() + delay);
        } finally {
            running.decrementAndGet();
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.shutdownNow();
        workers.shutdown();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
