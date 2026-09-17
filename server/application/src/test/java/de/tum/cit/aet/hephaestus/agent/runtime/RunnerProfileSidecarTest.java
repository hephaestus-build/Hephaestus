package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.mentor.MentorRunnerProfile;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeRunnerProfile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A runner script imports its sidecars with relative specifiers, so Node resolves them from the
 * staged directory rather than the classpath: one missing from a profile is not a compile error and
 * not a boot failure, but ERR_MODULE_NOT_FOUND inside the sandbox on every job it runs. A sidecar's
 * own imports resolve the same way, so the walk follows them, descending into a module only the
 * first time {@code reached} admits it, which is what ends the walk on an import cycle.
 */
@Tag("unit")
class RunnerProfileSidecarTest {

    private static final Pattern RELATIVE_IMPORT = Pattern.compile("from \"\\./([^\"]+)\"");

    static Stream<PiRunnerProfile> profiles() {
        return Stream.of(new PracticeRunnerProfile(), new MentorRunnerProfile());
    }

    @ParameterizedTest
    @MethodSource("profiles")
    void shouldStageEverySidecarItsRunnerImports(PiRunnerProfile profile) throws IOException {
        Set<String> reached = new LinkedHashSet<>();
        collectRelativeImports(profile.runnerScript(), reached);
        assertThat(profile.sidecarScripts())
                .as("sidecars staged beside %s", profile.runnerScript())
                .containsAll(reached);
    }

    private static void collectRelativeImports(String script, Set<String> reached) throws IOException {
        try (InputStream in = RunnerProfileSidecarTest.class.getResourceAsStream("/agent/" + script)) {
            assertThat(in).as("%s is on the classpath", script).isNotNull();
            Matcher matcher = RELATIVE_IMPORT.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (reached.add(imported)) {
                    collectRelativeImports(imported, reached);
                }
            }
        }
    }
}
