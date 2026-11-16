package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.candidate.CandidateLimits;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class AgentVocabularySyncTest extends BaseUnitTest {

    private static final Path CONTRACT = resolveRepoFile("docker/agents/practice-scripts/lib/core/contract.ts");

    private static final Path ORCHESTRATOR = resolveResource("agent/pi-orchestrator.md");

    @Test
    void shouldBoundSummariesAlikeInRunnerAndAdmission() throws IOException {
        Matcher matcher =
                Pattern.compile("\\bsummaryChars: (\\d+),").matcher(Files.readString(CONTRACT, StandardCharsets.UTF_8));
        assertThat(matcher.find())
                .as("CANDIDATE_LIMITS.summaryChars is declared in lib/contract.ts")
                .isTrue();
        assertThat(Integer.parseInt(matcher.group(1)))
                .as("CANDIDATE_LIMITS.summaryChars vs CandidateLimits.SUMMARY_CHARS")
                .isEqualTo(CandidateLimits.SUMMARY_CHARS);
    }

    @Test
    void orchestratorPromptCoversEveryOutcome() throws IOException {
        String body = Files.readString(ORCHESTRATOR, StandardCharsets.UTF_8);
        assertThat(body)
                .contains("MET", "NOT_MET", "DOES_NOT_APPLY", "COULD_NOT_DETERMINE")
                .doesNotContain("BEHAVIOR_PRESENT_", "NO_REVIEW_OCCASION", "INSUFFICIENT_EVIDENCE", "INCONCLUSIVE");
    }

    @Test
    void shouldKeepRetiredPracticeWordsOutOfGenerationInstructions() throws IOException {
        // docs/contributor/practice-feedback-language.mdx retires this word for practices and what recurs.
        Pattern retired = Pattern.compile("\\bhabits?\\b", Pattern.CASE_INSENSITIVE);
        List<Path> instructions;
        try (Stream<Path> agent = Files.walk(resolveResource("agent"))) {
            instructions = Stream.concat(
                            agent.filter(Files::isRegularFile),
                            Stream.of(resolveResource("practices/default-catalog.json")))
                    .toList();
        }
        assertThat(instructions)
                .isNotEmpty()
                .allSatisfy(file -> assertThat(retired.matcher(Files.readString(file, StandardCharsets.UTF_8))
                                .find())
                        .as("%s uses a retired practice word", file)
                        .isFalse());
    }

    private static Path resolveRepoFile(String relativePath) {
        Path candidate = Path.of("..", "..").resolve(relativePath);
        return Files.exists(candidate) ? candidate : Path.of(relativePath);
    }

    private static Path resolveResource(String relativePath) {
        Path candidate = Path.of("src/main/resources").resolve(relativePath);
        return Files.exists(candidate)
                ? candidate
                : Path.of("server/application/src/main/resources").resolve(relativePath);
    }
}
