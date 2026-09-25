package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewRunNarrativeRow;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunNarrativeLookup.ReviewRunNarrative;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What the adapter reads out of a run's own output, at the three points the payload is a claim about
 * someone's work rather than a copy of the row: which lane's next step belongs to an observation, how
 * long a run took, and how much of the catalogue it says it reached.
 */
class ReviewRunNarrativeLookupAdapterTest extends BaseUnitTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long WORKSPACE_ID = 7L;
    private static final Instant STARTED_AT = Instant.parse("2025-03-04T05:06:07Z");

    @Mock
    private AgentJobRepository repository;

    private final FeedbackCompositionResultParser composition = new FeedbackCompositionResultParser();

    @Test
    @DisplayName("takes the next step from the unit written about the work, not from the one about the habit")
    void shouldIgnoreInAppNextStepsWhenAddressingAnObservation() {
        UUID observationId = UUID.randomUUID();
        ReviewRunNarrative narrative = lookUp(output(observationId, """
                {"channel":"IN_CONTEXT","action":"NEW","practiceSlug":"pr-description-quality",
                 "basedOn":["%s"],"title":"No testing notes",
                 "nextStep":"Add a Testing section that names what you ran.",
                 "placement":{"kind":"ARTIFACT"}},
                {"channel":"IN_APP","action":"NEW","practiceSlug":"pr-description-quality",
                 "basedOn":["%s"],"title":"Testing notes keep going missing",
                 "nextStep":"Make a habit of writing down what you ran."}
                """.formatted(observationId, observationId)));

        assertThat(narrative.nextStepFor(observationId)).isEqualTo("Add a Testing section that names what you ran.");
        assertThat(narrative.nextStepByObservationId()).hasSize(1);
    }

    @Test
    @DisplayName("reports no duration for a run whose timestamps cannot both be true")
    void shouldNotReportADurationWhenCompletionPrecedesTheStart() {
        UUID observationId = UUID.randomUUID();
        ReviewRunNarrative narrative = lookUp(output(observationId, ""), STARTED_AT, STARTED_AT.minusSeconds(1));

        assertThat(narrative.durationSeconds()).isNull();
    }

    @Test
    @DisplayName("reads a negative coverage count as no coverage rather than reporting it")
    void shouldReadANegativeCoverageCountAsAbsent() {
        JsonNode output = OBJECT_MAPPER.readTree("""
                {"practiceCoverage":{"eligible":-1,"evaluated":3,"outcomes":[]},
                 "feedback":{"lead":"This change lands the retry.","observations":[],"units":[]}}
                """);

        ReviewRunNarrative narrative = lookUp(output);

        assertThat(narrative.practicesEligible()).isNull();
        assertThat(narrative.practicesEvaluated()).isEqualTo(3);
        assertThat(narrative.lead()).isEqualTo("This change lands the retry.");
    }

    private ReviewRunNarrative lookUp(JsonNode output) {
        return lookUp(output, STARTED_AT, STARTED_AT.plusSeconds(142));
    }

    private ReviewRunNarrative lookUp(JsonNode output, @Nullable Instant startedAt, @Nullable Instant completedAt) {
        UUID jobId = UUID.randomUUID();
        when(repository.findReviewRunNarratives(WORKSPACE_ID, List.of(jobId)))
                .thenReturn(List.of(new Row(jobId, output, startedAt, completedAt)));

        Map<UUID, ReviewRunNarrative> narratives =
                new ReviewRunNarrativeLookupAdapter(repository, composition).findByJobIds(WORKSPACE_ID, List.of(jobId));

        return Objects.requireNonNull(narratives.get(jobId));
    }

    /** One composed output grounded in {@code observationId}, carrying the given units verbatim. */
    private static JsonNode output(UUID observationId, String units) {
        return OBJECT_MAPPER.readTree("""
                {"practiceCoverage":{"eligible":4,"evaluated":3,"outcomes":[]},
                 "feedback":{"lead":"This change lands the retry, and says nothing about how it was tested.",
                  "observations":[{"id":"%s","practiceSlug":"pr-description-quality","anchorable":false,
                   "citations":[]}],
                  "units":[%s]}}
                """.formatted(observationId, units));
    }

    private record Row(
            UUID id,
            @Nullable JsonNode output,
            @Nullable Instant startedAt,
            @Nullable Instant completedAt) implements ReviewRunNarrativeRow {

        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public @Nullable JsonNode getOutput() {
            return output;
        }

        @Override
        public @Nullable Instant getStartedAt() {
            return startedAt;
        }

        @Override
        public @Nullable Instant getCompletedAt() {
            return completedAt;
        }
    }
}
