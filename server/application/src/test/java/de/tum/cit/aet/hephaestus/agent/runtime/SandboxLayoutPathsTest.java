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
 * Guardrail for the workspace ABI context prefix. Scans the bundled Pi runtime resources
 * (orchestrator + runner) for any legacy {@code .context/} reference that would violate the
 * rolling-deploy contract documented in {@code docs/developer/agent/workspace-abi.mdx}; the
 * canonical prefix is {@code inputs/context/}.
 */
class SandboxLayoutPathsTest extends HephaestusArchitectureTest {

    /** Matches references to the legacy {@code .context/} prefix that are NOT {@code inputs/context/}. */
    private static final Pattern LEGACY_CONTEXT_PREFIX = Pattern.compile("(?<![A-Za-z0-9_/.])\\.context/");

    @Test
    void agentResourcesAreOnContextTarget() throws IOException {
        Path agentResources = resolveDir("src/main/resources/agent", "server/application/src/main/resources/agent");
        assertThat(agentResources).isDirectory();

        try (Stream<Path> stream = Files.walk(agentResources)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                try {
                    String body = Files.readString(p, StandardCharsets.UTF_8);
                    assertThat(LEGACY_CONTEXT_PREFIX.matcher(body).find())
                            .as("Legacy '.context/' prefix found in %s", p)
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
