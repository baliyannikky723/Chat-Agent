package app.orrery.web;

import app.orrery.core.AgentRuntime;
import app.orrery.core.config.ProviderDetection;
import app.orrery.core.config.Settings;
import app.orrery.core.store.Store;
import app.orrery.core.time.AgentClock;
import app.orrery.infra.Migrations;
import app.orrery.infra.SqliteStore;
import java.net.http.HttpClient;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only place Spring meets the core. Spring supplies the pooled DataSource; everything
 * else is built with plain constructors inside one bean, so no bean of ours can collide
 * with one Spring Boot auto-configures.
 */
@Configuration
public class AppWiring {

    private static final Logger log = LoggerFactory.getLogger(AppWiring.class);

    @Bean(initMethod = "start", destroyMethod = "close")
    public AgentRuntime agentRuntime(DataSource dataSource) throws SQLException {
        Settings settings = ProviderDetection.fromEnv(EnvHolder.env());
        List<String> applied = Migrations.apply(dataSource);
        if (!applied.isEmpty()) log.info("database migrated: {}", applied);
        Store store = new SqliteStore(dataSource);

        // One shared HTTP/2 client keeps TLS connections to the model and search APIs warm.
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        logSetup(settings);
        return new AgentRuntime(settings, store, http, AgentClock.system());
    }

    private static void logSetup(Settings settings) {
        if (settings.llm() != null) {
            log.info("model: {} {} (background: {}), key {} from {}", settings.llm().provider().id, settings.llm().model(),
                    settings.llm().fastModel(), ProviderDetection.mask(settings.llm().apiKey()), settings.llm().keyEnv());
        } else {
            log.warn("model: NOT CONFIGURED. {}", settings.llmMissing());
        }
        if (!settings.search().isEmpty()) {
            log.info("search: {}", settings.search().stream().map(k -> k.provider().id + " (" + ProviderDetection.mask(k.apiKey()) + ")").toList());
        } else {
            log.warn("search: NOT CONFIGURED. {}", settings.searchMissing());
        }
        if (settings.demoTools()) log.info("demo tools enabled (demo clock and sample history)");
    }
}
