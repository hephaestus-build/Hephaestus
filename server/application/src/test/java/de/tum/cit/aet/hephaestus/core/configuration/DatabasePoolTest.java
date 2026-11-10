package de.tum.cit.aet.hephaestus.core.configuration;

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

/** The database pool the server binds from the shipped configuration, without the host's own environment. */
class DatabasePoolTest extends BaseUnitTest {

    @Test
    void shouldKeepTheShippedPoolWithoutAnOverride() throws IOException {
        assertThat(poolSize(null)).isEqualTo(20);
    }

    @Test
    void shouldUseTheOperatorPoolSizeWhenGivenAnOverride() throws IOException {
        assertThat(poolSize("48")).isEqualTo(48);
    }

    private static int poolSize(@Nullable String override) throws IOException {
        var environment = new MockEnvironment();
        // The first document of application.yml is the one no profile activates.
        environment
                .getPropertySources()
                .addLast(new YamlPropertySourceLoader()
                        .load("application", new ClassPathResource("application.yml"))
                        .getFirst());
        if (override != null) {
            environment.setProperty("HIKARI_MAXIMUM_POOL_SIZE", override);
        }
        return Binder.get(environment)
                .bind("spring.datasource.hikari", HikariConfig.class)
                .orElseThrow(IllegalStateException::new)
                .getMaximumPoolSize();
    }
}
