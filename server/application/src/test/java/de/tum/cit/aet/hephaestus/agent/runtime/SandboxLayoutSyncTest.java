package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins the literals that the TypeScript runtime repeats from {@link SandboxLayout}. The staged runners and the image's
 * gateway wrapper cannot import Java constants, so a value changed on one side only would pass every other test and
 * break each review at run time.
 */
class SandboxLayoutSyncTest extends BaseUnitTest {

    @Test
    void shouldStageTheHephHostWithTheWorkspaceLayoutTheServerUses() throws IOException {
        String host = resource("agent/heph-host.ts");

        assertThat(constant(host, "WORKSPACE_ROOT")).isEqualTo("\"" + SandboxLayout.WORKSPACE_ROOT + "\"");
        assertThat(constant(host, "MENTOR_SYSTEM_PROMPT_PATH"))
                .isEqualTo("\"" + SandboxLayout.MENTOR_SYSTEM_PROMPT_PATH + "\"");
        assertThat(constant(host, "PI_AGENT_DIR")).isEqualTo("\"" + SandboxLayout.PI_AGENT_DIR + "\"");
    }

    @Test
    void shouldExitThePracticeRunnerWithTheCodesTheServerReads() throws IOException {
        String runner = resource("agent/pi-runner.ts");

        assertThat(constant(runner, "ENVELOPE_MISMATCH_EXIT"))
                .isEqualTo(Integer.toString(SandboxLayout.EXIT_ENVELOPE_MISMATCH));
        assertThat(constant(runner, "SERVER_UNREACHABLE_EXIT"))
                .isEqualTo(Integer.toString(SandboxLayout.EXIT_SERVER_UNREACHABLE));
        assertThat(constant(runner, "PROVIDER_UNREACHABLE_EXIT"))
                .isEqualTo(Integer.toString(SandboxLayout.EXIT_PROVIDER_UNREACHABLE));
    }

    @Test
    void shouldExitTheGatewayWrapperWithTheCodesAndGraceTheServerReads() throws IOException {
        String gateway = Files.readString(repoFile("docker/agents/pi/gateway-run.ts"), StandardCharsets.UTF_8);

        assertThat(constant(gateway, "EXIT_UPLOAD_FAILED"))
                .isEqualTo(Integer.toString(SandboxLayout.EXIT_UPLOAD_FAILED));
        assertThat(constant(gateway, "EXIT_WORK_TIMEOUT")).isEqualTo(Integer.toString(SandboxLayout.EXIT_WORK_TIMEOUT));
        assertThat(constant(gateway, "UPLOAD_GRACE_MS"))
                .isEqualTo(SandboxLayout.RESULT_UPLOAD_GRACE.toMinutes() + " * 60_000");
    }

    @Test
    void shouldLetEverySessionCiteTheSourcesTheRunnerReadsThroughGit() throws IOException {
        String sources = Files.readString(
                repoFile("docker/agents/practice-scripts/kits/scm/sources.ts"), StandardCharsets.UTF_8);
        String readable = resource("agent/pi-declared-sources.ts");

        assertThat(readable).contains("return new Set([...declared, DIFF_SOURCE, TREE_SOURCE]);");
        assertThat(Set.of(constant(sources, "DIFF_SOURCE"), constant(sources, "TREE_SOURCE")))
                .isEqualTo(SandboxLayout.READ_THROUGH_GIT.stream()
                        .map(kind -> "\"" + kind.value() + "\"")
                        .collect(Collectors.toSet()));
    }

    /** The initializer of a {@code const} declaration, as written. */
    private static String constant(String source, String name) {
        var declaration = Pattern.compile("\\bconst " + name + " = ([^;\\n]+);").matcher(source);
        assertThat(declaration.find()).as("the source declares const %s", name).isTrue();
        return declaration.group(1);
    }

    private static String resource(String name) throws IOException {
        var input = Objects.requireNonNull(
                SandboxLayoutSyncTest.class.getClassLoader().getResourceAsStream(name), "the classpath holds " + name);
        try (input) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Path repoFile(String relativePath) {
        Path candidate = Path.of("..", "..").resolve(relativePath);
        return Files.exists(candidate) ? candidate : Path.of(relativePath);
    }
}
