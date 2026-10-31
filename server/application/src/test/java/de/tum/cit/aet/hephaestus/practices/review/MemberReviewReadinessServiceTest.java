package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
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
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewReadinessBlocker;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeMode;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceReviewScope;
import de.tum.cit.aet.hephaestus.workspace.spi.AiRefusalReason;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiReadiness;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipReviewSelection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MemberReviewReadinessServiceTest extends BaseUnitTest {
    private static final long WORKSPACE = 1L;
    private static final long ACCOUNT = 10L;

    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final PracticeRepository practices = mock(PracticeRepository.class);
    private final PracticeReviewCoverageService coverage = mock(PracticeReviewCoverageService.class);
    private final MembershipReviewSelection selection = mock(MembershipReviewSelection.class);
    private final MemberAiReadiness ai = mock(MemberAiReadiness.class);
    private final WorkspaceReviewDefaultsProvider defaults = mock(WorkspaceReviewDefaultsProvider.class);
    // The registered descriptors: readiness reads the same offered events and roles as the gate.
    private final PracticeItemOptions items = new PracticeItemOptions(new ItemDescriptorRegistry(List.of(
            new PullRequestItemDescriptor(), new PullRequestReviewItemDescriptor(), new IssueItemDescriptor())));
    private final MemberReviewReadinessService service = new MemberReviewReadinessService(
            workspaces,
            new ReviewGate(
                    practices,
                    items,
                    new JsonMapper(),
                    new ClasspathSourceCatalogRegistry(JsonMapper.builder().build(), Clock.systemUTC())),
            coverage,
            selection,
            ai,
            practices,
            items,
            defaults);
    private final Workspace workspace = new Workspace();
    private final List<Practice> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        workspace.setId(WORKSPACE);
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.getFeatures().setPracticesEnabled(true);
        workspace.getFeatures().setPracticeReviewAutoTriggerEnabled(true);
        workspace.getFeatures().setPracticeReviewManualTriggerEnabled(true);
        lenient().when(workspaces.findById(WORKSPACE)).thenReturn(Optional.of(workspace));
        lenient().when(practices.findByWorkspaceId(WORKSPACE)).thenReturn(stored);
        lenient().when(coverage.scope(workspace)).thenReturn(WorkspaceReviewScope.ALL);
        lenient().when(selection.eligibleAccountIds(anyLong(), anyCollection())).thenReturn(Set.of(ACCOUNT));
        lenient().when(ai.practiceReviewRefusal(WORKSPACE, ACCOUNT)).thenReturn(Optional.empty());
        lenient().when(defaults.rawDefaultFeedbackMode(WORKSPACE)).thenReturn(FeedbackMode.AUTOMATIC);
        stored.add(practice(1, DefinitionTestFixtures.definition()));
    }

    @Test
    void shouldReportNoBlockerWhenEveryRuleAdmitsTheMember() {
        assertThat(blocker()).isNull();
    }

    @Test
    void shouldReportTheInactiveWorkspaceFirstWhenEveryRuleRefuses() {
        workspace.setStatus(Workspace.WorkspaceStatus.SUSPENDED);
        workspace.getFeatures().setPracticesEnabled(false);
        refuseEveryMemberRule(AiRefusalReason.NO_AI);

        assertThat(blocker()).isEqualTo(ReviewReadinessBlocker.WORKSPACE_INACTIVE);
    }

    @Test
    void shouldReportReviewsOffWhenPracticesAreDisabled() {
        workspace.getFeatures().setPracticesEnabled(false);
        refuseEveryMemberRule(AiRefusalReason.NO_AI);

        assertThat(blocker()).isEqualTo(ReviewReadinessBlocker.REVIEWS_OFF);
    }

    @Test
    void shouldReportReviewsOffWhenNeitherActivityNorRequestsStartAReview() {
        workspace.getFeatures().setPracticeReviewAutoTriggerEnabled(false);
        workspace.getFeatures().setPracticeReviewManualTriggerEnabled(false);

        assertThat(blocker()).isEqualTo(ReviewReadinessBlocker.REVIEWS_OFF);
    }

    @Test
    void shouldReportOnRequestOnlyWhenOnlyRequestsStartAReview() {
        workspace.getFeatures().setPracticeReviewAutoTriggerEnabled(false);

        assertThat(blocker()).isEqualTo(ReviewReadinessBlocker.ON_REQUEST_ONLY);
    }

    @Test
    void shouldReportTheMembersNoAiBeforeOnRequestOnlyBecauseARequestWouldBeRefusedToo() {
        workspace.getFeatures().setPracticeReviewAutoTriggerEnabled(false);
        refuseEveryMemberRule(AiRefusalReason.NO_AI);

        assertThat(blocker()).isEqualTo(ReviewReadinessBlocker.NO_AI);
    }

    @Test
    void shouldReportOutsideReviewScopeBeforeTheModelWhenTheRolloutLeavesTheMemberOut() {
        refuseEveryMemberRule(AiRefusalReason.UNAVAILABLE);

        assertThat(blocker()).isEqualTo(ReviewReadinessBlocker.OUTSIDE_REVIEW_SCOPE);
    }

    @Test
    void shouldLeaveRepositoryCoverageToThePieceOfWorkWhenTheRolloutSelectsNoRepository() {
        // Work without a repository, such as a Slack thread or an Outline document, stays reviewable.
        lenient()
                .when(coverage.scope(workspace))
                .thenReturn(
                        new WorkspaceReviewScope(ReviewScopeMode.SELECTED, ReviewScopeMode.ALL, List.of(), List.of()));

        assertThat(blocker()).isNull();
    }

    @Test
    void shouldReportNotEligibleWhenEveryoneIsInScopeButSelectionCannotTakeTheMember() {
        lenient().when(selection.eligibleAccountIds(anyLong(), anyCollection())).thenReturn(Set.of());
        lenient()
                .when(ai.practiceReviewRefusal(WORKSPACE, ACCOUNT))
                .thenReturn(Optional.of(AiRefusalReason.UNAVAILABLE));

        assertThat(blocker()).isEqualTo(ReviewReadinessBlocker.NOT_ELIGIBLE);
    }

    @Test
    void shouldReportTheMembersOwnReasonWhenEligibilityLeavesOutAnErasedOrNoAiMember() {
        lenient().when(selection.eligibleAccountIds(anyLong(), anyCollection())).thenReturn(Set.of());

        lenient()
                .when(ai.practiceReviewRefusal(WORKSPACE, ACCOUNT))
                .thenReturn(Optional.of(AiRefusalReason.PERSON_ERASED));
        assertThat(blocker()).isEqualTo(ReviewReadinessBlocker.PERSON_ERASED);

        lenient().when(ai.practiceReviewRefusal(WORKSPACE, ACCOUNT)).thenReturn(Optional.of(AiRefusalReason.NO_AI));
        assertThat(blocker()).isEqualTo(ReviewReadinessBlocker.NO_AI);
    }

    @Test
    void shouldReportChoiceRequiredWhenTheMemberHasNotChosen() {
        lenient()
                .when(ai.practiceReviewRefusal(WORKSPACE, ACCOUNT))
                .thenReturn(Optional.of(AiRefusalReason.CHOICE_REQUIRED));

        assertThat(blocker()).isEqualTo(ReviewReadinessBlocker.CHOICE_REQUIRED);
    }

    @Test
    void shouldReportNoModelBeforeNoPracticeWhenNoModelServesTheMember() {
        stored.clear();
        lenient()
                .when(ai.practiceReviewRefusal(WORKSPACE, ACCOUNT))
                .thenReturn(Optional.of(AiRefusalReason.UNAVAILABLE));

        assertThat(blocker()).isEqualTo(ReviewReadinessBlocker.NO_MODEL);
    }

    @Test
    void shouldReportNoPracticeWhenNoReviewedPracticeIsAboutThePersonWhoDidTheWork() {
        stored.clear();
        stored.add(practice(2, DefinitionTestFixtures.withAbout(DefinitionTestFixtures.definition(), null)));
        stored.add(practice(3, reviewedBy(ReviewedByKind.PEOPLE)));

        var readiness = service.readiness(WORKSPACE, ACCOUNT);

        assertThat(readiness.blocker()).isEqualTo(ReviewReadinessBlocker.NO_PRACTICE);
        assertThat(readiness.events()).isEmpty();
        assertThat(readiness.approvalBeforePosting()).isFalse();
    }

    @Test
    void shouldListTheSortedDistinctEventsOfOnlyThePracticesThatReviewTheMember() {
        stored.clear();
        stored.add(practice(
                1,
                withEvents(
                        DefinitionTestFixtures.definition(),
                        "scm.pull_request.synchronized",
                        "scm.pull_request.opened")));
        stored.add(practice(
                2,
                withEvents(DefinitionTestFixtures.definition(), "scm.pull_request.ready", "scm.pull_request.opened")));
        // About nobody, reviewed by people, or bound to an event its item kind does not raise: none reviews the member.
        stored.add(practice(
                3,
                withEvents(
                        DefinitionTestFixtures.withAbout(DefinitionTestFixtures.definition(), null),
                        "scm.pull_request.merged")));
        stored.add(practice(4, withEvents(reviewedBy(ReviewedByKind.PEOPLE), "scm.pull_request.closed")));
        stored.add(practice(5, withEvents(DefinitionTestFixtures.definition(), "scm.pull_request_review.submitted")));

        assertThat(service.readiness(WORKSPACE, ACCOUNT).events())
                .containsExactly("scm.pull_request.opened", "scm.pull_request.ready", "scm.pull_request.synchronized");
    }

    @Test
    void shouldReportApprovalOnlyWhenAPracticeThatReviewsTheMemberWaitsForApproval() {
        var unrelated = practice(2, DefinitionTestFixtures.withAbout(DefinitionTestFixtures.definition(), null));
        unrelated.setFeedbackMode(FeedbackMode.APPROVE_FIRST);
        stored.add(unrelated);
        assertThat(service.readiness(WORKSPACE, ACCOUNT).approvalBeforePosting())
                .isFalse();

        stored.getFirst().setFeedbackMode(FeedbackMode.APPROVE_FIRST);
        assertThat(service.readiness(WORKSPACE, ACCOUNT).approvalBeforePosting())
                .isTrue();
    }

    @Test
    void shouldReportApprovalWhenTheWorkspaceNeverChoseAFeedbackMode() {
        lenient().when(defaults.rawDefaultFeedbackMode(WORKSPACE)).thenReturn(null);

        assertThat(service.readiness(WORKSPACE, ACCOUNT).approvalBeforePosting())
                .isTrue();
    }

    private @Nullable ReviewReadinessBlocker blocker() {
        return service.readiness(WORKSPACE, ACCOUNT).blocker();
    }

    /** The rollout leaves the member out, eligibility drops them, and the AI rule refuses them. */
    private void refuseEveryMemberRule(AiRefusalReason reason) {
        lenient()
                .when(coverage.scope(workspace))
                .thenReturn(
                        new WorkspaceReviewScope(ReviewScopeMode.ALL, ReviewScopeMode.SELECTED, List.of(), List.of()));
        lenient().when(selection.eligibleAccountIds(anyLong(), anyCollection())).thenReturn(Set.of());
        lenient().when(ai.practiceReviewRefusal(WORKSPACE, ACCOUNT)).thenReturn(Optional.of(reason));
    }

    private static Practice practice(long id, PracticeDefinition definition) {
        return ReviewTestData.practice(WORKSPACE, id, "practice-" + id, definition);
    }

    private static PracticeDefinition withEvents(PracticeDefinition definition, String... events) {
        return DefinitionTestFixtures.withReviewOn(
                definition,
                new ReviewOn(Arrays.stream(events).map(ItemEventName::new).toList(), Map.of()));
    }

    private static PracticeDefinition reviewedBy(ReviewedByKind by) {
        var original = DefinitionTestFixtures.definition();
        return new PracticeDefinition(
                original.name(),
                original.reviewOn(),
                null,
                original.about(),
                original.reads(),
                new ReviewedBy(by, new ReviewLimit("MANUAL_REVIEW", "People review this practice."), List.of()),
                original.criteria(),
                null,
                null,
                true,
                original.guidance());
    }
}
