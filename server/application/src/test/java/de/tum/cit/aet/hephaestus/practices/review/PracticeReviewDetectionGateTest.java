package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptions;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeReviewReadiness;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceFeatures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeDeliveryStatus;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class PracticeReviewDetectionGateTest extends BaseUnitTest {

    private static final SignalName SIGNAL = ScmSignals.PULL_REQUEST_OPENED;
    private static final Long WORKSPACE_ID = 1L;
    private static final Long PR_ID = 42L;

    @Mock
    private PracticeReviewReadiness practiceDetectionReadiness;

    @Mock
    private PracticeRepository practiceRepository;

    @Mock
    private WorkspaceResolver workspaceResolver;

    /**
     * Lenient because most tests never reach the manual-request question; an unstubbed answer of {@code false}
     * is exactly "this signal is an ordinary occasion", which is what they are about.
     */
    @Mock(strictness = Mock.Strictness.LENIENT)
    private PracticeSignalOptions signalOptions;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private PracticeReviewCoverageService coverageService;

    private PracticeReviewDetectionGate gate;

    @BeforeEach
    void setUp() {
        gate = new PracticeReviewDetectionGate(
                practiceDetectionReadiness, practiceRepository, workspaceResolver, signalOptions, coverageService);
        when(coverageService.admits(
                        any(Workspace.class),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(ReviewSubject.class)))
                .thenReturn(true);
        when(coverageService.admits(
                        any(Workspace.class),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(ReviewSubject.class),
                        org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(true);
        when(coverageService.assessRepositoryless(any(Workspace.class), any(ReviewSubject.class)))
                .thenReturn(new PracticeReviewCoverageService.CoverageAssessment(
                        de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryMode.ALL_MONITORED,
                        de.tum.cit.aet.hephaestus.workspace.settings.ReviewPersonMode.ALL_ELIGIBLE,
                        ReviewSubjectStatus.RESOLVED_LINKED_HUMAN,
                        true,
                        true,
                        true,
                        true));
    }

    // Helpers

    private PullRequest createPullRequest() {
        PullRequest pr = new PullRequest();
        pr.setId(PR_ID);
        pr.setLabels(new HashSet<>());
        pr.setAssignees(new HashSet<>());
        pr.setDraft(false);
        User author = new User();
        author.setId(7L);
        author.setType(User.Type.USER);
        pr.setAuthor(author);

        Repository repo = new Repository();
        repo.setNameWithOwner("ls1intum/Hephaestus");
        pr.setRepository(repo);

        return pr;
    }

    private Label createLabel(String name) {
        Label label = new Label();
        label.setName(name);
        return label;
    }

    private Workspace createWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        workspace.setWorkspaceSlug("test-workspace");
        WorkspaceFeatures features = new WorkspaceFeatures();
        features.setPracticesEnabled(true);
        features.setPracticeReviewAutoTriggerEnabled(true);
        features.setPracticeReviewManualTriggerEnabled(true);
        workspace.setFeatures(features);
        return workspace;
    }

    private Practice createPractice(SignalName... signals) {
        Practice practice = new Practice();
        practice.setBindings(PracticeTestEvidence.bindings(signals));
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        return practice;
    }

    private Practice createDraftPractice(SignalName... signals) {
        Practice practice = new Practice();
        practice.setBindings(List.of(new PracticeBinding(
                List.of(signals), PracticeTestEvidence.needsFor(ArtifactKinds.PULL_REQUEST), true)));
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        return practice;
    }

    private Workspace setupThroughPracticeMatching(PullRequest pr, Practice... practices) {
        Workspace workspace = createWorkspace();
        when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));
        when(practiceDetectionReadiness.hasRunnableAgent(WORKSPACE_ID)).thenReturn(true);
        when(practiceRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(practices));
        return workspace;
    }

    /**
     * A review somebody asked for by hand. No bundled practice binds {@code scm.pull_request.manual_review},
     * so matching a request by signal would refuse every one of them with "no matching practices". The
     * request instead admits every practice on the kind.
     */
    @Nested
    class ManualRequestTests {

        private static final SignalName REQUEST = ScmSignals.PULL_REQUEST_MANUAL_REVIEW;

        @BeforeEach
        void treatTheRequestSignalAsARequest() {
            when(signalOptions.isManualRequest(REQUEST)).thenReturn(true);
        }

        @Test
        @DisplayName("a request admits practices bound to entirely different signals of the same kind")
        void requestAdmitsEveryPracticeOnTheKind() {
            PullRequest pr = createPullRequest();
            Practice onOpened = createPractice(ScmSignals.PULL_REQUEST_OPENED);
            Practice onMerged = createPractice(ScmSignals.PULL_REQUEST_MERGED);
            Workspace workspace = setupThroughPracticeMatching(pr, onOpened, onMerged);
            GateDecision decision = gate.evaluate(pr, REQUEST, TriggerMode.MANUAL);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
            assertThat(((GateDecision.Detect) decision).matchedPractices())
                    .containsExactlyInAnyOrder(onOpened, onMerged);
        }

        @Test
        @DisplayName("a request about a draft is honoured: the person asking has answered that question")
        void requestIgnoresTheDraftFilter() {
            PullRequest pr = createPullRequest();
            pr.setDraft(true);
            Practice notOnDrafts = createPractice(ScmSignals.PULL_REQUEST_OPENED);
            Workspace workspace = setupThroughPracticeMatching(pr, notOnDrafts);

            GateDecision decision = gate.evaluate(pr, REQUEST, TriggerMode.MANUAL);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
            assertThat(((GateDecision.Detect) decision).matchedPractices()).containsExactly(notOnDrafts);
        }

        @Test
        @DisplayName("Off still means off, however the review was occasioned")
        void requestDoesNotOverrideTheTier() {
            PullRequest pr = createPullRequest();
            Practice silenced = createPractice(ScmSignals.PULL_REQUEST_OPENED);
            silenced.setAutonomy(PracticeAutonomy.OFF);
            setupThroughPracticeMatching(pr, silenced);

            GateDecision decision = gate.evaluate(pr, REQUEST, TriggerMode.MANUAL);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).resolvedSignalReason())
                    .isEqualTo(SignalStateReason.PRACTICE_AUTONOMY_OFF);
        }

        @Test
        @DisplayName("a practice on another kind is not dragged in by a pull-request request")
        void requestStaysWithinItsKind() {
            PullRequest pr = createPullRequest();
            Practice onIssues = createPractice(ScmSignals.ISSUE_OPENED);
            setupThroughPracticeMatching(pr, onIssues);

            GateDecision decision = gate.evaluate(pr, REQUEST, TriggerMode.MANUAL);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
        }
    }

    /**
     * Whether a draft occasions a review is a per-binding fact, not a workspace-wide one: a fleet-wide veto
     * would put the draft-specific criteria of a practice like {@code ready-and-traceable-handoff} out of
     * reach of the only artifact they apply to.
     */
    @Nested
    class DraftGateTests {

        @Test
        @DisplayName("a draft does not occasion a practice that did not ask for drafts")
        void skipDraftForAPracticeThatDoesNotWantThem() {
            PullRequest pr = createPullRequest();
            pr.setDraft(true);
            setupThroughPracticeMatching(pr, createPractice(SIGNAL));

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason())
                    .isEqualTo("no practices bound to this signal on drafts");
        }

        @Test
        @DisplayName("a binding that asks for drafts reaches its draft-specific criteria")
        void detectDraftForAPracticeThatAsksForThem() {
            PullRequest pr = createPullRequest();
            pr.setDraft(true);
            Practice onDrafts = createDraftPractice(SIGNAL);
            Workspace workspace = setupThroughPracticeMatching(pr, onDrafts);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
            assertThat(((GateDecision.Detect) decision).matchedPractices()).containsExactly(onDrafts);
        }

        @Test
        @DisplayName("a draft admits only the practices that asked for it, not the whole set")
        void draftAdmitsOnlyTheBindingsThatAskedForIt() {
            PullRequest pr = createPullRequest();
            pr.setDraft(true);
            Practice onDrafts = createDraftPractice(SIGNAL);
            Workspace workspace = setupThroughPracticeMatching(pr, createPractice(SIGNAL), onDrafts);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(((GateDecision.Detect) decision).matchedPractices()).containsExactly(onDrafts);
        }

        @Test
        @DisplayName("a practice that asks for drafts still reviews work that is not a draft")
        void draftBindingAlsoCoversNonDrafts() {
            PullRequest pr = createPullRequest();
            Practice onDrafts = createDraftPractice(SIGNAL);
            Workspace workspace = setupThroughPracticeMatching(pr, onDrafts);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
        }
    }

    @Nested
    class WorkspaceResolutionTests {

        @Test
        void skipWhenNoWorkspace() {
            PullRequest pr = createPullRequest();
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.empty());

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason()).isEqualTo("no workspace");
        }

        @Test
        void skipWhenNullRepository() {
            PullRequest pr = createPullRequest();
            pr.setRepository(null);
            when(workspaceResolver.resolveForRepository(null)).thenReturn(Optional.empty());

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason()).isEqualTo("no workspace");
        }
    }

    @Nested
    class PracticesEnabledTests {

        @Test
        void skipWhenPracticesDisabled() {
            PullRequest pr = createPullRequest();
            Workspace workspace = createWorkspace();
            workspace.getFeatures().setPracticesEnabled(false);
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason()).isEqualTo("practices disabled for workspace");
            verifyNoInteractions(practiceDetectionReadiness, practiceRepository);
        }

        @Test
        void practicesDisabledOutranksAnOutOfCoverageSkip() {
            PullRequest pr = createPullRequest();
            Workspace workspace = createWorkspace();
            workspace.getFeatures().setPracticesEnabled(false);
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));
            when(coverageService.admits(
                            any(Workspace.class),
                            nullable(String.class),
                            nullable(String.class),
                            nullable(ReviewSubject.class)))
                    .thenReturn(false);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(((GateDecision.Skip) decision).reason()).isEqualTo("practices disabled for workspace");
        }
    }

    @Nested
    class TriggerModeTests {

        @Test
        void skipWhenAutoTriggerDisabled() {
            PullRequest pr = createPullRequest();
            Workspace workspace = createWorkspace();
            workspace.getFeatures().setPracticeReviewAutoTriggerEnabled(false);
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason()).isEqualTo("auto-trigger disabled for workspace");
            verifyNoInteractions(practiceDetectionReadiness, practiceRepository);
        }

        @Test
        void skipWhenManualTriggerDisabled() {
            PullRequest pr = createPullRequest();
            Workspace workspace = createWorkspace();
            workspace.getFeatures().setPracticeReviewManualTriggerEnabled(false);
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.MANUAL);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason()).isEqualTo("manual trigger disabled for workspace");
            verifyNoInteractions(practiceDetectionReadiness, practiceRepository);
        }

        @Test
        void skipWhenBothTriggersDisabled() {
            PullRequest pr = createPullRequest();
            Workspace workspace = createWorkspace();
            workspace.getFeatures().setPracticeReviewAutoTriggerEnabled(false);
            workspace.getFeatures().setPracticeReviewManualTriggerEnabled(false);
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));

            GateDecision autoDecision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);
            GateDecision manualDecision = gate.evaluate(pr, SIGNAL, TriggerMode.MANUAL);

            assertThat(autoDecision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) autoDecision).reason()).isEqualTo("auto-trigger disabled for workspace");
            assertThat(manualDecision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) manualDecision).reason())
                    .isEqualTo("manual trigger disabled for workspace");
        }

        @Test
        void continueWhenAutoTriggerDisabledButModeIsManual() {
            PullRequest pr = createPullRequest();
            Workspace workspace = createWorkspace();
            workspace.getFeatures().setPracticeReviewAutoTriggerEnabled(false);
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));
            when(practiceDetectionReadiness.hasRunnableAgent(WORKSPACE_ID)).thenReturn(false);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.MANUAL);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason()).isEqualTo("no runnable practice-review agent");
        }

        @Test
        void continueWhenManualTriggerDisabledButModeIsAuto() {
            PullRequest pr = createPullRequest();
            Workspace workspace = createWorkspace();
            workspace.getFeatures().setPracticeReviewManualTriggerEnabled(false);
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));
            when(practiceDetectionReadiness.hasRunnableAgent(WORKSPACE_ID)).thenReturn(false);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason()).isEqualTo("no runnable practice-review agent");
        }
    }

    @Nested
    class AgentBindingGateTests {

        @Test
        void skipWhenNoRunnablePracticeDetectionBinding() {
            PullRequest pr = createPullRequest();
            Workspace workspace = createWorkspace();
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));
            when(practiceDetectionReadiness.hasRunnableAgent(WORKSPACE_ID)).thenReturn(false);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason()).isEqualTo("no runnable practice-review agent");
        }
    }

    @Nested
    class PracticeMatchingTests {

        @Test
        void skipWhenNoMatchingPractices() {
            PullRequest pr = createPullRequest();
            Practice practice = createPractice(ScmSignals.PULL_REQUEST_REVIEWED);
            setupThroughPracticeMatching(pr, practice);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) decision).reason()).isEqualTo("no matching practices");
        }

        @Test
        void returnsAllMatchingPractices() {
            PullRequest pr = createPullRequest();
            Practice matching1 = createPractice(SIGNAL, ScmSignals.PULL_REQUEST_REVIEWED);
            Practice matching2 = createPractice(SIGNAL);
            Practice nonMatching = createPractice(ScmSignals.PULL_REQUEST_REVIEWED);
            Workspace workspace = createWorkspace();
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));
            when(practiceDetectionReadiness.hasRunnableAgent(WORKSPACE_ID)).thenReturn(true);
            when(practiceRepository.findByWorkspaceId(WORKSPACE_ID))
                    .thenReturn(List.of(matching1, matching2, nonMatching));

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
            GateDecision.Detect detect = (GateDecision.Detect) decision;
            assertThat(detect.matchedPractices()).hasSize(2);
            assertThat(detect.matchedPractices()).containsExactly(matching1, matching2);
        }
    }

    @Nested
    class DetectionAudienceTests {

        @Test
        void detectsWithoutAssigneeOrRoleCheck() {
            PullRequest pr = createPullRequest();
            Practice practice = createPractice(SIGNAL);
            Workspace workspace = setupThroughPracticeMatching(pr, practice);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
            GateDecision.Detect detect = (GateDecision.Detect) decision;
            assertThat(detect.workspace()).isEqualTo(workspace);
            assertThat(detect.matchedPractices()).containsExactly(practice);
        }
    }

    @Nested
    class HappyPathTests {

        @Test
        @DisplayName("Should return Detect with workspace and matched practices when all checks pass")
        void fullHappyPath() {
            PullRequest pr = createPullRequest();
            pr.getLabels().add(createLabel("enhancement"));
            Practice practice = createPractice(SIGNAL);
            Workspace workspace = setupThroughPracticeMatching(pr, practice);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
            GateDecision.Detect detect = (GateDecision.Detect) decision;
            assertThat(detect.workspace().getId()).isEqualTo(WORKSPACE_ID);
            assertThat(detect.workspace().getWorkspaceSlug()).isEqualTo("test-workspace");
            assertThat(detect.matchedPractices()).containsExactly(practice);
        }
    }

    /**
     * OFF is the only autonomy that stops a review; HUMAN_APPROVAL is as reviewed as AUTOMATIC and differs only in what
     * may be said about the result, so its signals must reach the agent exactly like AUTOMATIC's do.
     */
    @Nested
    class AutonomyAdmissionTests {

        @Test
        void detectsWhenTheOnlyBoundPracticeIsProposingSilently() {
            PullRequest pr = createPullRequest();
            Practice measured = createPractice(SIGNAL);
            measured.setAutonomy(PracticeAutonomy.HUMAN_APPROVAL);
            Workspace workspace = setupThroughPracticeMatching(pr, measured);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
            assertThat(((GateDecision.Detect) decision).matchedPractices()).containsExactly(measured);
        }

        @Test
        void skipsAndNamesTheTierWhenEveryBoundPracticeIsOff() {
            PullRequest pr = createPullRequest();
            Practice silenced = createPractice(SIGNAL);
            silenced.setAutonomy(PracticeAutonomy.OFF);
            setupThroughPracticeMatching(pr, silenced);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            GateDecision.Skip skip = (GateDecision.Skip) decision;
            assertThat(skip.reason()).isEqualTo("every practice bound to this signal is off");
            // The whole point of the separate reason: an admin turned this down, and can turn it back up.
            assertThat(skip.resolvedSignalReason()).isEqualTo(SignalStateReason.PRACTICE_AUTONOMY_OFF);
            assertThat(skip.resolvedSignalReason().isRetryable()).isTrue();
        }

        @Test
        void keepsTheGenericReasonWhenNothingIsBoundAtAll() {
            PullRequest pr = createPullRequest();
            Practice other = createPractice(ScmSignals.PULL_REQUEST_MERGED);
            other.setAutonomy(PracticeAutonomy.OFF);
            setupThroughPracticeMatching(pr, other);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            GateDecision.Skip skip = (GateDecision.Skip) decision;
            assertThat(skip.reason()).isEqualTo("no matching practices");
            assertThat(skip.resolvedSignalReason()).isEqualTo(SignalStateReason.GATE_SKIPPED);
        }

        @Test
        void admitsOnlyTheReviewablePracticesWhenTheSignalIsSharedWithAnOffOne() {
            PullRequest pr = createPullRequest();
            Practice silenced = createPractice(SIGNAL);
            silenced.setAutonomy(PracticeAutonomy.OFF);
            Practice delivering = createPractice(SIGNAL);
            Workspace workspace = setupThroughPracticeMatching(pr, silenced, delivering);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(((GateDecision.Detect) decision).matchedPractices()).containsExactly(delivering);
        }
    }

    /**
     * A binding names a signal and cannot name the trunk it fires against, so this is where "we only
     * review merges into main" is expressible at all.
     */
    @Nested
    class ReviewScopeTests {

        @Test
        void pausedDeliveryDoesNotStopReviewCompute() {
            PullRequest pr = createPullRequest();
            Workspace workspace = setupThroughPracticeMatching(pr, createPractice(SIGNAL));
            workspace.getReviewSettings().setDeliveryStatus(PracticeDeliveryStatus.PAUSED);

            assertThat(gate.evaluate(pr, SIGNAL, TriggerMode.AUTO)).isInstanceOf(GateDecision.Detect.class);
        }

        @Test
        void admitsAPullRequestTargetingAScopedBranch() {
            PullRequest pr = createPullRequest();
            pr.setBaseRefName("main");
            setupThroughPracticeMatching(pr, createPractice(SIGNAL));

            assertThat(gate.evaluate(pr, SIGNAL, TriggerMode.AUTO)).isInstanceOf(GateDecision.Detect.class);
        }

        @Test
        void refusesAPullRequestTargetingABranchOutsideTheScope() {
            PullRequest pr = createPullRequest();
            pr.setBaseRefName("develop");
            Workspace workspace = createWorkspace();
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));
            when(coverageService.admits(workspace, "ls1intum/Hephaestus", "develop", pr.reviewSubject()))
                    .thenReturn(false);

            GateDecision decision = gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            GateDecision.Skip skip = (GateDecision.Skip) decision;
            assertThat(skip.reason()).contains("outside review coverage");
            assertThat(skip.resolvedSignalReason()).isEqualTo(SignalStateReason.OUT_OF_REVIEW_SCOPE);
            // Terminal, not pending: the branch the artifact targeted will not change, so re-offering it
            // would be the reaper re-deciding a decision that cannot come out differently.
            assertThat(skip.resolvedSignalReason().isRetryable()).isFalse();
        }

        @Test
        void anAdministrativeEvaluationMayRunOutsideCoverage() {
            PullRequest pr = createPullRequest();
            pr.setBaseRefName("develop");
            Workspace workspace = setupThroughPracticeMatching(pr, createPractice(SIGNAL));
            when(coverageService.admits(workspace, "ls1intum/Hephaestus", "develop", pr.reviewSubject()))
                    .thenReturn(false);

            assertThat(gate.evaluateAdministrative(pr, SIGNAL)).isInstanceOf(GateDecision.Detect.class);
        }

        /** Cheap enough to sit ahead of every query — no catalogue read happens for out-of-scope work. */
        @Test
        void refusesBeforePayingForAnyPracticeLookup() {
            PullRequest pr = createPullRequest();
            pr.setBaseRefName("develop");
            Workspace workspace = createWorkspace();
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));
            when(coverageService.admits(workspace, "ls1intum/Hephaestus", "develop", pr.reviewSubject()))
                    .thenReturn(false);

            gate.evaluate(pr, SIGNAL, TriggerMode.AUTO);

            verifyNoInteractions(practiceRepository);
            verifyNoInteractions(practiceDetectionReadiness);
        }

        @Test
        void refusesARepositoryTheWorkspaceSyncsButDoesNotReview() {
            PullRequest pr = createPullRequest();
            pr.setBaseRefName("main");
            Workspace workspace = createWorkspace();
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));
            when(coverageService.admits(workspace, "ls1intum/Hephaestus", "main", pr.reviewSubject()))
                    .thenReturn(false);

            assertThat(gate.evaluate(pr, SIGNAL, TriggerMode.AUTO)).isInstanceOf(GateDecision.Skip.class);
        }

        @Test
        void reviewerScopeUsesTheReviewerRatherThanThePullRequestAuthor() {
            PullRequest pr = createPullRequest();
            pr.setBaseRefName("main");
            ReviewSubject reviewer = new ReviewSubject(99L, true);
            Workspace workspace = createWorkspace();
            when(workspaceResolver.resolveForRepository("ls1intum/Hephaestus")).thenReturn(Optional.of(workspace));
            when(coverageService.admits(workspace, "ls1intum/Hephaestus", "main", reviewer))
                    .thenReturn(false);

            assertThat(gate.evaluate(pr, SIGNAL, TriggerMode.AUTO, reviewer)).isInstanceOf(GateDecision.Skip.class);
            verify(coverageService).admits(workspace, "ls1intum/Hephaestus", "main", reviewer);
            verify(coverageService, never()).admits(workspace, "ls1intum/Hephaestus", "main", pr.reviewSubject());
        }

        /**
         * An issue has no target branch, so a branch scope must not silently stop issue review. Only the
         * repository axis can narrow it — which is the documented limit, pinned here.
         */
        @Test
        void aBranchScopeDoesNotNarrowIssueReview() {
            Issue issue = new Issue();
            issue.setId(7L);
            issue.setAssignees(new HashSet<>());
            User author = new User();
            author.setId(7L);
            author.setType(User.Type.USER);
            issue.setAuthor(author);
            Repository repo = new Repository();
            repo.setNameWithOwner("ls1intum/Hephaestus");
            issue.setRepository(repo);
            Workspace workspace = createWorkspace();
            when(workspaceResolver.resolveAllForRepository("ls1intum/Hephaestus"))
                    .thenReturn(List.of(workspace));
            when(practiceDetectionReadiness.hasRunnableAgent(WORKSPACE_ID)).thenReturn(true);
            Practice issuePractice = createPractice(ScmSignals.ISSUE_OPENED);
            when(practiceRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(issuePractice));
            GateDecision decision = gate.evaluateIssue(issue, WORKSPACE_ID, ScmSignals.ISSUE_OPENED, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
        }

        @Test
        void reviewsAnIssueInTheRequestedWorkspaceWhenSeveralMonitorItsRepository() {
            Issue issue = new Issue();
            issue.setId(7L);
            issue.setAssignees(new HashSet<>());
            Repository repo = new Repository();
            repo.setNameWithOwner("ls1intum/Hephaestus");
            issue.setRepository(repo);
            Workspace first = createWorkspace();
            Workspace second = createWorkspace();
            second.setId(2L);
            when(workspaceResolver.resolveAllForRepository("ls1intum/Hephaestus"))
                    .thenReturn(List.of(first, second));
            when(practiceDetectionReadiness.hasRunnableAgent(2L)).thenReturn(true);
            when(practiceRepository.findByWorkspaceId(2L)).thenReturn(List.of(createPractice(ScmSignals.ISSUE_OPENED)));

            GateDecision decision = gate.evaluateIssue(issue, 2L, ScmSignals.ISSUE_OPENED, TriggerMode.AUTO);

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
            assertThat(((GateDecision.Detect) decision).workspace().getId()).isEqualTo(2L);
            verify(practiceRepository, never()).findByWorkspaceId(WORKSPACE_ID);
        }
    }

    /**
     * The gate reached by a kind that has no repository, no branch and no assignee. An entry point that
     * took a {@code PullRequest} or an {@code Issue} could not gate such a kind at all: it would go
     * straight to submission, losing the difference between "no practice for this work" and "a practice
     * bound to it and turned off".
     */
    @Nested
    class RepoLessSignalGate {

        private static final SignalName DOCUMENT_PUBLISHED = SignalName.of("docs.document.published");

        @Test
        void detectsWhenAPracticeIsBoundToTheSignalAndAudible() {
            Workspace workspace = createWorkspace();
            when(practiceDetectionReadiness.hasRunnableAgent(WORKSPACE_ID)).thenReturn(true);
            when(practiceRepository.findByWorkspaceId(WORKSPACE_ID))
                    .thenReturn(List.of(createPractice(DOCUMENT_PUBLISHED)));

            GateDecision decision =
                    gate.evaluateSignal(workspace, DOCUMENT_PUBLISHED, TriggerMode.AUTO, new ReviewSubject(7L, true));

            assertThat(decision).isInstanceOf(GateDecision.Detect.class);
            assertThat(((GateDecision.Detect) decision).matchedPractices()).hasSize(1);
        }

        @Test
        @DisplayName("a practice turned all the way down is a different answer from no practice at all")
        void separatesSilencedFromAbsent() {
            Workspace workspace = createWorkspace();
            when(practiceDetectionReadiness.hasRunnableAgent(WORKSPACE_ID)).thenReturn(true);
            Practice silenced = createPractice(DOCUMENT_PUBLISHED);
            silenced.setAutonomy(PracticeAutonomy.OFF);
            when(practiceRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(silenced));

            GateDecision silencedDecision =
                    gate.evaluateSignal(workspace, DOCUMENT_PUBLISHED, TriggerMode.AUTO, new ReviewSubject(7L, true));

            assertThat(silencedDecision).isInstanceOf(GateDecision.Skip.class);
            assertThat(((GateDecision.Skip) silencedDecision).resolvedSignalReason())
                    .isEqualTo(SignalStateReason.PRACTICE_AUTONOMY_OFF);

            when(practiceRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of());
            GateDecision absentDecision =
                    gate.evaluateSignal(workspace, DOCUMENT_PUBLISHED, TriggerMode.AUTO, new ReviewSubject(7L, true));

            assertThat(((GateDecision.Skip) absentDecision).resolvedSignalReason())
                    .isEqualTo(SignalStateReason.GATE_SKIPPED);
        }

        @Test
        void refusesWhenPracticesAreDisabledForTheWorkspace() {
            Workspace workspace = createWorkspace();
            workspace.getFeatures().setPracticesEnabled(false);

            GateDecision decision =
                    gate.evaluateSignal(workspace, DOCUMENT_PUBLISHED, TriggerMode.AUTO, new ReviewSubject(7L, true));

            assertThat(decision).isInstanceOf(GateDecision.Skip.class);
            verifyNoInteractions(practiceRepository);
        }

        @Test
        void refusesWhenAutoTriggerIsOff() {
            Workspace workspace = createWorkspace();
            workspace.getFeatures().setPracticeReviewAutoTriggerEnabled(false);

            assertThat(gate.evaluateSignal(
                            workspace, DOCUMENT_PUBLISHED, TriggerMode.AUTO, new ReviewSubject(7L, true)))
                    .isInstanceOf(GateDecision.Skip.class);
        }

        @Test
        void shouldRefuseRepositorylessKindWhenSpecificRepositoriesAreSelected() {
            Workspace workspace = createWorkspace();
            when(coverageService.assessRepositoryless(any(Workspace.class), any(ReviewSubject.class)))
                    .thenReturn(new PracticeReviewCoverageService.CoverageAssessment(
                            de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryMode.SELECTED,
                            de.tum.cit.aet.hephaestus.workspace.settings.ReviewPersonMode.ALL_ELIGIBLE,
                            ReviewSubjectStatus.RESOLVED_LINKED_HUMAN,
                            false,
                            false,
                            true,
                            false));

            GateDecision decision =
                    gate.evaluateSignal(workspace, DOCUMENT_PUBLISHED, TriggerMode.AUTO, new ReviewSubject(7L, true));

            assertThat(((GateDecision.Skip) decision).resolvedSignalReason())
                    .isEqualTo(SignalStateReason.OUT_OF_REVIEW_SCOPE);
        }
    }
}
