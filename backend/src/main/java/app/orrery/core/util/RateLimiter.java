package app.orrery.core.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token bucket per key. Protects the API keys behind the agent from runaway clients:
 * a burst of {@code capacity} requests, then one every {@code refillMillis}.
 */
public final class RateLimiter {

    private static final class Bucket {
        double tokens;
        long updatedAt;

        Bucket(double tokens, long updatedAt) {
            this.tokens = tokens;
            this.updatedAt = updatedAt;
        }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final long refillMillis;
    private final LongSupplier clock;

    public RateLimiter(int capacity, long refillMillis) {
        this(capacity, refillMillis, System::currentTimeMillis);
    }

    public RateLimiter(int capacity, long refillMillis, LongSupplier clock) {
        this.capacity = capacity;
        this.refillMillis = refillMillis;
        this.clock = clock;
    }

    /** Takes one token. Returns 0 if allowed, otherwise milliseconds until the next token. */
    public long tryAcquire(String key) {
        long now = clock.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));
        synchronized (bucket) {
            double refill = (now - bucket.updatedAt) / (double) refillMillis;
            bucket.tokens = Math.min(capacity, bucket.tokens + refill);
            bucket.updatedAt = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - bucket.tokens) * refillMillis);
        }
    }
}
