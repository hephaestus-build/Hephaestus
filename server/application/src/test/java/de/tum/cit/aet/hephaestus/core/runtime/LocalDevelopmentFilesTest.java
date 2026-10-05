package de.tum.cit.aet.hephaestus.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Reads {@code application.yml} itself: the local files it names resolve from the working directory, which
 * {@code bootRun} and the tests share as the application module. A path written for another directory reads
 * nothing, and the server still starts.
 */
class LocalDevelopmentFilesTest extends BaseUnitTest {

    @Test
    void readsTheDotenvBesideTheComposeFileTheLocalProfileStarts() throws IOException {
        Path dotenv = Path.of(valueOf("spring.config.import")
                        .replaceFirst("^optional:file:", "")
                        .replaceFirst("\\[.*]$", ""))
                .toAbsolutePath()
                .normalize();
        Path compose =
                Path.of(valueOf("spring.docker.compose.file")).toAbsolutePath().normalize();

        assertThat(dotenv).isEqualTo(compose.resolveSibling(".env"));
        assertThat(dotenv.resolveSibling(".env.example")).isRegularFile();
    }

    /** The first document of {@code application.yml} that sets {@code key}. */
    private static String valueOf(String key) throws IOException {
        List<PropertySource<?>> documents =
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        return documents.stream()
                .map(document -> document.getProperty(key))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .findFirst()
                .orElseThrow(() -> new AssertionError(key + " is not set in application.yml"));
    }
}
