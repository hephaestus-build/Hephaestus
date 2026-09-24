package de.tum.cit.aet.hephaestus.practices.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluation;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.EvidenceRole;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationFeedbackErasure;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-Postgres proof of the practices-owned {@link ConversationFeedbackErasure} port. Erasing a set of Slack
 * thread ids for one workspace hard-deletes exactly the {@code chat.conversation_thread} observations/feedback (and
 * their {@code feedback_observation} join rows, via DB {@code ON DELETE CASCADE}) whose {@code artifact_id} is one
 * of those threads in that workspace — while leaving PR/ISSUE rows and another workspace's rows (even one carrying
 * the same {@code artifact_id}) fully intact. The no-regression + tenant-isolation assertions fail if the delete
 * over-reaches. Deterministic.
 */
class ConversationFeedbackErasureIntegrationTest extends BaseIntegrationTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    private ConversationFeedbackErasure erasure;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private DeliveryPolicyEvaluationRepository evaluationRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private IdentityProviderRepository identityProviderRepository;

    private final AtomicInteger positionSeq = new AtomicInteger();

    private Workspace workspaceA;
    private Workspace workspaceB;
    private Practice practiceA;
    private Practice practiceB;
    private User recipientA;
    private User recipientB;
    private User recipientC;
    private AgentJob jobA;
    private AgentJob jobB;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        IdentityProvider provider = identityProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        workspaceA = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("erasure-ws-a"));
        workspaceB = workspaceRepository.save(WorkspaceTestFixtures.activeWorkspace("erasure-ws-b"));
        practiceA = savePractice(workspaceA);
        practiceB = savePractice(workspaceB);
        recipientA = userRepository.save(TestUserFactory.createUser(100L, "recipient-a", provider));
        recipientB = userRepository.save(TestUserFactory.createUser(200L, "recipient-b", provider));
        recipientC = userRepository.save(TestUserFactory.createUser(300L, "recipient-c", provider));
        jobA = newJob(workspaceA);
        jobB = newJob(workspaceB);
    }

    @Test
    @DisplayName(
            "erases only chat.conversation_thread rows for the given threads/workspace; PR + other-tenant rows survive")
    void erasesConversationRowsForThreadsWithoutOverreaching() {
        long thread1 = 5001L;
        long thread2 = 5002L;

        // Workspace A: two conversation threads, each an observation + feedback + join (the erasure targets).
        UUID convObs1 = seedBoundObservationAndFeedback(
                jobA, practiceA, recipientA, ArtifactKinds.CONVERSATION_THREAD, thread1);
        UUID convFb1 = lastFeedbackId;
        UUID convObs2 = seedBoundObservationAndFeedback(
                jobA, practiceA, recipientA, ArtifactKinds.CONVERSATION_THREAD, thread2);
        UUID convFb2 = lastFeedbackId;

        // Workspace A: a PR observation + feedback + join — a different artifact type, MUST survive.
        UUID prObs = seedBoundObservationAndFeedback(jobA, practiceA, recipientA, ArtifactKinds.PULL_REQUEST, thread1);
        UUID prFb = lastFeedbackId;

        // Workspace B: a conversation observation + feedback on the SAME artifact_id as thread1 — a different
        // tenant, MUST survive (proves the workspace predicate, not just the artifact_id, is load-bearing).
        UUID otherWsObs = seedBoundObservationAndFeedback(
                jobB, practiceB, recipientB, ArtifactKinds.CONVERSATION_THREAD, thread1);
        UUID otherWsFb = lastFeedbackId;
        seedEvaluation(jobA);
        seedEvaluation(jobB);

        assertThat(feedbackObservationRepository.count()).isEqualTo(4);

        int deleted = erasure.eraseForThreads(workspaceA.getId(), List.of(thread1, thread2));

        // 2 conversation observations + 2 conversation feedback units deleted.
        assertThat(deleted).isEqualTo(4);

        // The targeted conversation rows are gone…
        assertThat(observationRepository.findById(convObs1)).isEmpty();
        assertThat(observationRepository.findById(convObs2)).isEmpty();
        assertThat(feedbackRepository.findById(convFb1)).isEmpty();
        assertThat(feedbackRepository.findById(convFb2)).isEmpty();

        // …the PR rows (same workspace, different artifact type) survive…
        assertThat(observationRepository.findById(prObs)).isPresent();
        assertThat(feedbackRepository.findById(prFb)).isPresent();

        // …and the other workspace's conversation rows (same artifact_id, different tenant) survive.
        assertThat(observationRepository.findById(otherWsObs)).isPresent();
        assertThat(feedbackRepository.findById(otherWsFb)).isPresent();
        assertThat(evaluationsFor(workspaceA, jobA)).isEmpty();
        assertThat(evaluationsFor(workspaceB, jobB)).hasSize(1);

        // The join rows of the two erased conversation feedback units cascaded away; the 2 survivors' joins remain.
        assertThat(feedbackObservationRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "eraseAllConversationForWorkspace deletes every CONVERSATION row for the workspace; PR + other tenant survive")
    void eraseAllConversationForWorkspaceScopedToTenantAndArtifactKind() {
        // Workspace A: two CONVERSATION threads (different artifact ids) + one PR unit (must survive).
        UUID convObs1 =
                seedBoundObservationAndFeedback(jobA, practiceA, recipientA, ArtifactKinds.CONVERSATION_THREAD, 8001L);
        UUID convFb1 = lastFeedbackId;
        UUID convObs2 =
                seedBoundObservationAndFeedback(jobA, practiceA, recipientA, ArtifactKinds.CONVERSATION_THREAD, 8002L);
        UUID convFb2 = lastFeedbackId;
        UUID prObs = seedBoundObservationAndFeedback(jobA, practiceA, recipientA, ArtifactKinds.PULL_REQUEST, 8001L);
        UUID prFb = lastFeedbackId;

        // Workspace B: an IN_CHAT unit — a different tenant, MUST survive.
        UUID otherWsObs =
                seedBoundObservationAndFeedback(jobB, practiceB, recipientB, ArtifactKinds.CONVERSATION_THREAD, 8003L);
        UUID otherWsFb = lastFeedbackId;

        assertThat(feedbackObservationRepository.count()).isEqualTo(4);

        int deleted = erasure.eraseAllConversationForWorkspace(workspaceA.getId());

        // 2 conversation observations + 2 conversation feedback units deleted (NOT the PR unit).
        assertThat(deleted).isEqualTo(4);
        assertThat(observationRepository.findById(convObs1)).isEmpty();
        assertThat(observationRepository.findById(convObs2)).isEmpty();
        assertThat(feedbackRepository.findById(convFb1)).isEmpty();
        assertThat(feedbackRepository.findById(convFb2)).isEmpty();

        // PR row (same workspace, different artifact type) survives…
        assertThat(observationRepository.findById(prObs)).isPresent();
        assertThat(feedbackRepository.findById(prFb)).isPresent();
        // …and the other tenant's conversation row survives.
        assertThat(observationRepository.findById(otherWsObs)).isPresent();
        assertThat(feedbackRepository.findById(otherWsFb)).isPresent();

        assertThat(feedbackObservationRepository.count()).isEqualTo(2);
        // Idempotent: a second whole-workspace erasure is a no-op.
        assertThat(erasure.eraseAllConversationForWorkspace(workspaceA.getId())).isZero();
    }

    @Test
    @DisplayName(
            "eraseConversationFeedbackAboutUser deletes only that subject's CONVERSATION rows; other user + PR/ISSUE + other tenant survive")
    void eraseConversationFeedbackAboutUserScopedToSubject() {
        // Workspace A, subject = recipientA: one IN_CHAT unit (target) + one PR unit + one ISSUE unit (survive).
        UUID convObsA =
                seedBoundObservationAndFeedback(jobA, practiceA, recipientA, ArtifactKinds.CONVERSATION_THREAD, 9001L);
        UUID convFbA = lastFeedbackId;
        UUID prObsA = seedBoundObservationAndFeedback(jobA, practiceA, recipientA, ArtifactKinds.PULL_REQUEST, 9001L);
        UUID prFbA = lastFeedbackId;
        UUID issueObsA = seedBoundObservationAndFeedback(jobA, practiceA, recipientA, ArtifactKinds.ISSUE, 9002L);
        UUID issueFbA = lastFeedbackId;

        // Workspace A, subject = recipientC: an IN_CHAT unit for a DIFFERENT person — MUST survive.
        UUID convObsOther =
                seedBoundObservationAndFeedback(jobA, practiceA, recipientC, ArtifactKinds.CONVERSATION_THREAD, 9003L);
        UUID convFbOther = lastFeedbackId;

        // Workspace B, subject = recipientA (same user id, different tenant): an IN_CHAT unit — MUST survive.
        UUID convObsWsB =
                seedBoundObservationAndFeedback(jobB, practiceB, recipientA, ArtifactKinds.CONVERSATION_THREAD, 9004L);
        UUID convFbWsB = lastFeedbackId;

        int deleted = erasure.eraseConversationFeedbackAboutUser(workspaceA.getId(), recipientA.getId());

        // Exactly recipientA's single CONVERSATION observation + feedback in workspace A.
        assertThat(deleted).isEqualTo(2);
        assertThat(observationRepository.findById(convObsA)).isEmpty();
        assertThat(feedbackRepository.findById(convFbA)).isEmpty();

        // recipientA's PR + ISSUE units (same subject, different artifact type) survive.
        assertThat(observationRepository.findById(prObsA)).isPresent();
        assertThat(feedbackRepository.findById(prFbA)).isPresent();
        assertThat(observationRepository.findById(issueObsA)).isPresent();
        assertThat(feedbackRepository.findById(issueFbA)).isPresent();

        // The other person's conversation row (same workspace) survives.
        assertThat(observationRepository.findById(convObsOther)).isPresent();
        assertThat(feedbackRepository.findById(convFbOther)).isPresent();

        // The same user's conversation row in another tenant survives.
        assertThat(observationRepository.findById(convObsWsB)).isPresent();
        assertThat(feedbackRepository.findById(convFbWsB)).isPresent();
    }

    @Test
    void erasesConversationPolicyTracesWithTheirSubjectWithoutCrossingTenants() {
        seedBoundObservationAndFeedback(jobA, practiceA, recipientA, ArtifactKinds.CONVERSATION_THREAD, 9101L);
        seedEvaluation(jobA);
        seedBoundObservationAndFeedback(jobB, practiceB, recipientA, ArtifactKinds.CONVERSATION_THREAD, 9102L);
        seedEvaluation(jobB);

        erasure.eraseConversationFeedbackAboutUser(workspaceA.getId(), recipientA.getId());

        assertThat(evaluationsFor(workspaceA, jobA)).isEmpty();
        assertThat(evaluationsFor(workspaceB, jobB)).hasSize(1);
    }

    @Test
    @DisplayName("idempotent: empty thread set and threads with no derived rows delete nothing")
    void idempotentOnEmptyOrUnmatchedInput() {
        UUID convObs =
                seedBoundObservationAndFeedback(jobA, practiceA, recipientA, ArtifactKinds.CONVERSATION_THREAD, 7001L);

        assertThat(erasure.eraseForThreads(workspaceA.getId(), List.of())).isZero();
        assertThat(erasure.eraseForThreads(workspaceA.getId(), List.of(9999L))).isZero();

        // The unrelated row is untouched by both no-op calls.
        assertThat(observationRepository.findById(convObs)).isPresent();
        assertThat(feedbackObservationRepository.count()).isEqualTo(1);
    }

    // --- fixtures ---

    private UUID lastFeedbackId = UUID.randomUUID();

    /** Save an observation, a feedback unit anchored to the same (artifactKind, artifactId), and the join between them. */
    private UUID seedBoundObservationAndFeedback(
            AgentJob job, Practice practice, User recipient, ArtifactKind artifactKind, long artifactId) {
        UUID observationId = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                observationId,
                "occ-" + observationId,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                null,
                artifactKind.value(),
                artifactId,
                recipient.getId(),
                "Observation title",
                "ASSESSED",
                "ABSENT",
                "GOOD",
                "MAJOR",
                null,
                null,
                null,
                Instant.now(),
                "LIVE");

        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(practice.getWorkspace().getId())
                .artifactKind(artifactKind)
                .artifactId(artifactId)
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(FeedbackChannel.IN_CHAT)
                .position(positionSeq.getAndIncrement())
                .deliveryState(FeedbackDeliveryState.PREPARED)
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .build());
        lastFeedbackId = feedback.getId();

        feedbackObservationRepository.insertIfAbsent(feedback.getId(), observationId, EvidenceRole.PRIMARY.name(), 0);
        return observationId;
    }

    private Practice savePractice(Workspace workspace) {
        Practice practice = new Practice();
        practice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.CONVERSATION_THREAD));
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.conversationThread());
        practice.setWorkspace(workspace);
        practice.setSlug("erasure-practice-" + workspace.getId());
        practice.setName("Erasure Practice");
        practice.setCriteria("Test description");
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        return practiceRepository.save(practice);
    }

    private AgentJob newJob(Workspace workspace) {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.CONVERSATION_REVIEW);
        job.setArtifactKind(ArtifactKinds.CONVERSATION_THREAD);
        job.setConfigSnapshot(OM.valueToTree(Map.of("model", "test")));
        return agentJobRepository.save(job);
    }

    private void seedEvaluation(AgentJob job) {
        evaluationRepository.save(DeliveryPolicyEvaluation.builder()
                .workspaceId(job.getWorkspace().getId())
                .agentJobId(job.getId())
                .admittedRevision(0L)
                .resolverVersion("1")
                .surface(DeliveryPolicySurface.CONVERSATION)
                .stage(DeliveryPolicyStage.EGRESS)
                .allowed(true)
                .checks(OM.createArrayNode())
                .facts(OM.createObjectNode())
                .build());
    }

    private List<DeliveryPolicyEvaluation> evaluationsFor(Workspace workspace, AgentJob job) {
        return evaluationRepository.findByWorkspaceIdAndAgentJobIdAndFeedbackIdIsNullAndSurfaceOrderByEvaluatedAtAsc(
                workspace.getId(), job.getId(), DeliveryPolicySurface.CONVERSATION);
    }
}
