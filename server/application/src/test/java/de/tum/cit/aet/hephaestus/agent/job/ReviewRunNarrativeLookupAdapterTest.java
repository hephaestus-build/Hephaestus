package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewRunNarrativeRow;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunNarrativeLookup.ReviewRunNarrative;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
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
 * What the adapter reads out of a run's own output: which lane's next step belongs to an observation, and
 * that an id the output relays wrongly names nothing rather than failing the read.
 */
class ReviewRunNarrativeLookupAdapterTest extends BaseUnitTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long WORKSPACE_ID = 7L;

    @Mock
    private AgentJobRepository repository;

    private final FeedbackCompositionResultParser composition = new FeedbackCompositionResultParser();

    @Test
    @DisplayName("takes the next step from the feedback written about the work, not from the one about the habit")
    void shouldIgnoreInAppNextStepsWhenAddressingAnObservation() {
        UUID observationId = UUID.randomUUID();
        ReviewRunNarrative narrative =
                lookUp(output(observationId.toString(), """
                {"channel":"IN_CONTEXT","action":"NEW","practiceSlug":"pr-description-quality",
                 "basedOn":["%s"],"title":"No testing notes",
                 "nextStep":"Add a Testing section that names what you ran.",
                 "placement":{"kind":"ARTIFACT"}},
                {"channel":"IN_APP","action":"NEW","practiceSlug":"pr-description-quality",
                 "basedOn":["%s"],"title":"Testing notes keep going missing",
                 "nextStep":"Make a habit of writing down what you ran."}
                """.formatted(observationId, observationId)));

        assertThat(narrative.nextStepByObservationId())
                .containsExactly(Map.entry(observationId, "Add a Testing section that names what you ran."));
    }

    @Test
    @DisplayName("an observation the output names by something other than an id gets no next step")
    void shouldAddressNoObservationWhenTheOutputNamesOneByANonId() {
        ReviewRunNarrative narrative = lookUp(output("obs-0", """
                {"channel":"IN_CONTEXT","action":"NEW","practiceSlug":"pr-description-quality",
                 "basedOn":["obs-0"],"title":"No testing notes",
                 "nextStep":"Add a Testing section that names what you ran.",
                 "placement":{"kind":"ARTIFACT"}}
                """));

        assertThat(narrative.nextStepByObservationId()).isEmpty();
    }

    private ReviewRunNarrative lookUp(JsonNode output) {
        UUID jobId = UUID.randomUUID();
        when(repository.findReviewRunNarrativesByWorkspaceIdAndIdIn(WORKSPACE_ID, List.of(jobId)))
                .thenReturn(List.of(new Row(jobId, output)));

        Map<UUID, ReviewRunNarrative> narratives =
                new ReviewRunNarrativeLookupAdapter(repository, composition).findByJobIds(WORKSPACE_ID, List.of(jobId));

        return Objects.requireNonNull(narratives.get(jobId));
    }

    /** One composed output grounded in {@code observationId}, carrying the given units verbatim. */
    private static JsonNode output(String observationId, String units) {
        return OBJECT_MAPPER.readTree("""
                {"feedback":{"observations":[{"id":"%s","practiceSlug":"pr-description-quality","anchorable":false,
                   "citations":[]}],
                  "units":[%s]}}
                """.formatted(observationId, units));
    }

    private record Row(UUID id, @Nullable JsonNode output) implements ReviewRunNarrativeRow {

        @Override
        public UUID getId() {
            return id;
        }

        @Override
        public @Nullable JsonNode getOutput() {
            return output;
        }
    }
}
