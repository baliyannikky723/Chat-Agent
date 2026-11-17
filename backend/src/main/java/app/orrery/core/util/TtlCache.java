package app.orrery.core.util;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Bounded cache with per-entry expiry. When full, the entry closest to expiry is evicted.
 * Small and lock-free for reads; good for search results and fetched pages.
 */
public final class TtlCache<K, V> {

    private record Entry<V>(V value, long expiresAt) {}

    private final Map<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final LongSupplier clock;

    public TtlCache(int maxEntries) {
        this(maxEntries, System::currentTimeMillis);
    }

    public TtlCache(int maxEntries, LongSupplier clock) {
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    public V get(K key) {
        Entry<V> e = map.get(key);
        if (e == null) return null;
        if (e.expiresAt() <= clock.getAsLong()) {
            map.remove(key, e);
            return null;
        }
        return e.value();
    }

    public void put(K key, V value, long ttlMillis) {
        if (value == null || ttlMillis <= 0) return;
        if (map.size() >= maxEntries) evict();
        map.put(key, new Entry<>(value, clock.getAsLong() + ttlMillis));
    }

    public int size() {
        return map.size();
    }

    public void clear() {
        map.clear();
    }

    private void evict() {
        long now = clock.getAsLong();
        K soonest = null;
        long soonestAt = Long.MAX_VALUE;
        for (Iterator<Map.Entry<K, Entry<V>>> it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<K, Entry<V>> e = it.next();
            if (e.getValue().expiresAt() <= now) {
                it.remove();
            } else if (e.getValue().expiresAt() < soonestAt) {
                soonestAt = e.getValue().expiresAt();
                soonest = e.getKey();
            }
        }
        if (map.size() >= maxEntries && soonest != null) map.remove(soonest);
    }
}
