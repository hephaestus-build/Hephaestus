package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * {@code application-prod.yml} states, and {@code docs/admin/dsms/} publishes as a GDPR posture, that NO layer of the
 * deployment writes a per-request IP/URL record. The server serves the web app too (ADR 0100), so Tomcat is the one
 * layer that sees every request.
 *
 * <p>The URL is the personal datum, not the address: an SPA deep link names a person
 * ({@code /w/<slug>/user/<username>}), so one combined-format line per request is a per-request personal-data record
 * regardless.
 */
class NoPerRequestAccessLogTest extends BaseUnitTest {

    private static final Path PROD_PROFILE = Path.of("src", "main", "resources", "application-prod.yml");

    @Test
    void tomcatAccessLogStaysDisabledInTheProductionProfile() throws Exception {
        assertThat(propertyIn(PROD_PROFILE, "server.tomcat.accesslog.enabled"))
                .as("application-prod.yml claims no layer writes a per-request record; Tomcat is one of them")
                .isEqualTo("false");
    }

    private static @Nullable String propertyIn(Path yaml, String key) throws IOException {
        return new YamlPropertySourceLoader()
                .load(yaml.toString(), new FileSystemResource(yaml)).stream()
                        .map(PropertySource.class::cast)
                        .filter(source -> source.containsProperty(key))
                        .findFirst()
                        .map(source -> String.valueOf(source.getProperty(key)))
                        .orElse(null);
    }
}
