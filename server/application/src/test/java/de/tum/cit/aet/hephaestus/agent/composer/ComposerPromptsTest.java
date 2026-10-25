package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The worker's composers use the voice, placement and contracts the golden file holds. */
@Tag("unit")
class ComposerPromptsTest {

    @Test
    void shouldComposeWithTheVoicePlacementContractAndToolGuidanceOfTheGolden() throws IOException {
        String shared = WorkerFeedbackComposer.prompt("feedback-style.md");
        String actual = String.join(
                        "\n\n",
                        "# Shared feedback voice",
                        Js.trim(shared),
                        "# Public placement guidance",
                        section(WorkerFeedbackComposer.prompt("review-composer.md"), "Where each point goes"),
                        "# Public writing contract",
                        ReviewOnWork.WRITE_CONTRACT,
                        "# Private wording guidance",
                        section(
                                WorkerFeedbackComposer.prompt("feedback-composer.md"),
                                "Wording rules for these destinations"),
                        "# Public tool description",
                        ReviewOnWork.TOOL_DESCRIPTION)
                + "\n";
        try (InputStream golden = Objects.requireNonNull(
                getClass().getResourceAsStream("/agent/golden-prompts/composition-contracts.md"))) {
            assertThat(actual).isEqualTo(new String(golden.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String section(String text, String heading) {
        int start = text.indexOf("## " + heading + "\n");
        assertThat(start).as(heading).isNotNegative();
        int end = text.indexOf("\n## ", start + 1);
        return Js.trim(end < 0 ? text.substring(start) : text.substring(start, end));
    }
}
