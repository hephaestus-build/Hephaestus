package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Drift guard for the prose contract in {@link MentorContextKeys}: the JS runner's hand-maintained
 * {@code FETCH_CONTEXT_ALLOWED} whitelist (pi-mentor-protocol.ts) must mirror
 * {@link MentorContextKeys#ALLOWED_OUTPUT_KEYS}. Java is authoritative (MentorChatService re-checks),
 * so a divergence only weakens the runner's defense-in-depth — this test makes the mirror enforced.
 */
@Tag("unit")
class MentorContextKeysRunnerMirrorTest {

    private static final Path RUNNER = Path.of("src", "main", "resources", "agent", "pi-mentor-runner.ts");
    private static final Path PROTOCOL = Path.of("src", "main", "resources", "agent", "pi-mentor-protocol.ts");
    private static final Path SYSTEM_PROMPT = Path.of("src", "main", "resources", "agent", "mentor", "system.md");
    private static final Pattern ALLOWED_BLOCK =
            Pattern.compile("const FETCH_CONTEXT_ALLOWED = new Set\\(\\[(.*?)\\]\\);", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]+)\"");

    @Test
    @DisplayName("runner FETCH_CONTEXT_ALLOWED mirrors MentorContextKeys.ALLOWED_OUTPUT_KEYS")
    void runnerWhitelistMirrorsJavaSource() throws IOException {
        String source = Files.readString(PROTOCOL, StandardCharsets.UTF_8);
        Matcher block = ALLOWED_BLOCK.matcher(source);
        assertThat(block.find())
                .as("FETCH_CONTEXT_ALLOWED block present in pi-mentor-protocol.ts")
                .isTrue();

        Set<String> jsKeys = STRING_LITERAL
                .matcher(block.group(1))
                .results()
                .map(m -> m.group(1))
                .collect(Collectors.toSet());

        assertThat(jsKeys)
                .as("runner JS whitelist must equal the Java context output keys")
                .isEqualTo(MentorContextKeys.ALLOWED_OUTPUT_KEYS);
    }

    @Test
    @DisplayName("a fitted context payload is never sliced by the runner")
    void mergeReadinessBudgetIsTheRunnerFetchCap() throws IOException {
        assertThat(Files.readString(RUNNER, StandardCharsets.UTF_8))
                .contains("const FETCH_CONTEXT_MAX_CHARS = 200_000;");
        assertThat(MentorContextKeys.FETCH_CONTEXT_MAX_CHARS).isEqualTo(200_000);
        assertThat(MergeReadinessContentSource.artifactIdOf("inputs/context/merge_readiness/42.json"))
                .contains(42L);
        assertThat(MergeReadinessContentSource.artifactIdOf("inputs/context/merge_readiness/../user.json"))
                .isEmpty();
    }

    @Test
    @DisplayName("an observation detail key is one canonical lowercase id, mirrored by the runner")
    void observationDetailKeyIsCanonical() throws IOException {
        String id = "0b7e1c9a-3f5d-4a8e-9c21-6d4f8e2a1b3c";
        assertThat(ObservationHistoryContentSource.observationIdOf(
                        "inputs/context/observations_history/" + id + ".json"))
                .contains(UUID.fromString(id));
        assertThat(ObservationHistoryContentSource.observationIdOf(
                        "inputs/context/observations_history/" + id.toUpperCase(Locale.ROOT) + ".json"))
                .isEmpty();
        assertThat(ObservationHistoryContentSource.observationIdOf("inputs/context/observations_history/../user.json"))
                .isEmpty();
        assertThat(ObservationHistoryContentSource.observationIdOf("inputs/context/observations_history/42.json"))
                .isEmpty();
        assertThat(Files.readString(PROTOCOL, StandardCharsets.UTF_8))
                .contains(
                        "/^inputs\\/context\\/observations_history\\/[\\da-f]{8}-[\\da-f]{4}-[\\da-f]{4}-[\\da-f]{4}-[\\da-f]{12}\\.json$/u");
    }

    @Test
    @DisplayName("a review-attempts key names one pull request or issue by artifactId, mirrored by the runner")
    void reviewAttemptsKeyIsCanonical() throws IOException {
        assertThat(ReviewAttemptsContentSource.workOf("inputs/context/review_attempts/issue/21.json"))
                .contains(new AgentJobRepository.ScmWork(AgentJobType.ISSUE_REVIEW, 21L));
        assertThat(ReviewAttemptsContentSource.workOf("inputs/context/review_attempts/pull_request/42.json"))
                .contains(new AgentJobRepository.ScmWork(AgentJobType.PULL_REQUEST_REVIEW, 42L));
        assertThat(ReviewAttemptsContentSource.workOf("inputs/context/review_attempts/21.json"))
                .isEmpty();
        assertThat(ReviewAttemptsContentSource.workOf("inputs/context/review_attempts/conversation/21.json"))
                .isEmpty();
        assertThat(ReviewAttemptsContentSource.workOf("inputs/context/review_attempts/issue/../user.json"))
                .isEmpty();
        assertThat(ReviewAttemptsContentSource.workOf("inputs/context/review_attempts/issue/1234567890123456789.json"))
                .isEmpty();
        assertThat(Files.readString(PROTOCOL, StandardCharsets.UTF_8))
                .contains("/^inputs\\/context\\/review_attempts\\/(?:pull_request|issue)\\/\\d{1,18}\\.json$/u");
    }

    @Test
    @DisplayName("system prompt lists every mentor context file path")
    void systemPromptListsContextBasenames() throws IOException {
        String prompt = Files.readString(SYSTEM_PROMPT, StandardCharsets.UTF_8);
        String perTurnInputSection =
                prompt.substring(prompt.indexOf("## Per-turn input"), prompt.indexOf("## When to use tools"));

        assertThat(MentorContextKeys.ALLOWED_OUTPUT_KEYS)
                .allSatisfy(key -> assertThat(perTurnInputSection)
                        .as("system prompt should document context file %s in the per-turn input list", key)
                        .contains("`" + key + "`"));
    }
}
