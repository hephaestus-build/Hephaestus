package de.tum.cit.aet.hephaestus.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class AgentVocabularySyncTest extends BaseUnitTest {

    private static final Path NORMALIZER = resolveResource("agent/pi-observation-normalize.ts");
    private static final Path RUNNER = resolveResource("agent/pi-runner.ts");
    private static final Path ORCHESTRATOR = resolveResource("agent/pi-orchestrator.md");

    @Test
    void severityVocabularyMatches() throws IOException {
        assertThat(jsArray("SEVERITY_VALUES"))
                .as("SEVERITY_VALUES in pi-observation-normalize.ts vs Severity.values()")
                .containsExactlyInAnyOrderElementsOf(names(Severity.values()));
    }

    @Test
    void shouldUseTheSameOutcomeVocabularyInRuntimeAndAdmission() throws IOException {
        assertThat(jsArray("OUTCOME_VALUES")).containsExactlyInAnyOrderElementsOf(names(Outcome.values()));
        assertThat(Files.readString(RUNNER, StandardCharsets.UTF_8))
                .contains("outcome:", "severity:")
                .doesNotContain("assessmentStatus:", "presence:", "assessment:");
    }

    @Test
    void shouldBoundSummariesAlikeInRunnerAndAdmission() throws IOException {
        Matcher matcher = Pattern.compile("export const MAX_SUMMARY_CHARS = (\\d+);")
                .matcher(Files.readString(NORMALIZER, StandardCharsets.UTF_8));
        assertThat(matcher.find())
                .as("MAX_SUMMARY_CHARS is declared in pi-observation-normalize.ts")
                .isTrue();
        assertThat(Integer.parseInt(matcher.group(1)))
                .as("MAX_SUMMARY_CHARS vs ReviewResultParser.MAX_SUMMARY_LENGTH")
                .isEqualTo(ReviewResultParser.MAX_SUMMARY_LENGTH);
    }

    @Test
    void shouldKeepConversationNoteShapeInSync() throws IOException {
        List<String> javaFields = Arrays.stream(ComposedFeedbackUnit.ConversationBrief.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();
        // A component the record allows to be null is a field the runner may omit, so it belongs in the
        // schema's properties but not in its required list. Keeping it out of both would let the two
        // vocabularies drift apart silently, which is the whole point of this test.
        List<String> requiredFields = Arrays.stream(ComposedFeedbackUnit.ConversationBrief.class.getRecordComponents())
                .filter(component -> component.getAnnotatedType().getAnnotation(Nullable.class) == null)
                .map(component -> component.getName())
                .toList();
        String required = "required: ["
                + requiredFields.stream().map(field -> "\"" + field + "\"").collect(Collectors.joining(", "))
                + "]";

        String runner = Files.readString(RUNNER, StandardCharsets.UTF_8);
        assertThat(runner)
                .as("pi-runner.ts conversation-note schema vs ConversationBrief")
                .contains(required);
        assertThat(javaFields)
                .as("every note field the record carries is declared in the runner schema")
                .allSatisfy(field -> assertThat(runner).contains(field + ": {"));
        assertThat(Files.readString(resolveResource("agent/feedback-composer.md"), StandardCharsets.UTF_8))
                .as("feedback-composer.md conversation-note shape vs ConversationBrief")
                .contains("notes: { " + String.join(", ", javaFields) + " }");
    }

    @Test
    void toolPhasesAreGated() throws IOException {
        String body = Files.readString(RUNNER, StandardCharsets.UTF_8);

        assertThat(body)
                .contains("if (measurementClosed)")
                .contains("measurementClosed = true")
                .contains("if (!compositionAdmitted)")
                .contains("compositionAdmitted = true");
    }

    @Test
    void orchestratorPromptCoversEveryOutcome() throws IOException {
        String body = Files.readString(ORCHESTRATOR, StandardCharsets.UTF_8);
        assertThat(body)
                .contains("MET", "NOT_MET", "NOT_APPLICABLE", "UNDETERMINED")
                .doesNotContain("BEHAVIOR_PRESENT_", "NO_REVIEW_OCCASION", "INSUFFICIENT_EVIDENCE", "INCONCLUSIVE");
    }

    @Test
    void shouldKeepRetiredPracticeWordsOutOfGenerationInstructions() throws IOException {
        // docs/contributor/practice-feedback-language.md retires this word for practices and what recurs.
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

    private static List<String> names(Enum<?>[] values) {
        return Stream.of(values).map(Enum::name).toList();
    }

    private static Set<String> jsArray(String constantName) throws IOException {
        String body = Files.readString(NORMALIZER, StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile(
                        "export const " + Pattern.quote(constantName) + "\\s*=\\s*\\[(.*?)]", Pattern.DOTALL)
                .matcher(body);
        assertThat(matcher.find())
                .as("%s is declared in pi-observation-normalize.ts", constantName)
                .isTrue();
        Set<String> values = quotedStrings(matcher.group(1));
        assertThat(values).as("%s is not empty", constantName).isNotEmpty();
        return values;
    }

    private static Set<String> quotedStrings(String source) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\"([A-Z_]+)\"").matcher(source);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static Path resolveResource(String relativePath) {
        Path candidate = Path.of("src/main/resources").resolve(relativePath);
        return Files.exists(candidate)
                ? candidate
                : Path.of("server/application/src/main/resources").resolve(relativePath);
    }
}
