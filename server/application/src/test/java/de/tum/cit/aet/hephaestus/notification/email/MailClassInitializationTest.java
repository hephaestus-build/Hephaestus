package de.tum.cit.aet.hephaestus.notification.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The first deliveries on virtual threads park on no class initialization once the adapter has taken their path on its
 * own thread. Each run is a JVM of its own, so its deliveries really are the first to use Jakarta Mail.
 */
@Tag("unit")
class MailClassInitializationTest {

    @Test
    void firstDeliveriesOnVirtualThreadsParkUnpinnedAfterTheAdapterInitializedTheMailClasses() throws Exception {
        assertThat(probe("initialized")).contains("pinned parks: 0");
    }

    @Test
    void firstDeliveriesOnVirtualThreadsWaitPinnedForEachOtherWithoutIt() throws Exception {
        // With one carrier the initializing thread finishes before another runs, so the race needs two.
        assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
        // The race that the constructor prevents: the probe sees it, so its zero above means something.
        String output = probe("racing");
        assertThat(output).doesNotContain("pinned parks: 0").contains("EmailAdapter.initializeMailClasses");
    }

    private static String probe(String mode) throws IOException, InterruptedException {
        File output = File.createTempFile("mail-class-initialization", ".log");
        try {
            String java = ProcessHandle.current().info().command().orElse("java");
            Process process = new ProcessBuilder(List.of(
                            java,
                            "-cp",
                            System.getProperty("java.class.path"),
                            MailClassInitializationProbe.class.getName(),
                            mode))
                    .redirectErrorStream(true)
                    .redirectOutput(output)
                    .start();
            boolean exited = process.waitFor(60, TimeUnit.SECONDS);
            if (!exited) process.destroyForcibly();
            String printed = Files.readString(output.toPath(), StandardCharsets.UTF_8);
            assertThat(exited).as(printed).isTrue();
            assertThat(process.exitValue()).as(printed).isZero();
            return printed;
        } finally {
            Files.deleteIfExists(output.toPath());
        }
    }
}
