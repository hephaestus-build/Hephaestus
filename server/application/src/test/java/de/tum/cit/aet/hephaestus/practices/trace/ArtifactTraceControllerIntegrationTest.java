package de.tum.cit.aet.hephaestus.practices.trace;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluation;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The trace view answers "why didn't anything happen to this merge request?" to a workspace admin, and to a
 * member for a review that observed them.
 *
 * <p>Three properties matter more than the rendering. The ledger is the tenancy boundary — an artifact
 * with no recorded signal in this workspace is a 404 whatever the mirror holds — a member reading work that
 * is not theirs gets the same 404, and a practice that is quiet is present in the answer with its reason
 * rather than absent from it.
 */
class ArtifactTraceControllerIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TRACE = "/workspaces/{slug}/practices/trace/{kind}/{id}";
    private static final String LIST = "/workspaces/{slug}/practices/trace";
    private static final long ARTIFACT_ID = 482L;
    private static final Instant READY_AT = Instant.parse("2026-08-07T14:02:00Z");

    @Autowired
    private ArtifactSignalRepository signalRepository;

    @Autowired
    private PracticeGroupRepository groupRepository;

    @Autowired
    private DeliveryPolicyEvaluationRepository deliveryPolicyEvaluationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private Workspace workspace;
    private Workspace otherWorkspace;
    private User author;
    private User member;

    @BeforeEach
    void setUpWorkspaces() {
        User owner = persistUser("trace-owner");
        workspace = createWorkspace("trace-ws", "Trace WS", "trace-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        author = persistUser("alice");
        member = persistUser("testuser");
        User workspaceAdmin = persistUser("mentor");
        ensureWorkspaceMembership(workspace, author, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, member, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, workspaceAdmin, WorkspaceMembership.WorkspaceRole.ADMIN);

        User otherOwner = persistUser("other-owner");
        otherWorkspace = createWorkspace("other-trace-ws", "Other", "other-trace-org", AccountType.ORG, otherOwner);
    }

    @Nested
    @DisplayName("Access control")
    class AccessControl {

        @Test
        void refusesAnAnonymousCaller() {
            recordSignal(workspace, ScmSignals.PULL_REQUEST_READY, SignalState.RECORDED, null, null);

            webTestClient
                    .get()
                    .uri(TRACE, workspace.getWorkspaceSlug(), ArtifactKinds.PULL_REQUEST.value(), ARTIFACT_ID)
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }

        /** A public-read workspace admits an anonymous read at the filter chain; membership still stops it. */
        @Test
        void refusesAnAnonymousCallerOnAPublicWorkspace() {
            AgentJob job = reviewObserving(member, READY_AT);
            workspace.setIsPubliclyViewable(true);
            workspaceRepository.save(workspace);

            webTestClient
                    .get()
                    .uri(
                            TRACE + "?reviewId={reviewId}",
                            workspace.getWorkspaceSlug(),
                            ArtifactKinds.PULL_REQUEST.value(),
                            ARTIFACT_ID,
                            job.getId())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @WithUser
        void admitsAMemberNamingAReviewThatObservedThemWithoutAdminPolicyFacts() {
            AgentJob job = reviewObserving(member, READY_AT);
            deliveryPolicyEvaluationRepository.save(DeliveryPolicyEvaluation.builder()
                    .workspaceId(workspace.getId())
                    .agentJobId(job.getId())
                    .admittedRevision(0L)
                    .evaluatedRevision(0L)
                    .resolverVersion("v1")
                    .surface(DeliveryPolicySurface.ARTIFACT)
                    .stage(DeliveryPolicyStage.EGRESS)
                    .allowed(false)
                    .checks(OBJECT_MAPPER.createArrayNode())
                    .facts(OBJECT_MAPPER.createObjectNode().put("recipientConsent", false))
                    .evaluatedAt(Instant.now())
                    .build());

            getForReview(job.getId())
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.artifactId")
                    .isEqualTo(ARTIFACT_ID)
                    .jsonPath("$.deliveryPolicy")
                    .doesNotExist();
        }

        /** Every review of the work at once is the admin's view; a member asking for it learns nothing. */
        @Test
        @WithUser
        void hidesWorkFromAMemberWhoNamesNoReview() {
            reviewObserving(member, READY_AT);

            get(TRACE, workspace.getWorkspaceSlug(), ArtifactKinds.PULL_REQUEST.value(), ARTIFACT_ID)
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithUser
        void hidesAReviewThatObservedSomebodyElseFromAMember() {
            AgentJob job = reviewObserving(author, READY_AT);

            getForReview(job.getId()).expectStatus().isNotFound().expectBody(Void.class);
        }

        /** A review of their own names one piece of work; it opens no other the review also carried. */
        @Test
        @WithUser
        void hidesOtherWorkFromAMemberNamingTheirOwnReview() {
            AgentJob job = reviewObserving(member, READY_AT);
            long otherWork = ARTIFACT_ID + 1;
            recordSignal(
                    workspace,
                    ScmSignals.PULL_REQUEST_READY,
                    SignalState.TRIGGERED,
                    null,
                    job.getId(),
                    READY_AT,
                    otherWork);

            get(
                            TRACE + "?reviewId={reviewId}",
                            workspace.getWorkspaceSlug(),
                            ArtifactKinds.PULL_REQUEST.value(),
                            otherWork,
                            job.getId())
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void admitsAnAdminNamingAReviewThatObservedSomebodyElse() {
            AgentJob job = reviewObserving(author, READY_AT);

            getForReview(job.getId()).expectStatus().isOk().expectBody(Void.class);
        }

        /** Viewing as a member is reading as that member: the administrator's own reach does not come along. */
        @Test
        void readsAsTheViewedMemberWhenAnInstanceAdministratorViewsThem() {
            AgentJob own = reviewObserving(member, READY_AT);
            AgentJob theirs = reviewObserving(author, READY_AT.plusSeconds(3600));
            Account administrator = persistInstanceAdmin("Trace inspector");

            viewAsMember(administrator, TRACE, ArtifactKinds.PULL_REQUEST.value(), ARTIFACT_ID)
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
            viewAsMember(
                            administrator,
                            TRACE + "?reviewId={reviewId}",
                            ArtifactKinds.PULL_REQUEST.value(),
                            ARTIFACT_ID,
                            theirs.getId())
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
            viewAsMember(
                            administrator,
                            TRACE + "?reviewId={reviewId}",
                            ArtifactKinds.PULL_REQUEST.value(),
                            ARTIFACT_ID,
                            own.getId())
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
        }

        /** The ledger, not the mirror, decides visibility: another tenant's artifact id has no row here. */
        @Test
        @WithMentorUser
        void hidesAnArtifactOnlyAnotherWorkspaceRecorded() {
            recordSignal(otherWorkspace, ScmSignals.PULL_REQUEST_READY, SignalState.RECORDED, null, null);

            get(TRACE, workspace.getWorkspaceSlug(), ArtifactKinds.PULL_REQUEST.value(), ARTIFACT_ID)
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithUser
        void refusesAnUnknownArtifactKind() {
            get(TRACE, workspace.getWorkspaceSlug(), "NotAKind", ARTIFACT_ID)
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("The answer")
    class Answers {

        /** The group is on the entry because a practice that stayed quiet is on no list the reader holds. */
        @Test
        @WithMentorUser
        void namesTheGroupOfEveryPracticeIncludingTheQuietOnes() {
            PracticeGroup packaging = persistGroup("review-ready-work", "Packaging work for review");
            persistPractice(
                    "grouped",
                    "Grouped practice",
                    PracticeAutonomy.AUTOMATIC,
                    ScmSignals.PULL_REQUEST_READY,
                    packaging);
            persistPractice("loose", "Loose practice", PracticeAutonomy.AUTOMATIC);
            recordSignal(workspace, ScmSignals.PULL_REQUEST_READY, SignalState.RECORDED, null, null);

            get(TRACE, workspace.getWorkspaceSlug(), ArtifactKinds.PULL_REQUEST.value(), ARTIFACT_ID)
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.practices[?(@.practiceSlug=='grouped')].groupSlug")
                    .isEqualTo("review-ready-work")
                    .jsonPath("$.practices[?(@.practiceSlug=='grouped')].groupName")
                    .isEqualTo("Packaging work for review")
                    .jsonPath("$.practices[?(@.practiceSlug=='loose')].groupSlug")
                    .doesNotExist();
        }

        /**
         * {@code dormant} watches a signal no connected integration raises, so it is reported as waiting.
         * {@code not-admitted} watches the very signal in the ledger, so it is <em>not</em> reported as
         * waiting even though coverage would say so: the recorded occurrence refutes the claim.
         */
        @Test
        @WithMentorUser
        void reportsEveryPracticeIncludingTheQuietOnes() {
            Practice reviewed = persistPractice("reviewed", "Reviewed practice", PracticeAutonomy.AUTOMATIC);
            persistPractice("silenced", "Silenced practice", PracticeAutonomy.OFF);
            persistPractice("not-admitted", "Not admitted practice", PracticeAutonomy.AUTOMATIC);
            persistPractice("dormant", "Dormant practice", PracticeAutonomy.AUTOMATIC, ScmSignals.PULL_REQUEST_MERGED);
            AgentJob job = persistJob();
            recordSignal(workspace, ScmSignals.PULL_REQUEST_READY, SignalState.TRIGGERED, null, job.getId());
            insertObservation(reviewed, job);

            get(TRACE, workspace.getWorkspaceSlug(), ArtifactKinds.PULL_REQUEST.value(), ARTIFACT_ID)
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.artifactId")
                    .isEqualTo(ARTIFACT_ID)
                    .jsonPath("$.signals.length()")
                    .isEqualTo(1)
                    .jsonPath("$.signals[0].signal")
                    .isEqualTo(ScmSignals.PULL_REQUEST_READY.value())
                    .jsonPath("$.signals[0].displayName")
                    .isEqualTo("Marked ready for review")
                    .jsonPath("$.practices.length()")
                    .isEqualTo(4)
                    .jsonPath("$.practices[?(@.practiceSlug=='reviewed')].outcome")
                    .isEqualTo("REVIEWED")
                    .jsonPath("$.practices[?(@.practiceSlug=='reviewed')].observationCount")
                    .isEqualTo(1)
                    .jsonPath("$.practices[?(@.practiceSlug=='silenced')].outcome")
                    .isEqualTo("TURNED_OFF")
                    .jsonPath("$.practices[?(@.practiceSlug=='not-admitted')].outcome")
                    .isEqualTo("SKIPPED")
                    .jsonPath("$.practices[?(@.practiceSlug=='dormant')].outcome")
                    .isEqualTo("DORMANT")
                    .jsonPath("$.practices[?(@.practiceSlug=='dormant')].explanation")
                    .value(
                            (java.util.List<String> value) -> org.hamcrest.MatcherAssert.assertThat(
                                    value,
                                    org.hamcrest.Matchers.hasItem(
                                            org.hamcrest.Matchers.containsString(
                                                    "No connected integration raises scm.pull_request.merged; connect GITHUB or GITLAB"))));
        }

        @Test
        @WithMentorUser
        void explainsARefusedSignalWithTheActionThatWouldLiftIt() {
            persistPractice("waiting", "Waiting practice", PracticeAutonomy.AUTOMATIC);
            recordSignal(
                    workspace,
                    ScmSignals.PULL_REQUEST_READY,
                    SignalState.PENDING,
                    SignalStateReason.BUDGET_EXHAUSTED,
                    null);

            get(TRACE, workspace.getWorkspaceSlug(), ArtifactKinds.PULL_REQUEST.value(), ARTIFACT_ID)
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.practices[0].outcome")
                    .isEqualTo("PENDING")
                    .jsonPath("$.practices[0].explanation")
                    .value(
                            String.class,
                            value -> org.hamcrest.MatcherAssert.assertThat(
                                    value, org.hamcrest.Matchers.containsString("budget refills")))
                    .jsonPath("$.signals[0].stateReason")
                    .isEqualTo("BUDGET_EXHAUSTED");
        }

        /**
         * Two reviews of one pull request. Named, the older review answers for itself, down to the feedback the
         * newer one delivered; the occurrence ledger stays whole either way.
         */
        @Test
        @WithMentorUser
        void answersForOneReviewWhenTheCallerNamesIt() {
            Practice first = persistPractice("first", "First practice", PracticeAutonomy.AUTOMATIC);
            Practice second = persistPractice("second", "Second practice", PracticeAutonomy.AUTOMATIC);
            AgentJob older = persistJob();
            AgentJob newer = persistJob();
            recordSignal(
                    workspace, ScmSignals.PULL_REQUEST_READY, SignalState.TRIGGERED, null, older.getId(), READY_AT);
            recordSignal(
                    workspace,
                    ScmSignals.PULL_REQUEST_READY,
                    SignalState.TRIGGERED,
                    null,
                    newer.getId(),
                    READY_AT.plusSeconds(3600));
            insertObservation(first, older);
            deliverFeedback(newer, insertObservation(second, newer));

            get(TRACE, workspace.getWorkspaceSlug(), ArtifactKinds.PULL_REQUEST.value(), ARTIFACT_ID)
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.practices[?(@.practiceSlug=='first')].outcome")
                    .isEqualTo("REVIEWED")
                    .jsonPath("$.practices[?(@.practiceSlug=='second')].outcome")
                    .isEqualTo("REVIEWED")
                    .jsonPath("$.practices[?(@.practiceSlug=='second')].deliveredCount")
                    .isEqualTo(1);

            get(
                            TRACE + "?reviewId={reviewId}",
                            workspace.getWorkspaceSlug(),
                            ArtifactKinds.PULL_REQUEST.value(),
                            ARTIFACT_ID,
                            older.getId())
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.signals.length()")
                    .isEqualTo(2)
                    .jsonPath("$.practices[?(@.practiceSlug=='first')].outcome")
                    .isEqualTo("REVIEWED")
                    .jsonPath("$.practices[?(@.practiceSlug=='first')].observationCount")
                    .isEqualTo(1)
                    .jsonPath("$.practices[?(@.practiceSlug=='first')].reviewId")
                    .isEqualTo(older.getId().toString())
                    .jsonPath("$.practices[?(@.practiceSlug=='second')].outcome")
                    .isEqualTo("SKIPPED")
                    .jsonPath("$.practices[?(@.practiceSlug=='second')].observationCount")
                    .isEqualTo(0)
                    .jsonPath("$.practices[?(@.practiceSlug=='second')].deliveredCount")
                    .isEqualTo(0);
        }

        /**
         * One review observed two developers on the same pull request. The member reads what it made of them
         * alone: another developer's observations, delivered feedback and withheld reasons stay out of it.
         */
        @Test
        @WithUser
        void countsOnlyWhatConcernsTheMemberInAReviewThatObservedOthersToo() {
            Practice shared = persistPractice(workspace, null, "shared", "Shared practice", null);
            Practice theirs = persistPractice(workspace, null, "theirs", "Their practice", null);
            AgentJob job = persistPullRequestReview(workspace, (int) ARTIFACT_ID, ARTIFACT_ID, READY_AT);
            recordSignal(workspace, ScmSignals.PULL_REQUEST_READY, SignalState.TRIGGERED, null, job.getId());
            UUID aboutMember =
                    observe(shared, job, ARTIFACT_ID, member, ObservationKind.DEMONSTRATED_STRENGTH, null, READY_AT);
            UUID aboutAuthor =
                    observe(shared, job, ARTIFACT_ID, author, ObservationKind.DEMONSTRATED_STRENGTH, null, READY_AT);
            UUID onlyAuthor =
                    observe(theirs, job, ARTIFACT_ID, author, ObservationKind.DEMONSTRATED_STRENGTH, null, READY_AT);
            feedback(job, 1, aboutMember, member, FeedbackDeliveryState.DELIVERED, null);
            feedback(job, 2, aboutAuthor, author, FeedbackDeliveryState.DELIVERED, null);
            feedback(
                    job,
                    3,
                    onlyAuthor,
                    author,
                    FeedbackDeliveryState.SUPPRESSED,
                    FeedbackSuppressionReason.RECIPIENT_OPTED_OUT);

            getForReview(job.getId())
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.practices[?(@.practiceSlug=='shared')].outcome")
                    .isEqualTo("REVIEWED")
                    .jsonPath("$.practices[?(@.practiceSlug=='shared')].observationCount")
                    .isEqualTo(1)
                    .jsonPath("$.practices[?(@.practiceSlug=='shared')].deliveredCount")
                    .isEqualTo(1)
                    .jsonPath("$.practices[?(@.practiceSlug=='theirs')].observationCount")
                    .isEqualTo(0)
                    .jsonPath("$.practices[?(@.practiceSlug=='theirs')].deliveredCount")
                    .isEqualTo(0)
                    .jsonPath("$.practices[?(@.practiceSlug=='theirs')].withheldReasons[*]")
                    .isEmpty();
        }

        /** The admin reads the same shared review whole. */
        @Test
        @WithMentorUser
        void countsEveryoneInASharedReviewForAnAdmin() {
            Practice shared = persistPractice(workspace, null, "shared", "Shared practice", null);
            AgentJob job = persistPullRequestReview(workspace, (int) ARTIFACT_ID, ARTIFACT_ID, READY_AT);
            recordSignal(workspace, ScmSignals.PULL_REQUEST_READY, SignalState.TRIGGERED, null, job.getId());
            observe(shared, job, ARTIFACT_ID, member, ObservationKind.DEMONSTRATED_STRENGTH, null, READY_AT);
            UUID aboutAuthor =
                    observe(shared, job, ARTIFACT_ID, author, ObservationKind.DEMONSTRATED_STRENGTH, null, READY_AT);
            feedback(
                    job,
                    1,
                    aboutAuthor,
                    author,
                    FeedbackDeliveryState.SUPPRESSED,
                    FeedbackSuppressionReason.RECIPIENT_OPTED_OUT);

            getForReview(job.getId())
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.practices[?(@.practiceSlug=='shared')].observationCount")
                    .isEqualTo(2)
                    .jsonPath("$.practices[?(@.practiceSlug=='shared')].withheldReasons[0]")
                    .isEqualTo("RECIPIENT_OPTED_OUT");
        }

        @Test
        @WithMentorUser
        void answersNothingForAReviewThatNeverRanOnTheArtifact() {
            persistPractice("waiting", "Waiting practice", PracticeAutonomy.AUTOMATIC);
            recordSignal(
                    workspace,
                    ScmSignals.PULL_REQUEST_READY,
                    SignalState.TRIGGERED,
                    null,
                    persistJob().getId());

            get(
                            TRACE + "?reviewId={reviewId}",
                            workspace.getWorkspaceSlug(),
                            ArtifactKinds.PULL_REQUEST.value(),
                            ARTIFACT_ID,
                            persistJob().getId())
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void answersNothingForAnArtifactNobodyRecordedAnythingAbout() {
            persistPractice("waiting", "Waiting practice", PracticeAutonomy.AUTOMATIC);

            get(TRACE, workspace.getWorkspaceSlug(), ArtifactKinds.PULL_REQUEST.value(), ARTIFACT_ID)
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("The index")
    class Index {

        @Test
        @WithUser
        void refusesAMember() {
            recordSignal(workspace, ScmSignals.PULL_REQUEST_READY, SignalState.RECORDED, null, null);

            get(LIST, workspace.getWorkspaceSlug()).expectStatus().isForbidden().expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void listsWorkThatWasNeverReviewed() {
            recordSignal(workspace, ScmSignals.PULL_REQUEST_READY, SignalState.RECORDED, null, null);
            recordSignal(otherWorkspace, ScmSignals.PULL_REQUEST_MERGED, SignalState.RECORDED, null, null);

            get(LIST, workspace.getWorkspaceSlug())
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].artifactId")
                    .isEqualTo(ARTIFACT_ID)
                    .jsonPath("$.content[0].artifactKind")
                    .isEqualTo(ArtifactKinds.PULL_REQUEST.value())
                    .jsonPath("$.content[0].signalCount")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].reviewedSignalCount")
                    .isEqualTo(0);
        }

        @Test
        @WithMentorUser
        void filtersByKind() {
            recordSignal(workspace, ScmSignals.PULL_REQUEST_READY, SignalState.RECORDED, null, null);

            get(LIST + "?artifactKind={kind}", workspace.getWorkspaceSlug(), ArtifactKinds.ISSUE.value())
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(0);
        }
    }

    private WebTestClient.ResponseSpec get(String uri, Object... vars) {
        return webTestClient
                .get()
                .uri(uri, vars)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange();
    }

    private WebTestClient.ResponseSpec getForReview(UUID reviewId) {
        return get(
                TRACE + "?reviewId={reviewId}",
                workspace.getWorkspaceSlug(),
                ArtifactKinds.PULL_REQUEST.value(),
                ARTIFACT_ID,
                reviewId);
    }

    /** A read by an instance administrator in a read-only user view of {@code member}. */
    private WebTestClient.ResponseSpec viewAsMember(Account administrator, String uri, Object... vars) {
        Object[] withSlug = new Object[vars.length + 1];
        withSlug[0] = workspace.getWorkspaceSlug();
        System.arraycopy(vars, 0, withSlug, 1, vars.length);
        return webTestClient
                .get()
                .uri(uri, withSlug)
                .headers(headers -> headers.setBearerAuth("mock-jwt-sub-" + administrator.getId()))
                .header(UserViewContextHolder.WORKSPACE_HEADER, workspace.getWorkspaceSlug())
                .header(UserViewContextHolder.USER_HEADER, String.valueOf(member.getId()))
                .header(UserViewContextHolder.REASON_HEADER, "Check a review")
                .exchange();
    }

    /**
     * A review of the artifact, started by the occurrence at {@code readyAt}, that recorded one observation about
     * {@code developer} they may see.
     */
    private AgentJob reviewObserving(User developer, Instant readyAt) {
        Practice practice =
                persistPractice(workspace, null, "observed-" + UUID.randomUUID(), "Observed practice", null);
        AgentJob job = persistPullRequestReview(workspace, (int) ARTIFACT_ID, ARTIFACT_ID, readyAt);
        recordSignal(workspace, ScmSignals.PULL_REQUEST_READY, SignalState.TRIGGERED, null, job.getId(), readyAt);
        observe(practice, job, ARTIFACT_ID, developer, ObservationKind.DEMONSTRATED_STRENGTH, null, readyAt);
        return job;
    }

    private Practice persistPractice(String slug, String name, PracticeAutonomy autonomy) {
        return persistPractice(slug, name, autonomy, ScmSignals.PULL_REQUEST_READY);
    }

    private PracticeGroup persistGroup(String slug, String name) {
        PracticeGroup group = new PracticeGroup();
        group.setWorkspace(workspace);
        group.setSlug(slug);
        group.setName(name);
        return groupRepository.save(group);
    }

    private Practice persistPractice(String slug, String name, PracticeAutonomy autonomy, SignalName signal) {
        return persistPractice(slug, name, autonomy, signal, null);
    }

    private Practice persistPractice(
            String slug, String name, PracticeAutonomy autonomy, SignalName signal, @Nullable PracticeGroup group) {
        Practice practice = new Practice();
        practice.setGroup(group);
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(workspace);
        practice.setSlug(slug);
        practice.setName(name);
        practice.setCriteria("Criteria for " + slug);
        practice.setBindings(PracticeTestEvidence.bindings(signal));
        practice.setAutonomy(autonomy);
        return practiceRepository.save(practice);
    }

    private AgentJob persistJob() {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        // A finished run: an unfinished one is reported as still running, which is a different answer.
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setCompletedAt(READY_AT);
        return agentJobRepository.save(job);
    }

    private ArtifactSignal recordSignal(
            Workspace ws,
            SignalName signal,
            SignalState state,
            @Nullable SignalStateReason reason,
            @Nullable UUID jobId) {
        return recordSignal(ws, signal, state, reason, jobId, READY_AT);
    }

    private ArtifactSignal recordSignal(
            Workspace ws,
            SignalName signal,
            SignalState state,
            @Nullable SignalStateReason reason,
            @Nullable UUID jobId,
            Instant occurredAt) {
        return recordSignal(ws, signal, state, reason, jobId, occurredAt, ARTIFACT_ID);
    }

    private ArtifactSignal recordSignal(
            Workspace ws,
            SignalName signal,
            SignalState state,
            @Nullable SignalStateReason reason,
            @Nullable UUID jobId,
            Instant occurredAt,
            long artifactId) {
        ArtifactSignal row = new ArtifactSignal();
        row.setId(UUID.randomUUID());
        row.setWorkspace(ws);
        row.setArtifactKind(ArtifactKinds.PULL_REQUEST.value());
        row.setArtifactId(artifactId);
        row.setSignalName(signal.value());
        // One row per revision: the ledger is unique on it, so two occurrences of one signal differ here.
        row.setRevision("sha~" + occurredAt.getEpochSecond());
        row.setOccurredAt(occurredAt);
        row.setDiscoveredVia(DiscoveredVia.EVENT);
        row.setState(state);
        row.setStateReason(reason);
        row.setJobId(jobId);
        row.setStateChangedAt(occurredAt);
        return signalRepository.save(row);
    }

    private void deliverFeedback(AgentJob job, UUID observationId) {
        feedback(job, 1, observationId, author, FeedbackDeliveryState.DELIVERED, null);
    }

    /** One piece of feedback about and to {@code recipient}, at {@code position} in the review's own order. */
    private void feedback(
            AgentJob job,
            int position,
            UUID observationId,
            User recipient,
            FeedbackDeliveryState state,
            @Nullable FeedbackSuppressionReason reason) {
        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(FeedbackChannel.IN_APP)
                .position(position)
                .deliveryState(state)
                .suppressionReason(reason)
                .body("Feedback")
                .source(FeedbackSource.AGENT)
                .createdAt(READY_AT)
                .deliveredAt(state == FeedbackDeliveryState.DELIVERED ? READY_AT : null)
                .build());
        feedbackObservationRepository.insertIfAbsent(feedback.getId(), observationId, "PRIMARY", 0);
    }

    private UUID insertObservation(Practice practice, AgentJob job) {
        UUID id = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                id,
                "occurrence-" + id,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                null,
                ArtifactKinds.PULL_REQUEST.value(),
                ARTIFACT_ID,
                author.getId(),
                "Something was observed",
                "ASSESSED",
                "PRESENT",
                "GOOD",
                null,
                "{\"citations\":[]}",
                "Because the diff says so",
                "recurrence-1",
                READY_AT,
                "LIVE");
        return id;
    }
}
