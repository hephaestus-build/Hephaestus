package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guards against test-context fragmentation: a per-class {@code @EventListener} for SCM/GitHub-project
 * domain events forks a fresh (Testcontainers-backed) Spring context per class. Record via the shared
 * {@link de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener} (imported by
 * {@code BaseIntegrationTest}) instead, so those tests reuse one context. Source scan; the recorder is
 * the only allowed declaration site.
 */
@Tag("architecture")
class SharedEventRecorderConventionTest {

    private static final String ALLOWED = "RecordingScmEventListener.java";

    /** {@code @EventListener} on a method whose parameter is an SCM / GitHub-project domain event. */
    private static final Pattern PER_CLASS_LISTENER =
            Pattern.compile("@EventListener\\b[\\s\\S]{0,200}?\\((?:final\\s+)?ScmDomainEvent\\.", Pattern.DOTALL);

    @Test
    void scmDomainEventsAreRecordedViaTheSharedRecorderNotPerClassListeners() {
        Path testRoot = locateTestRoot();
        Set<String> violations = new TreeSet<>();

        try (Stream<Path> sources = Files.walk(testRoot)) {
            sources.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals(ALLOWED))
                    .forEach(p -> {
                        String content;
                        try {
                            content = Files.readString(p);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        if (PER_CLASS_LISTENER.matcher(content).find()) {
                            violations.add("  " + p.getFileName());
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(violations)
                .as("These tests declare a per-class @EventListener for SCM / GitHub-project domain events, "
                        + "which forks a fresh Spring context (a Testcontainers boot) per class. Record via the "
                        + "shared RecordingScmEventListener (imported by BaseIntegrationTest) instead — "
                        + "recorder.ofType(SomeEvent.class):\n"
                        + String.join("\n", violations))
                .isEmpty();
    }

    private static Path locateTestRoot() {
        Path serverDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path testRoot = serverDir.resolve("src/test/java/de/tum/cit/aet/hephaestus");
        if (!Files.isDirectory(testRoot)) {
            testRoot = serverDir.resolve("server/application/src/test/java/de/tum/cit/aet/hephaestus");
        }
        if (!Files.isDirectory(testRoot)) {
            throw new IllegalStateException("Could not locate test source root from user.dir=" + serverDir);
        }
        return testRoot;
    }
}
