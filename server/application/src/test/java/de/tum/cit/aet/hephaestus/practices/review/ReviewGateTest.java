package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCaptureFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.IssueItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.PullRequestItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.PullRequestReviewItemDescriptor;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.ReviewLimit;
import de.tum.cit.aet.hephaestus.practices.ReviewOn;
import de.tum.cit.aet.hephaestus.practices.ReviewedBy;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeDeliveryStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class ReviewGateTest {
    private final PracticeRepository practices = mock(PracticeRepository.class);
    // The registered descriptors: selection reads the same offered events and roles as authoring and capture.
    private final PracticeItemOptions items = new PracticeItemOptions(new ItemDescriptorRegistry(List.of(
            new PullRequestItemDescriptor(), new PullRequestReviewItemDescriptor(), new IssueItemDescriptor())));
    private final ReviewGate gate = new ReviewGate(practices, items, new JsonMapper());
    private final Workspace workspace = workspace();

    @ParameterizedTest
    @EnumSource(ReviewOrigin.class)
    void shouldRefuseOnlyTheDisabledWayToStartAReview(ReviewOrigin origin) {
        when(practices.findByWorkspaceIdAndKind(1L, new ItemKind("scm.pull_request")))
                .thenReturn(List.of(practice(1, DefinitionTestFixtures.definition())));
        workspace.getFeatures().setPracticeReviewAutoTriggerEnabled(origin != ReviewOrigin.EVENT);
        workspace.getFeatures().setPracticeReviewManualTriggerEnabled(origin == ReviewOrigin.EVENT);
        var event = new ItemEventName("scm.pull_request.opened");
        assertThat(gate.evaluate(workspace, snapshot(Map.of()), origin, event, List.of()))
                .isInstanceOfSatisfying(
                        GateDecision.Skip.class,
                        skip -> assertThat(skip.eventReason()).isEqualTo(ReviewRequestReason.GATE_WITHHELD));
        workspace.getFeatures().setPracticeReviewAutoTriggerEnabled(origin == ReviewOrigin.EVENT);
        workspace.getFeatures().setPracticeReviewManualTriggerEnabled(origin != ReviewOrigin.EVENT);
        assertThat(gate.evaluate(workspace, snapshot(Map.of()), origin, event, List.of()))
                .isInstanceOf(GateDecision.Run.class);
    }

    @Test
    void feedbackModeOffStillAdmitsTheReview() {
        var p = practice(1, DefinitionTestFixtures.definition());
        p.setFeedbackMode(FeedbackMode.OFF);
        when(practices.findByWorkspaceIdAndKind(1L, new ItemKind("scm.pull_request")))
                .thenReturn(List.of(p));
        assertThat(gate.evaluate(
                        workspace,
                        snapshot(Map.of()),
                        ReviewOrigin.EVENT,
                        new ItemEventName("scm.pull_request.opened"),
                        List.of()))
                .isInstanceOf(GateDecision.Run.class);
    }

    @Test
    void peopleAndNobodyAreNotExecutedByHephaestus() {
        for (var by : List.of(ReviewedByKind.PEOPLE, ReviewedByKind.NOBODY)) {
            var original = DefinitionTestFixtures.definition();
            var definition = new PracticeDefinition(
                    original.name(),
                    original.reviewOn(),
                    null,
                    original.about(),
                    original.reads(),
                    new ReviewedBy(
                            by,
                            by == ReviewedByKind.PEOPLE
                                    ? new ReviewLimit("MANUAL_REVIEW", "People review this practice.")
                                    : null,
                            List.of()),
                    original.criteria(),
                    null,
                    null,
                    true,
                    original.guidance());
            when(practices.findByWorkspaceIdAndKind(1L, new ItemKind("scm.pull_request")))
                    .thenReturn(List.of(practice(1, definition)));
            assertThat(gate.evaluate(
                            workspace,
                            snapshot(Map.of()),
                            ReviewOrigin.EVENT,
                            new ItemEventName("scm.pull_request.opened"),
                            List.of()))
                    .isInstanceOf(GateDecision.Skip.class);
        }
    }

    @ParameterizedTest
    @EnumSource(ReviewOrigin.class)
    void shouldSelectNoBundledPracticeThatPeopleReview(ReviewOrigin origin) {
        workspace.getFeatures().setPracticeReviewAutoTriggerEnabled(true);
        workspace.getFeatures().setPracticeReviewManualTriggerEnabled(true);
        var peoplePractices = ScmCaptureFixtures.bundledPractices().entrySet().stream()
                .filter(entry -> entry.getValue().definition().reviewedBy().by() == ReviewedByKind.PEOPLE)
                .toList();

        assertThat(peoplePractices)
                .extracting(Map.Entry::getKey)
                .containsExactlyInAnyOrder(
                        "triages-the-issue-with-labels-and-ownership", "issue-closed-with-unmet-outcome");
        long id = 0;
        for (var entry : peoplePractices) {
            var definition = entry.getValue().definition();
            assertThat(definition.reviewedBy().humanReason()).as(entry.getKey()).isNotNull();
            assertThat(definition.script()).as(entry.getKey()).isNull();
            var kind = entry.getValue().kind();
            var practice = ReviewTestData.practice(1, ++id, entry.getKey(), definition);
            practice.setKind(kind);
            when(practices.findByWorkspaceIdAndKind(1L, kind)).thenReturn(List.of(practice));
            // The practice's own event and an unfiltered state: only who reviews it keeps it from running.
            assertThat(definition.reviewOn().state()).as(entry.getKey()).isEmpty();
            var event = definition.reviewOn().events().getFirst();
            assertThat(gate.evaluate(workspace, snapshot(kind, Map.of()), origin, event, List.of()))
                    .as(entry.getKey())
                    .isInstanceOfSatisfying(
                            GateDecision.Skip.class,
                            skip -> assertThat(skip.eventReason()).isEqualTo(ReviewRequestReason.NO_ACTIVE_PRACTICE));
        }
    }

    @Test
    void automaticStateFiltersDoNotExcludeARequestedReview() {
        var original = DefinitionTestFixtures.definition();
        var d = new PracticeDefinition(
                original.name(),
                new ReviewOn(original.reviewOn().events(), Map.of("state", Set.of("open"))),
                null,
                original.about(),
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                null,
                null,
                true,
                original.guidance());
        when(practices.findByWorkspaceIdAndKind(1L, new ItemKind("scm.pull_request")))
                .thenReturn(List.of(practice(1, d)));
        assertThat(gate.evaluate(
                        workspace,
                        snapshot(Map.of("state", "closed")),
                        ReviewOrigin.EVENT,
                        new ItemEventName("scm.pull_request.opened"),
                        List.of()))
                .isInstanceOf(GateDecision.Skip.class);
        assertThat(gate.evaluate(workspace, snapshot(Map.of("state", "closed")), ReviewOrigin.REQUEST, null, List.of()))
                .isInstanceOf(GateDecision.Run.class);
    }

    @Test
    void feedbackPauseDoesNotStopResultCapture() {
        workspace.getReviewSettings().setDeliveryStatus(PracticeDeliveryStatus.PAUSED);
        when(practices.findByWorkspaceIdAndKind(1L, new ItemKind("scm.pull_request")))
                .thenReturn(List.of(practice(1, DefinitionTestFixtures.definition())));
        assertThat(gate.evaluate(workspace, snapshot(Map.of()), ReviewOrigin.REQUEST, null, List.of()))
                .isInstanceOf(GateDecision.Run.class);
    }

    @Test
    void selectsOnlyRequestedPractices() {
        var first = practice(1, DefinitionTestFixtures.definition());
        var second = practice(2, DefinitionTestFixtures.definition());
        when(practices.findByWorkspaceIdAndKind(1L, new ItemKind("scm.pull_request")))
                .thenReturn(List.of(first, second));
        var result = gate.evaluate(workspace, snapshot(Map.of()), ReviewOrigin.REQUEST, null, List.of(2L));
        assertThat(result)
                .isInstanceOfSatisfying(
                        GateDecision.Run.class,
                        r -> assertThat(r.matchedPractices()).containsExactly(second));
    }

    @Test
    void shouldSelectPracticesBeforeRecipientSpecificModelBinding() {
        var practice = practice(1, DefinitionTestFixtures.definition());
        when(practices.findByWorkspaceIdAndKind(1L, new ItemKind("scm.pull_request")))
                .thenReturn(List.of(practice));
        assertThat(gate.evaluate(workspace, snapshot(Map.of()), ReviewOrigin.REQUEST, null, List.of()))
                .isInstanceOf(GateDecision.Run.class);
    }

    @Test
    void rejectsForeignWorkspaceItems() {
        workspace.setId(2L);
        assertThatThrownBy(() -> gate.evaluate(workspace, snapshot(Map.of()), ReviewOrigin.REQUEST, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(ReviewOrigin.class)
    void shouldNotSelectAPracticeWhoseEventsTheItemKindDoesNotOffer(ReviewOrigin origin) {
        // A migrated pull request practice bound only to an event of a submitted review: no review could run it.
        var unrunnable = practice(
                1,
                DefinitionTestFixtures.withReviewOn(
                        DefinitionTestFixtures.definition(),
                        new ReviewOn(List.of(new ItemEventName("scm.pull_request_review.submitted")), Map.of())));
        var runnable = practice(2, DefinitionTestFixtures.definition());
        when(practices.findByWorkspaceIdAndKind(1L, new ItemKind("scm.pull_request")))
                .thenReturn(List.of(unrunnable, runnable));

        assertThat(gate.evaluate(
                        workspace, snapshot(Map.of()), origin, new ItemEventName("scm.pull_request.opened"), List.of()))
                .isInstanceOfSatisfying(
                        GateDecision.Run.class,
                        run -> assertThat(run.matchedPractices()).containsExactly(runnable));
    }

    private static Practice practice(long id, PracticeDefinition d) {
        return ReviewTestData.practice(1, id, "practice-" + id, d);
    }

    private static Workspace workspace() {
        var ws = new Workspace();
        ws.setId(1L);
        ws.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        ws.getFeatures().setPracticesEnabled(true);
        return ws;
    }

    private static ItemSnapshot snapshot(Map<String, String> state) {
        return snapshot(new ItemKind("scm.pull_request"), state);
    }

    private static ItemSnapshot snapshot(ItemKind kind, Map<String, String> state) {
        return ItemFixtures.snapshot(new ItemRef(1, 1, kind, 1L, null, null), state)
                .integration(new IntegrationRef(IntegrationKind.GITHUB, 1, "test", null))
                .label(ItemFixtures.label()
                        .itemId(1)
                        .title("Review")
                        .noun("Pull request")
                        .number(1)
                        .container("project")
                        .integration(IntegrationKind.GITHUB)
                        .build())
                .build();
    }
}
