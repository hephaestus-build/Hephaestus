package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SandboxInitializationExceptionTest extends BaseUnitTest {

    @Test
    void shouldKeepOnlyTheLastLinesOfTheTranscript() {
        String transcript = IntStream.rangeClosed(1, 30)
                .mapToObj(line -> "line " + line)
                .collect(Collectors.joining("\n", "", "\n"));

        var failure = new SandboxInitializationException(2, false, transcript);

        assertThat(failure.transcriptTail().lines())
                .hasSize(SandboxInitializationException.TAIL_LINES)
                .startsWith("line 11")
                .endsWith("line 30");
    }

    @Test
    void shouldBoundTheTailInCharactersAndKeepItsEnd() {
        String transcript = "x".repeat(5000) + "\nthe last line";

        var failure = new SandboxInitializationException(2, false, transcript);

        assertThat(failure.transcriptTail())
                .hasSize(SandboxInitializationException.TAIL_CHARACTERS)
                .endsWith("the last line");
    }

    @Test
    void shouldMaskCredentialsInTheTailAndKeepTheTranscriptOutOfTheMessage() {
        String transcript =
                "GET https://user:hunter2@gateway/workspace\nAuthorization: Bearer abc.def\nghp_0123456789abcdef\n";

        var failure = new SandboxInitializationException(2, false, transcript);

        assertThat(failure.transcriptTail())
                .doesNotContain("hunter2", "abc.def", "0123456789abcdef")
                .contains("***");
        assertThat(failure).hasMessage("Sandbox workspace initialization failed: exit 2");
    }

    @Test
    void shouldNameATimeoutAndAnUnknownExitWithoutATranscript() {
        assertThat(new SandboxInitializationException(null, true, null))
                .hasMessage("Sandbox workspace initialization did not end by the work deadline")
                .satisfies(failure -> assertThat(failure.transcriptTail()).isEmpty());
        assertThat(new SandboxInitializationException(null, false, ""))
                .hasMessage("Sandbox workspace initialization failed: exit unknown");
    }
}
