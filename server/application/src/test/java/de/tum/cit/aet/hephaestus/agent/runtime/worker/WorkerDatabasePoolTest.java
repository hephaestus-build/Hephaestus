package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;

/** The database pool each database role binds from the shipped configuration, without the host's own environment. */
class WorkerDatabasePoolTest extends BaseUnitTest {

    @Test
    void shouldKeepApplicationPoolWhenWorkerProfileIsNotActive() throws IOException {
        assertThat(poolSize(null, null)).isEqualTo(20);
    }

    @Test
    void shouldRaisePoolForConcurrentEvidencePreparationWhenWorkerProfileIsActive() throws IOException {
        assertThat(poolSize("worker", null)).isEqualTo(32);
    }

    @Test
    void shouldUseOperatorPoolSizeWhenWorkerReceivesOverride() throws IOException {
        assertThat(poolSize("worker", "48")).isEqualTo(48);
    }

    private static int poolSize(@Nullable String profile, @Nullable String override) throws IOException {
        var loader = new YamlPropertySourceLoader();
        var environment = new MockEnvironment();
        var sources = environment.getPropertySources();
        // The first document of application.yml is the one no profile activates.
        sources.addLast(loader.load("application", new ClassPathResource("application.yml"))
                .getFirst());
        if (profile != null) {
            sources.addAfter(
                    MockPropertySource.MOCK_PROPERTIES_PROPERTY_SOURCE_NAME,
                    loader.load(profile, new ClassPathResource("application-" + profile + ".yml"))
                            .getFirst());
        }
        if (override != null) {
            environment.setProperty("HIKARI_MAXIMUM_POOL_SIZE", override);
        }
        return Binder.get(environment)
                .bind("spring.datasource.hikari", HikariConfig.class)
                .orElseThrow(IllegalStateException::new)
                .getMaximumPoolSize();
    }
}
