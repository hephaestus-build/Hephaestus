package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.AdmittedDelivery.Automatic;
import de.tum.cit.aet.hephaestus.agent.handler.AdmittedDelivery.Composition;
import de.tum.cit.aet.hephaestus.agent.handler.AdmittedDelivery.Proposed;
import de.tum.cit.aet.hephaestus.agent.handler.AdmittedDelivery.Withheld;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AdmittedDeliveryTest extends BaseUnitTest {

    private static final String SLUG = "describe-what-and-why";
    private static final String LEAD = "You kept this to one concern, but the description never says why it changed.";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Composition composition = new Composition(ArtifactKinds.PULL_REQUEST, Map.of(), List.of(), LEAD);

    private JsonNode coverage(int eligible, int evaluated) {
        ObjectNode output = objectMapper.createObjectNode();
        ObjectNode ledger = output.putObject("practiceCoverage");
        ledger.put("eligible", eligible);
        ledger.put("evaluated", evaluated);
        return output;
    }

    private static DeliveryContent content(@Nullable DeliveryContent content) {
        assertThat(content).isNotNull();
        return content;
    }

    private static ComposedFeedbackUnit onTheWork(ValidatedObservation cited) {
        return new ComposedFeedbackUnit(
                FeedbackChannel.IN_CONTEXT,
                cited.practiceSlug(),
                List.of(String.valueOf(cited.observationId())),
                ComposedFeedbackUnit.Action.NEW,
                null,
                null,
                "The description says why",
                null,
                "Keep writing the reason down.",
                null,
                new ComposedFeedbackUnit.InContextPlacement(
                        ComposedFeedbackUnit.InContextPlacement.PlacementKind.ARTIFACT, null));
    }

    private static ValidatedObservation observation(Outcome outcome, @Nullable Severity severity) {
        return new ValidatedObservation(
                        SLUG,
                        outcome == Outcome.NOT_MET
                                ? "PR description lacks a rationale sentence"
                                : "The description says why it changed",
                        outcome,
                        severity,
                        null,
                        "The body is what the evidence shows.")
                .withKeys(new ObservationKeys("occ-" + UUID.randomUUID(), null, UUID.randomUUID()));
    }

    @Test
    void shouldWithholdTheNoteWhenAPartialReviewFoundNoProblem() {
        ValidatedObservation met = observation(Outcome.MET, null);
        // A strength with a composed unit, so the all-clear would have a note to post.
        Composition withStrength = new Composition(ArtifactKinds.PULL_REQUEST, Map.of(), List.of(onTheWork(met)), LEAD);

        AdmittedDelivery delivery =
                AdmittedDelivery.decide(coverage(4, 2), List.of(met), List.of(), List.of(met), withStrength);

        assertThat(delivery).isInstanceOf(Withheld.class);
        DeliveryContent content = content(((Withheld) delivery).content());
        assertThat(content.mrNote()).isNull();
        assertThat(content.diffNotes()).isEmpty();
    }

    @Test
    void shouldProposeTheNoteWithItsLeadWhenItIsWrittenFromAnObservationAwaitingApproval() {
        ValidatedObservation lapse = observation(Outcome.NOT_MET, Severity.MINOR);

        AdmittedDelivery delivery =
                AdmittedDelivery.decide(coverage(4, 4), List.of(lapse), List.of(lapse), List.of(), composition);

        assertThat(delivery).isInstanceOf(Proposed.class);
        assertThat(((Proposed) delivery).content().mrNote()).startsWith(LEAD);
    }

    @Test
    void shouldPostAutomaticallyWithoutTheLeadWhenNoObservationAwaitsApproval() {
        ValidatedObservation lapse = observation(Outcome.NOT_MET, Severity.MINOR);

        AdmittedDelivery delivery =
                AdmittedDelivery.decide(coverage(4, 4), List.of(lapse), List.of(), List.of(lapse), composition);

        assertThat(delivery).isInstanceOf(Automatic.class);
        Automatic automatic = (Automatic) delivery;
        assertThat(content(automatic.content()).mrNote())
                .startsWith("PR description lacks a rationale sentence")
                .doesNotContain(LEAD);
        assertThat(automatic.contributingPracticeSlugs()).containsExactly(SLUG);
    }

    @Test
    void shouldPostNothingAutomaticallyWhenNoObservationWasAdmitted() {
        ValidatedObservation lapse = observation(Outcome.NOT_MET, Severity.MINOR);

        AdmittedDelivery delivery =
                AdmittedDelivery.decide(coverage(4, 4), List.of(lapse), List.of(), List.of(), composition);

        assertThat(delivery).isEqualTo(new Automatic(null, Set.of()));
    }
}
