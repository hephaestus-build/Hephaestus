package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedReview;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PublicReviewCompositionContractTest extends BaseUnitTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final FeedbackCompositionResultParser parser = new FeedbackCompositionResultParser();

    private Observation observation(Outcome outcome, String source) {
        return Observation.builder()
                .id(UUID.randomUUID())
                .outcome(outcome)
                .evidence(mapper.readTree("{ \"citations\": [{ \"sourceKind\": \"%s\" }] }".formatted(source)))
                .build();
    }

    private AgentJob job(String review) {
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setOutput(
                mapper.readTree("{ \"feedback\": { \"contractVersion\": 2, \"review\": %s } }".formatted(review)));
        return job;
    }

    @Test
    void shouldRefuseEmptyPublicCompositionWhenAnEligibleNegativeHasNoDecision() {
        Observation negative = observation(Outcome.NOT_MET, "scm.pull-request.core");
        assertThatThrownBy(() -> PullRequestReviewHandler.reviewToDeliver(
                        parser, job("{}"), List.of(negative), Set.of(FeedbackChannel.IN_CONTEXT)))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessageContaining("undecided");
    }

    @Test
    void shouldRefuseAPartialPublicCompositionThatLeavesAnotherNegativeUndecided() {
        Observation said = observation(Outcome.NOT_MET, "scm.pull-request.core");
        Observation missing = observation(Outcome.NOT_MET, "scm.pull-request.core");
        AgentJob job =
                job("{ \"summary\": { \"body\": \"The description needs its motivation.\", \"basedOn\": [\"%s\"] } }"
                        .formatted(said.getId()));
        ObjectNode output = (ObjectNode) Objects.requireNonNull(job.getOutput());
        ObjectNode feedback = (ObjectNode) output.path("feedback");
        feedback.putArray("observations")
                .addObject()
                .put("id", said.getId().toString())
                .put("practiceSlug", "describe-what-and-why")
                .put("outcome", "NOT_MET")
                .putArray("citations");
        assertThatThrownBy(() -> PullRequestReviewHandler.reviewToDeliver(
                        parser, job, List.of(said, missing), Set.of(FeedbackChannel.IN_CONTEXT)))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessageContaining("undecided");
    }

    @Test
    void shouldKeepAllMetSilenceAndExcludePrivateAndUndecidedObservationsFromCompleteness() {
        List<Observation> observations = List.of(
                observation(Outcome.MET, "scm.pull-request.core"),
                observation(Outcome.NOT_MET, "hephaestus.observation-history"),
                observation(Outcome.UNDETERMINED, "scm.pull-request.core"));
        assertThat(PullRequestReviewHandler.reviewToDeliver(
                        parser, job("{}"), observations, Set.of(FeedbackChannel.IN_CONTEXT)))
                .isEqualTo(ComposedReview.empty());
    }

    @Test
    void shouldSkipPublicCompositionWhenItsChannelWasNotStaged() {
        assertThat(PullRequestReviewHandler.reviewToDeliver(
                        parser,
                        job("null"),
                        List.of(observation(Outcome.NOT_MET, "scm.pull-request.core")),
                        Set.of(FeedbackChannel.IN_APP)))
                .isEqualTo(ComposedReview.empty());
    }
}
