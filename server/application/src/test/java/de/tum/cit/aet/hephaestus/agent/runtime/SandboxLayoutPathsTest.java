package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.architecture.HephaestusArchitectureTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The job folder mounts its inputs under {@code context/} ({@code docs/contributor/agent/workspace-abi.mdx}),
 * so a hidden {@code .context/} path in the bundled Pi runtime resources would read nothing.
 */
class SandboxLayoutPathsTest extends HephaestusArchitectureTest {

    private static final Pattern HIDDEN_CONTEXT_PREFIX = Pattern.compile("(?<![A-Za-z0-9_/.])\\.context/");

    @Test
    void agentResourcesAreOnContextTarget() throws IOException {
        Path agentResources = resolveDir("src/main/resources/agent", "server/application/src/main/resources/agent");
        assertThat(agentResources).isDirectory();

        try (Stream<Path> stream = Files.walk(agentResources)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String body = Files.readString(p, StandardCharsets.UTF_8);
                    assertThat(HIDDEN_CONTEXT_PREFIX.matcher(body).find())
                            .as("'.context/' path in %s", p)
                            .isFalse();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    /** IDE launches may use the repo root rather than Gradle's module root — try both. */
    private static Path resolveDir(String moduleRelative, String repoRelative) {
        Path candidate = Path.of(moduleRelative);
        return Files.isDirectory(candidate) ? candidate : Path.of(repoRelative);
    }
}
