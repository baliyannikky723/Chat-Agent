package app.orrery.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The merged environment (.env + real env vars) computed once in {@code main}. */
public final class EnvHolder {

    private static final Map<String, String> ENV = new ConcurrentHashMap<>();

    private EnvHolder() {}

    public static void set(Map<String, String> env) {
        ENV.clear();
        env.forEach((k, v) -> {
            if (k != null && v != null) ENV.put(k, v);
        });
    }

    public static void put(String key, String value) {
        ENV.put(key, value);
    }

    public static Map<String, String> env() {
        if (ENV.isEmpty()) set(System.getenv());
        return ENV;
    }
}
