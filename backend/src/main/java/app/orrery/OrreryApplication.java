package app.orrery;

import app.orrery.core.config.DotEnv;
import app.orrery.web.EnvHolder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. Reads .env (real environment variables win), prepares the data folder,
 * then starts Spring Boot. The agent core under {@code app.orrery.core} has no Spring
 * dependency; {@code app.orrery.web} and {@code app.orrery.infra} are the adapters.
 */
@SpringBootApplication
public class OrreryApplication {

    public static void main(String[] args) throws IOException {
        Map<String, String> env = DotEnv.load(locateEnvFile(), System.getenv());
        EnvHolder.set(env);

        String dir = env.getOrDefault("ORRERY_DATA_DIR", "");
        Path dataDir = (dir.isBlank() ? Path.of("data") : Path.of(dir)).toAbsolutePath().normalize();
        Files.createDirectories(dataDir);
        System.setProperty("orrery.db-path", dataDir.resolve("orrery.db").toString().replace('\\', '/'));
        EnvHolder.put("ORRERY_DATA_DIR", dataDir.toString());

        String port = env.getOrDefault("BACKEND_PORT", "");
        if (port.matches("\\d{2,5}")) System.setProperty("server.port", port);
        String host = env.getOrDefault("BACKEND_HOST", "");
        if (host.matches("[0-9a-fA-F.:]+|localhost")) System.setProperty("server.address", host);

        SpringApplication.run(OrreryApplication.class, args);
    }

    /** ORRERY_ENV_FILE (set by the npm launcher), else ./.env, else ../.env (when run from backend/). */
    static Path locateEnvFile() {
        String explicit = System.getenv("ORRERY_ENV_FILE");
        if (explicit != null && !explicit.isBlank()) return Path.of(explicit);
        Path here = Path.of(".env");
        if (Files.isRegularFile(here)) return here;
        Path parent = Path.of("..", ".env");
        return Files.isRegularFile(parent) ? parent : here;
    }
}
