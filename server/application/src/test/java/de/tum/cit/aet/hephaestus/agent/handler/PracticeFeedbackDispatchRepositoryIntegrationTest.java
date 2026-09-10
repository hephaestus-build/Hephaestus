package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluation;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyStage;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicySurface;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchCompletion;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchInsert;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class PracticeFeedbackDispatchRepositoryIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private FeedbackDispatchRepository dispatchRepository;

    @Autowired
    private DeliveryPolicyEvaluationRepository evaluationRepository;

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Workspace workspace;
    private UUID jobId;
    private Long ownerId;

    @BeforeEach
    void seedDispatchOwner() {
        User owner = persistUser("dispatch-owner");
        ownerId = owner.getId();
        workspace = createWorkspace("dispatch", "Dispatch", "dispatch-org", AccountType.ORG, owner);
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setArtifactKind(ArtifactKinds.PULL_REQUEST);
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setConfigSnapshot(tools.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        jobId = jobRepository.saveAndFlush(job).getId();
    }

    @Test
    void shouldAllowExactlyOneConcurrentClaim() throws Exception {
        UUID dispatchId = insertDispatch(workspace.getId(), jobId, "claim-race");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> claimAfter(ready, start, dispatchId, "first"));
            var second = executor.submit(() -> claimAfter(ready, start, dispatchId, "second"));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(0, 1);
        }
    }

    @Test
    void shouldFenceOldOwnerWhenExpiredLeaseIsTakenOver() {
        UUID dispatchId = insertDispatch(workspace.getId(), jobId, "lease-takeover");
        assertThat(claim(dispatchId, "first", Instant.now().plusSeconds(60))).isEqualTo(1);
        assertThat(claim(dispatchId, "second", Instant.now().plusSeconds(60))).isZero();

        jdbcTemplate.update(
                "UPDATE feedback_dispatch SET lease_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                dispatchId);

        assertThat(claim(dispatchId, "second", Instant.now().plusSeconds(60))).isEqualTo(1);
        assertThat(beginWrite(dispatchId, "first")).isZero();
        assertThat(beginWrite(dispatchId, "second")).isEqualTo(1);
    }

    @Test
    void shouldRejectJobWhenItBelongsToAnotherWorkspace() {
        User otherOwner = persistUser("other-dispatch-owner");
        Workspace other = createWorkspace("other-dispatch", "Other", "other-org", AccountType.ORG, otherOwner);

        assertThat(tryInsertDispatch(other.getId(), jobId, "cross-tenant")).isNull();
    }

    @Test
    void scmErasureDeletesOnlyArtifactDispatchesFromTheNamedWorkspace() {
        UUID scmDispatch = insertDispatch(workspace.getId(), jobId, "scm-erasure");
        UUID conversationJob = saveJob(workspace, AgentJobType.CONVERSATION_REVIEW, ArtifactKinds.CONVERSATION_THREAD);
        UUID conversationDispatch = insertDispatch(workspace.getId(), conversationJob, "conversation-survivor");
        UUID scmEvaluation = seedEvaluation(workspace, jobId, DeliveryPolicySurface.ARTIFACT);
        UUID conversationEvaluation = seedEvaluation(workspace, conversationJob, DeliveryPolicySurface.CONVERSATION);

        User otherOwner = persistUser("other-erasure-owner");
        Workspace other =
                createWorkspace("other-erasure", "Other erasure", "other-erasure-org", AccountType.ORG, otherOwner);
        UUID otherJob = saveJob(other, AgentJobType.PULL_REQUEST_REVIEW, ArtifactKinds.PULL_REQUEST);
        UUID otherDispatch = insertDispatch(other.getId(), otherJob, "other-tenant-survivor");
        UUID otherEvaluation = seedEvaluation(other, otherJob, DeliveryPolicySurface.ARTIFACT);

        Integer deletedDispatches =
                transactions.execute(status -> dispatchRepository.deleteScmArtifactDispatches(workspace.getId()));
        Integer deletedEvaluations =
                transactions.execute(status -> evaluationRepository.deleteScmArtifactEvaluations(workspace.getId()));
        assertThat(deletedDispatches).isEqualTo(1);
        assertThat(deletedEvaluations).isEqualTo(1);

        assertThat(dispatchRepository.findById(scmDispatch)).isEmpty();
        assertThat(dispatchRepository.findById(conversationDispatch)).isPresent();
        assertThat(dispatchRepository.findById(otherDispatch)).isPresent();
        assertThat(evaluationRepository.findById(scmEvaluation)).isEmpty();
        assertThat(evaluationRepository.findById(conversationEvaluation)).isPresent();
        assertThat(evaluationRepository.findById(otherEvaluation)).isPresent();
    }

    @Test
    void shouldNotReclaimTerminalFailure() {
        UUID dispatchId = insertDispatch(workspace.getId(), jobId, "terminal-failure");
        jdbcTemplate.update(
                "UPDATE feedback_dispatch SET state = 'FAILED', lease_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                dispatchId);

        assertThat(claim(dispatchId, "late-redelivery", Instant.now().plusSeconds(60)))
                .isZero();
    }

    @Test
    void projectionErasesDeliveredPayloadButKeepsTheIdempotencyFence() {
        String key = "projected-payload";
        UUID dispatchId = insertDispatch(workspace.getId(), jobId, key);
        jdbcTemplate.update(
                "UPDATE feedback_dispatch SET state = 'SENT', delivered_external_ref = 'summary-1' WHERE id = ?",
                dispatchId);

        assertThat(claimProjection(dispatchId, "projector")).isEqualTo(1);
        assertThat(markProjected(dispatchId, "projector")).isEqualTo(1);

        assertThat(dispatchRepository.findById(dispatchId)).hasValueSatisfying(dispatch -> {
            assertThat(dispatch.getBody()).isEmpty();
            assertThat(dispatch.getPracticeSlugs()).isEmpty();
            assertThat(dispatch.packageContent()).isEmpty();
        });
        assertThat(tryInsertDispatch(workspace.getId(), jobId, key)).isNull();
    }

    @Test
    void projectionKeepsFailedPayloadAvailableForAnExplicitRetry() {
        UUID dispatchId = insertDispatch(workspace.getId(), jobId, "failed-payload");
        jdbcTemplate.update("UPDATE feedback_dispatch SET state = 'FAILED' WHERE id = ?", dispatchId);

        assertThat(claimProjection(dispatchId, "projector")).isEqualTo(1);
        assertThat(markProjected(dispatchId, "projector")).isEqualTo(1);

        assertThat(dispatchRepository.findById(dispatchId)).hasValueSatisfying(dispatch -> {
            assertThat(dispatch.getBody()).isEqualTo("body");
            assertThat(dispatch.packageContent().path("mrNote").asString()).isEqualTo("body");
        });
    }

    @Test
    void shouldKeepAmbiguousWriteRecoverableAfterRetryBudget() {
        UUID dispatchId = insertDispatch(workspace.getId(), jobId, "ambiguous-write");
        jdbcTemplate.update(
                "UPDATE feedback_dispatch SET state = 'UNCERTAIN', write_started = TRUE, attempt_count = 8 WHERE id = ?",
                dispatchId);

        assertThat(claim(dispatchId, "reconciler", Instant.now().plusSeconds(60)))
                .isEqualTo(1);
    }

    @Test
    void recoveryQueriesNeverExhaustAnAmbiguousWrite() {
        UUID safeToFail = insertDispatch(workspace.getId(), jobId, "safe-to-fail");
        UUID ambiguous = insertDispatch(workspace.getId(), jobId, "ambiguous-beyond-budget");
        jdbcTemplate.update(
                "UPDATE feedback_dispatch SET state = 'UNCERTAIN', attempt_count = 8 WHERE id = ?", safeToFail);
        jdbcTemplate.update(
                "UPDATE feedback_dispatch SET state = 'UNCERTAIN', write_started = TRUE, attempt_count = 8 WHERE id = ?",
                ambiguous);

        Instant now = Instant.now().plusSeconds(1);
        assertThat(dispatchRepository.findRecoverable(now, 8, PageRequest.of(0, 10)))
                .extracting(dispatch -> dispatch.getId())
                .contains(ambiguous)
                .doesNotContain(safeToFail);
        assertThat(dispatchRepository.findExhausted(now, 8, PageRequest.of(0, 10)))
                .extracting(dispatch -> dispatch.getId())
                .contains(safeToFail)
                .doesNotContain(ambiguous);
    }

    @Test
    void shouldResetFailedAutomaticPackagesBeforeOrAfterProviderWrite() {
        UUID safe = insertDispatch(workspace.getId(), jobId, "safe-retry");
        UUID ambiguous = insertDispatch(workspace.getId(), jobId, "ambiguous-retry");
        jdbcTemplate.update(
                "UPDATE feedback_dispatch SET state = 'FAILED', attempt_count = 8, last_error = 'failed' WHERE id = ?",
                safe);
        jdbcTemplate.update(
                "UPDATE feedback_dispatch SET state = 'FAILED', write_started = TRUE, attempt_count = 8 WHERE id = ?",
                ambiguous);

        Integer reset = transactions.execute(
                status -> dispatchRepository.resetFailedAutomaticPackage(jobId, workspace.getId()));
        assertThat(reset).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM feedback_dispatch WHERE id = ?", String.class, safe))
                .isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT attempt_count FROM feedback_dispatch WHERE id = ?", Integer.class, safe))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT state FROM feedback_dispatch WHERE id = ?", String.class, ambiguous))
                .isEqualTo("PENDING");
    }

    @Test
    void approvedPackageKeepsTheSummaryReferenceWhileInlineDeliveryIsUncertain() {
        Feedback feedback = feedbackRepository.saveAndFlush(Feedback.builder()
                .agentJobId(jobId)
                .workspaceId(workspace.getId())
                .recipientUserId(ownerId)
                .aboutUserId(ownerId)
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(8000)
                .deliveryState(FeedbackDeliveryState.PREPARED)
                .body("approved body")
                .source(FeedbackSource.AGENT)
                .build());
        UUID dispatchId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> dispatchRepository.insertIfAbsent(new FeedbackDispatchInsert(
                dispatchId,
                "approved:" + feedback.getId(),
                workspace.getId(),
                jobId,
                feedback.getId(),
                "APPROVED_REVIEW_PACKAGE",
                "approved body",
                "[]",
                "{\"mrNote\":\"approved body\",\"diffNotes\":[],\"withheld\":[]}")));
        assertThat(claim(dispatchId, "package-worker", Instant.now().plusSeconds(60)))
                .isEqualTo(1);

        Integer finished = transactions.execute(status -> dispatchRepository.finish(new FeedbackDispatchCompletion(
                dispatchId,
                workspace.getId(),
                "package-worker",
                FeedbackDispatchState.UNCERTAIN.name(),
                "summary-42",
                "inline delivery incomplete",
                null,
                "[]",
                Instant.now())));

        assertThat(finished).isEqualTo(1);
        assertThat(dispatchRepository.findByIdAndWorkspaceId(dispatchId, workspace.getId()))
                .hasValueSatisfying(dispatch -> {
                    assertThat(dispatch.getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
                    assertThat(dispatch.getDeliveredExternalRef()).isEqualTo("summary-42");
                });
    }

    private int claimAfter(CountDownLatch ready, CountDownLatch start, UUID dispatchId, String owner) {
        try {
            ready.countDown();
            assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
            return claim(dispatchId, owner, Instant.now().plusSeconds(60));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private int claim(UUID dispatchId, String owner, Instant leaseUntil) {
        return transactions.execute(
                status -> dispatchRepository.claim(dispatchId, workspace.getId(), owner, leaseUntil, 8));
    }

    private int beginWrite(UUID dispatchId, String owner) {
        return transactions.execute(status -> dispatchRepository.beginWrite(dispatchId, workspace.getId(), owner));
    }

    private int claimProjection(UUID dispatchId, String owner) {
        return transactions.execute(status -> dispatchRepository.claimProjection(
                dispatchId, workspace.getId(), owner, Instant.now().plusSeconds(60)));
    }

    private int markProjected(UUID dispatchId, String owner) {
        return transactions.execute(status -> dispatchRepository.markProjected(dispatchId, workspace.getId(), owner));
    }

    private UUID insertDispatch(long workspaceId, UUID owningJobId, String key) {
        return java.util.Objects.requireNonNull(
                tryInsertDispatch(workspaceId, owningJobId, key), "the insert this test builds on must land");
    }

    private UUID saveJob(Workspace owningWorkspace, AgentJobType type, ArtifactKind kind) {
        AgentJob job = new AgentJob();
        job.setWorkspace(owningWorkspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(type);
        job.setArtifactKind(kind);
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setConfigSnapshot(tools.jackson.databind.node.JsonNodeFactory.instance.objectNode());
        return jobRepository.saveAndFlush(job).getId();
    }

    private UUID seedEvaluation(Workspace owningWorkspace, UUID owningJobId, DeliveryPolicySurface surface) {
        return evaluationRepository
                .saveAndFlush(DeliveryPolicyEvaluation.builder()
                        .workspaceId(owningWorkspace.getId())
                        .agentJobId(owningJobId)
                        .admittedRevision(0L)
                        .resolverVersion("1")
                        .surface(surface)
                        .stage(DeliveryPolicyStage.EGRESS)
                        .allowed(true)
                        .checks(tools.jackson.databind.node.JsonNodeFactory.instance.arrayNode())
                        .facts(tools.jackson.databind.node.JsonNodeFactory.instance.objectNode())
                        .build())
                .getId();
    }

    private @Nullable UUID tryInsertDispatch(long workspaceId, UUID owningJobId, String key) {
        UUID id = UUID.randomUUID();
        Integer inserted = transactions.execute(status -> dispatchRepository.insertIfAbsent(new FeedbackDispatchInsert(
                id,
                key,
                workspaceId,
                owningJobId,
                null,
                "AUTOMATIC_REVIEW_PACKAGE",
                "body",
                "[]",
                "{\"mrNote\":\"body\",\"diffNotes\":[],\"withheld\":[]}")));
        return inserted != null && inserted == 1 ? id : null;
    }
}
