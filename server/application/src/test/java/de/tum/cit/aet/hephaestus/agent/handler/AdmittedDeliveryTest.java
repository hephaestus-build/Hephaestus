package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.DeliveryContent;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.agent.handler.ReviewResultParser.WithheldObservation;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedbackUnit;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedReview;
import de.tum.cit.aet.hephaestus.practices.PracticeDeliveryBehavior;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Admission of a complete review: each body goes out whole, exactly as written, or not at all. */
class AdmittedDeliveryTest extends BaseUnitTest {

    private static final long AUTHOR = 7L;
    private static final long OTHER_PERSON = 8L;
    private static final String PATH = "src/Auth.java";
    private static final String SUMMARY = "The sign-in path calls `insecure()` before the token is checked.\n\n"
            + "    insecure();\n\n"
            + "Route it through the checked helper so a bad token cannot reach it.";

    private final JsonMapper mapper = new JsonMapper();
    private final Map<UUID, Long> subjects = new HashMap<>();

    private ValidatedObservation observation(String slug, Outcome outcome, String sourceKind, long about) {
        ObjectNode evidence = mapper.createObjectNode();
        ObjectNode citation = evidence.putArray("citations").addObject();
        citation.put("sourceKind", sourceKind);
        citation.put("path", PATH);
        citation.put("side", "NEW");
        citation.put("startLine", 10);
        citation.put("endLine", 10);
        citation.putObject("verification").put("status", "VERIFIED").put("scope", "EXACT_LOCATION");
        evidence.putObject("search").putArray("consulted").add("scm.pull-request.diff");
        UUID id = UUID.randomUUID();
        subjects.put(id, about);
        return new ValidatedObservation(
                        slug,
                        "Summary of " + slug,
                        outcome,
                        outcome == Outcome.NOT_MET ? Severity.MINOR : null,
                        evidence,
                        "What the evidence shows.")
                .withKeys(new ObservationKeys("occ-" + id, null, id));
    }

    private ValidatedObservation problem(String slug) {
        return observation(slug, Outcome.NOT_MET, "scm.pull-request.diff", AUTHOR);
    }

    private static String id(ValidatedObservation observation) {
        return String.valueOf(observation.observationId());
    }

    private static ComposedReview.InlineNote note(
            String body, ValidatedObservation anchored, ValidatedObservation... more) {
        List<String> basedOn = new ArrayList<>(List.of(id(anchored)));
        for (ValidatedObservation also : more) basedOn.add(id(also));
        return new ComposedReview.InlineNote(
                body, basedOn, new ComposedReview.ResolvedAnchor(id(anchored), 0, PATH, "NEW", 10, 10));
    }

    private AdmittedDelivery decide(
            ComposedReview review,
            List<ValidatedObservation> recorded,
            List<ValidatedObservation> awaitingApproval,
            List<ValidatedObservation> automatic) {
        return AdmittedDelivery.decide(
                review, ArtifactKinds.PULL_REQUEST, recorded, subjects, awaitingApproval, automatic);
    }

    private static DeliveryContent automatic(AdmittedDelivery delivery) {
        assertThat(delivery).isInstanceOf(AdmittedDelivery.Automatic.class);
        return Objects.requireNonNull(((AdmittedDelivery.Automatic) delivery).content());
    }

    @Test
    void shouldKeepSummaryOnlySupportOutOfAnInlineBodyButAllowItsSummary() {
        ValidatedObservation original = problem("describe-what-and-why");
        ValidatedObservation summaryOnly = new ValidatedObservation(
                original.practiceSlug(),
                original.summary(),
                original.outcome(),
                original.severity(),
                original.evidence(),
                original.evidenceRationale(),
                original.keys(),
                new PracticeDeliveryBehavior(true, null, null));
        ValidatedObservation lineConcern = problem("checks-tokens-first");
        DeliveryContent content = automatic(decide(
                new ComposedReview(
                        new ComposedReview.Summary(SUMMARY, List.of(id(summaryOnly))),
                        List.of(note("This complete note rests on both observations.", lineConcern, summaryOnly)),
                        List.of()),
                List.of(summaryOnly, lineConcern),
                List.of(),
                List.of(summaryOnly, lineConcern)));
        assertThat(content.mrNote()).isEqualTo(SUMMARY);
        assertThat(content.diffNotes()).isEmpty();
    }

    @Test
    void shouldPostTheCompleteSummaryVerbatimWithExactlyItsContributorsWhenEverySupportIsAutomatic() {
        ValidatedObservation lapse = problem("checks-tokens-first");
        ValidatedObservation unrelated = problem("describe-what-and-why");

        DeliveryContent content = automatic(decide(
                new ComposedReview(new ComposedReview.Summary(SUMMARY, List.of(id(lapse))), List.of(), List.of()),
                List.of(lapse, unrelated),
                List.of(),
                List.of(lapse, unrelated)));

        assertThat(content.mrNote()).isEqualTo(SUMMARY);
        assertThat(content.summaryContributors()).containsExactly(lapse.occurrenceKey());
    }

    @Test
    void shouldRefuseTheWholeSummaryAndKeepAnIndependentNoteWhenOneSupportIsHeld() {
        ValidatedObservation posted = problem("checks-tokens-first");
        ValidatedObservation disputed = problem("describe-what-and-why");

        DeliveryContent content = automatic(decide(
                new ComposedReview(
                        new ComposedReview.Summary(SUMMARY, List.of(id(posted), id(disputed))),
                        List.of(note("Call the checked helper on this line.", posted)),
                        List.of()),
                List.of(posted, disputed),
                List.of(),
                List.of(posted)));

        assertThat(content.mrNote()).isNull();
        assertThat(content.diffNotes()).singleElement().satisfies(diff -> {
            assertThat(diff.body()).isEqualTo("Call the checked helper on this line.");
            assertThat(diff.deliveryKey()).isEqualTo("observation:" + posted.occurrenceKey() + ":0");
            assertThat(diff.contributors()).containsExactly(posted.occurrenceKey());
        });
    }

    @Test
    void shouldPostAStrengthAloneWhenTheReviewDidNotReachEveryPractice() {
        ValidatedObservation strength = observation("ships-a-preview", Outcome.MET, "scm.pull-request.diff", AUTHOR);
        String body = "The preview you added shows the screen in both appearances.";

        DeliveryContent content = automatic(decide(
                new ComposedReview(new ComposedReview.Summary(body, List.of(id(strength))), List.of(), List.of()),
                List.of(strength),
                List.of(),
                List.of(strength)));

        assertThat(content.mrNote()).isEqualTo(body);
    }

    @Test
    void shouldRefuseABodyRestingOnAnUndecidedOrPrivateObservationEvenWhenItPassedTheGates() {
        ValidatedObservation undecided =
                observation("keeps-tests-honest", Outcome.UNDETERMINED, "scm.pull-request.diff", AUTHOR);
        ValidatedObservation fromHistory =
                observation("checks-tokens-first", Outcome.NOT_MET, "hephaestus.feedback-history", AUTHOR);

        AdmittedDelivery delivery = decide(
                new ComposedReview(
                        new ComposedReview.Summary("Earlier reviews said this twice.", List.of(id(fromHistory))),
                        List.of(note("Nothing settled here.", undecided)),
                        List.of()),
                List.of(undecided, fromHistory),
                List.of(),
                List.of(undecided, fromHistory));

        assertThat(delivery).isEqualTo(new AdmittedDelivery.Automatic(null, Set.of()));
    }

    @Test
    void shouldRefuseAStandaloneApprovalLineButKeepTimingInsideASentence() {
        ValidatedObservation lapse = problem("checks-tokens-first");
        String timing = "Before this is ready for another look, route the call through the checked helper.";

        DeliveryContent refused = automatic(decide(
                new ComposedReview(
                        new ComposedReview.Summary(SUMMARY + "\n\nLGTM", List.of(id(lapse))),
                        List.of(note(timing, lapse)),
                        List.of()),
                List.of(lapse),
                List.of(),
                List.of(lapse)));

        assertThat(refused.mrNote()).isNull();
        assertThat(refused.diffNotes())
                .singleElement()
                .extracting(ReviewResultParser.DiffNote::body)
                .isEqualTo(timing);
    }

    @Test
    void shouldRefuseANoteWhoseCitationIsNotAVerifiedLineOfThisChangeOrWhoseWorkHasNoLines() {
        ValidatedObservation lapse = problem("checks-tokens-first");
        // Verified, but not at its exact location: it cannot carry a note onto that line.
        ((ObjectNode) Objects.requireNonNull(lapse.evidence()).get("citations").get(0))
                .putObject("verification")
                .put("status", "VERIFIED");
        ComposedReview review = new ComposedReview(null, List.of(note("Call the checked helper.", lapse)), List.of());

        assertThat(decide(review, List.of(lapse), List.of(), List.of(lapse)))
                .isEqualTo(new AdmittedDelivery.Automatic(null, Set.of()));

        ValidatedObservation onAnIssue = problem("states-acceptance-criteria");
        assertThat(AdmittedDelivery.decide(
                        new ComposedReview(null, List.of(note("Say which criteria.", onAnIssue)), List.of()),
                        ArtifactKinds.ISSUE,
                        List.of(onAnIssue),
                        subjects,
                        List.of(),
                        List.of(onAnIssue)))
                .isEqualTo(new AdmittedDelivery.Automatic(null, Set.of()));
    }

    @Test
    void shouldClearEveryPublicBodyButKeepWithholdingWhenBodiesWouldReachDifferentPeople() {
        ValidatedObservation authors = problem("checks-tokens-first");
        ValidatedObservation reviewers =
                observation("answers-review-threads", Outcome.NOT_MET, "scm.pull-request.diff", OTHER_PERSON);
        ValidatedObservation quiet = problem("describe-what-and-why");

        DeliveryContent content = automatic(decide(
                new ComposedReview(
                        new ComposedReview.Summary(SUMMARY, List.of(id(authors))),
                        List.of(note("Answer the open thread.", reviewers)),
                        List.of(new ComposedReview.Withheld(
                                List.of(id(quiet)), ComposedFeedbackUnit.WithholdReason.ALREADY_SAID))),
                List.of(authors, reviewers, quiet),
                List.of(),
                List.of(authors, reviewers, quiet)));

        assertThat(content.mrNote()).isNull();
        assertThat(content.diffNotes()).isEmpty();
        assertThat(content.withheld())
                .containsExactly(new WithheldObservation(
                        Objects.requireNonNull(quiet.occurrenceKey()), FeedbackSuppressionReason.COMPOSER_WITHHELD));
    }

    @Test
    void shouldRefuseAWholeWithholdingDecisionThatNamesAStrengthOrAPrivateObservation() {
        ValidatedObservation lapse = problem("checks-tokens-first");
        ValidatedObservation strength = observation("ships-a-preview", Outcome.MET, "scm.pull-request.diff", AUTHOR);
        ValidatedObservation fromHistory =
                observation("describe-what-and-why", Outcome.NOT_MET, "hephaestus.feedback-history", AUTHOR);

        AdmittedDelivery delivery = decide(
                new ComposedReview(
                        null,
                        List.of(),
                        List.of(
                                new ComposedReview.Withheld(
                                        List.of(id(lapse), id(strength)),
                                        ComposedFeedbackUnit.WithholdReason.BELOW_BAR),
                                new ComposedReview.Withheld(
                                        List.of(id(lapse), id(fromHistory)),
                                        ComposedFeedbackUnit.WithholdReason.BELOW_BAR))),
                List.of(lapse, strength, fromHistory),
                List.of(),
                List.of(lapse, strength, fromHistory));

        assertThat(delivery).isEqualTo(new AdmittedDelivery.Automatic(null, Set.of()));
    }

    @Test
    void shouldProposeTheWholeReviewWhenAnyAdmittedBodyRestsOnAnObservationAwaitingApproval() {
        ValidatedObservation automaticLapse = problem("checks-tokens-first");
        ValidatedObservation gated = problem("describe-what-and-why");

        AdmittedDelivery delivery = decide(
                new ComposedReview(
                        new ComposedReview.Summary(SUMMARY, List.of(id(gated))),
                        List.of(note("Call the checked helper.", automaticLapse)),
                        List.of()),
                List.of(automaticLapse, gated),
                List.of(gated),
                List.of(automaticLapse));

        assertThat(delivery).isInstanceOf(AdmittedDelivery.Proposed.class);
        DeliveryContent proposed = ((AdmittedDelivery.Proposed) delivery).content();
        assertThat(proposed.mrNote()).isEqualTo(SUMMARY);
        assertThat(proposed.diffNotes()).hasSize(1);
    }

    @Test
    void shouldNotBuildABodyWhoseSupportIsEmptyOrWhoseAnchorItDoesNotRestOn() {
        ValidatedObservation lapse = problem("checks-tokens-first");
        ValidatedObservation other = problem("describe-what-and-why");
        ComposedReview.ResolvedAnchor anchor = new ComposedReview.ResolvedAnchor(id(lapse), 0, PATH, "NEW", 10, 10);

        assertThatThrownBy(() -> new ComposedReview.Summary(SUMMARY, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComposedReview.InlineNote("A note.", List.of(id(other)), anchor))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ComposedReview.Withheld(List.of(), ComposedFeedbackUnit.WithholdReason.BELOW_BAR))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
