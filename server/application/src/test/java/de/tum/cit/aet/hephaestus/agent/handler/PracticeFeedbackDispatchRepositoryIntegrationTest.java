package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWork;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWorkFixtures;
import de.tum.cit.aet.hephaestus.agent.context.providers.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.SourceArtifact;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
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
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkChanges;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

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

    @Autowired
    private FeedbackDispatchStateMachine stateMachine;

    @Autowired
    private PracticeFeedbackDeliveryPolicy deliveryPolicy;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private ReviewedWorkChanges reviewedWorkChanges;

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
        job.setConfigSnapshot(JsonNodeFactory.instance.objectNode());
        jobId = jobRepository.saveAndFlush(job).getId();
    }

    @Test
    void shouldReserveBeforeALaterMirrorWriteAndKeepTheWorkLockUntilTheReceiptCommits() throws Exception {
        var work = reviewedPullRequest();
        AgentJob job = jobRepository.findById(jobId).orElseThrow();
        UUID dispatchId = insertDispatch(workspace.getId(), jobId, "work-reservation-race");
        assertThat(claim(dispatchId, "reserving", Instant.now().plusSeconds(60)))
                .isEqualTo(1);
        var checked = new CountDownLatch(1);
        var writing = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var reservation = threads.submit(() -> stateMachine.reserve(
                    () -> {
                        var revision = deliveryPolicy.lockedReviewedRevision(job, null);
                        checked.countDown();
                        await(writing);
                        Integer holder = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                        Awaitility.await()
                                .atMost(Duration.ofSeconds(10))
                                .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity a WHERE ? = ANY(pg_blocking_pids(a.pid)))",
                                                Boolean.class,
                                                holder))
                                        .isTrue());
                        return PracticeFeedbackDeliveryPolicy.Decision.allowed(revision);
                    },
                    () -> dispatchRepository.beginWrite(dispatchId, workspace.getId(), "reserving")));
            var writer = threads.submit(() -> {
                await(checked);
                writing.countDown();
                transactions.executeWithoutResult(status -> {
                    assertThat(pullRequests.lockById(work.getId())).isPresent();
                    assertThat(jdbcTemplate.queryForObject(
                                    "SELECT write_started FROM feedback_dispatch WHERE id = ?",
                                    Boolean.class,
                                    dispatchId))
                            .isTrue();
                    jdbcTemplate.update("UPDATE issue SET body = 'New description' WHERE id = ?", work.getId());
                });
            });
            assertThat(reservation.get(30, TimeUnit.SECONDS))
                    .isEqualTo(FeedbackDispatchStateMachine.Reservation.RESERVED);
            writer.get(30, TimeUnit.SECONDS);
        } finally {
            checked.countDown();
            writing.countDown();
        }
        var revision = transactions.execute(status -> deliveryPolicy.lockedReviewedRevision(job, null));
        assertThat(revision).isEqualTo(PracticeFeedbackDeliveryPolicy.ReviewedRevision.CHANGED);
    }

    @Test
    void shouldReserveNothingAfterAMirrorWriteWinsTheWorkLock() throws Exception {
        var work = reviewedPullRequest();
        AgentJob job = jobRepository.findById(jobId).orElseThrow();
        UUID dispatchId = insertDispatch(workspace.getId(), jobId, "mirror-reservation-race");
        assertThat(claim(dispatchId, "reserving", Instant.now().plusSeconds(60)))
                .isEqualTo(1);
        var held = new CountDownLatch(1);
        var waiting = new CountDownLatch(1);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var writer = threads.submit(() -> transactions.executeWithoutResult(status -> {
                assertThat(pullRequests.lockById(work.getId())).isPresent();
                jdbcTemplate.update("UPDATE issue SET body = 'New description' WHERE id = ?", work.getId());
                held.countDown();
                await(waiting);
                Integer holder = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                Awaitility.await()
                        .atMost(Duration.ofSeconds(10))
                        .untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
                                        "SELECT EXISTS(SELECT 1 FROM pg_stat_activity a WHERE ? = ANY(pg_blocking_pids(a.pid)))",
                                        Boolean.class,
                                        holder))
                                .isTrue());
            }));
            var reservation = threads.submit(() -> {
                await(held);
                waiting.countDown();
                return stateMachine.reserve(
                        () -> PracticeFeedbackDeliveryPolicy.Decision.allowed(
                                deliveryPolicy.lockedReviewedRevision(job, null)),
                        () -> dispatchRepository.beginWrite(dispatchId, workspace.getId(), "reserving"));
            });
            writer.get(30, TimeUnit.SECONDS);
            assertThat(reservation.get(30, TimeUnit.SECONDS)).isEqualTo(FeedbackDispatchStateMachine.Reservation.STALE);
        } finally {
            held.countDown();
            waiting.countDown();
        }
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT write_started FROM feedback_dispatch WHERE id = ?", Boolean.class, dispatchId))
                .isFalse();
    }

    // Same head and words; only GitLab's diff base can differ. Its base is the pair GitLab recorded with the head.
    @ParameterizedTest
    @CsvSource({"equal, CURRENT", "different, CHANGED", "absent, UNKNOWN", "malformed, UNKNOWN"})
    void shouldCompareTheCapturedGitLabDiffBaseUnderTheWorkLock(
            String mirror, PracticeFeedbackDeliveryPolicy.ReviewedRevision expected) {
        String base =
                switch (mirror) {
                    case "equal" -> ReviewedWorkFixtures.BASE;
                    case "different" -> "d".repeat(40);
                    case "absent" -> null;
                    case "malformed" -> "not-a-commit";
                    default -> throw new IllegalArgumentException(mirror);
                };
        reviewedPullRequest(true, base);
        AgentJob job = jobRepository.findById(jobId).orElseThrow();

        var revision = transactions.execute(status -> deliveryPolicy.lockedReviewedRevision(job, null));
        assertThat(revision).isEqualTo(expected);
    }

    @Test
    void shouldKeepTheGitHubPolicyWhenOnlyTheTargetTipMoved() {
        reviewedPullRequest(false, "d".repeat(40));
        AgentJob job = jobRepository.findById(jobId).orElseThrow();

        var revision = transactions.execute(status -> deliveryPolicy.lockedReviewedRevision(job, null));
        assertThat(revision).isEqualTo(PracticeFeedbackDeliveryPolicy.ReviewedRevision.CURRENT);
    }

    @Test
    void shouldCompareTheGitLabBaseCommittedAfterTheSessionLoadedTheWork() throws Exception {
        var work = reviewedPullRequest(true, ReviewedWorkFixtures.BASE);
        AgentJob job = jobRepository.findById(jobId).orElseThrow();

        var revision = transactions.execute(status -> {
            // The session holds the work as loaded before another writer changed its base.
            assertThat(pullRequests.findById(work.getId()).orElseThrow().getBaseRefOid())
                    .isEqualTo(ReviewedWorkFixtures.BASE);
            try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
                threads.submit(() -> transactions.executeWithoutResult(other -> jdbcTemplate.update(
                                "UPDATE issue SET base_ref_oid = ? WHERE id = ?", "d".repeat(40), work.getId())))
                        .get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return deliveryPolicy.lockedReviewedRevision(job, null);
        });

        assertThat(revision).isEqualTo(PracticeFeedbackDeliveryPolicy.ReviewedRevision.CHANGED);
    }

    // Admission retires the staged files and their inventories; the reviewed identity and source facts stay.
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldReserveAgainstTheRetainedCaptureAfterAdmissionRetiresItsFiles(boolean gitLab) {
        var work = reviewedPullRequest(gitLab, ReviewedWorkFixtures.BASE);
        JsonNode before = snapshot();
        assertThat(before.path("manifest").path("artifacts")).isNotEmpty();

        JsonNode after = retire();

        assertThat(after.path("manifest").path("artifacts")).isEmpty();
        assertThat(after.path("manifest").path("refusals")).isEmpty();
        JsonNode sourcesBefore = before.path("manifest").path("sources");
        JsonNode sourcesAfter = after.path("manifest").path("sources");
        assertThat(sourcesAfter).hasSameSizeAs(sourcesBefore);
        for (int i = 0; i < sourcesAfter.size(); i++) {
            assertThat(sourcesAfter.get(i).path("artifacts")).isEmpty();
            assertThat(sourcesAfter.get(i).path("kind"))
                    .isEqualTo(sourcesBefore.get(i).path("kind"));
            assertThat(sourcesAfter.get(i).path("state"))
                    .isEqualTo(sourcesBefore.get(i).path("state"));
        }
        assertThat(after.path(ReviewedWork.SNAPSHOT_KEY)).isEqualTo(before.path(ReviewedWork.SNAPSHOT_KEY));
        assertThat(reviewedWorkChanges.deliverableCapture(workspace.getId(), jobId, work.getId()))
                .contains(new ReviewedWorkChanges.CapturedIdentity(
                        Objects.requireNonNull(work.getHeadRefOid()),
                        ReviewedWorkFixtures.BASE,
                        ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, work.getTitle(), work.getBody())));

        UUID dispatchId = claimedDispatch("retained-current-" + gitLab);
        assertThat(reserve(dispatchId)).isEqualTo(FeedbackDispatchStateMachine.Reservation.RESERVED);
        assertThat(writeStarted(dispatchId)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"head", "base", "body"})
    void shouldReserveNothingWhenTheWorkMovedAfterItsCaptureWasRetired(String moved) {
        var work = reviewedPullRequest(true, ReviewedWorkFixtures.BASE);
        retire();
        switch (moved) {
            case "head" ->
                jdbcTemplate.update("UPDATE issue SET head_ref_oid = ? WHERE id = ?", "c".repeat(40), work.getId());
            case "base" ->
                jdbcTemplate.update("UPDATE issue SET base_ref_oid = ? WHERE id = ?", "d".repeat(40), work.getId());
            case "body" -> jdbcTemplate.update("UPDATE issue SET body = 'New description' WHERE id = ?", work.getId());
            default -> throw new IllegalArgumentException(moved);
        }

        UUID dispatchId = claimedDispatch("retained-moved-" + moved);
        assertThat(reserve(dispatchId)).isEqualTo(FeedbackDispatchStateMachine.Reservation.STALE);
        assertThat(writeStarted(dispatchId)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing change", "malformed range"})
    void shouldReserveNothingWhenTheRetainedChangeIdentityCannotBeRead(String damage) {
        reviewedPullRequest(true, ReviewedWorkFixtures.BASE);
        ObjectNode retired = (ObjectNode) retire();
        ArrayNode sources = (ArrayNode) retired.path("manifest").path("sources");
        for (int i = 0; i < sources.size(); i++) {
            if (!PullRequestContentSource.DIFF
                    .value()
                    .equals(sources.get(i).path("kind").asString())) continue;
            if (damage.equals("missing change")) {
                sources.remove(i);
            } else {
                ((ObjectNode) sources.get(i).path("state").path("facts"))
                        .put("immutableIdentity", "base:" + "b".repeat(40));
            }
            break;
        }
        jdbcTemplate.update(
                "UPDATE agent_job SET evidence_snapshot = ?::jsonb WHERE id = ?", retired.toString(), jobId);

        UUID dispatchId = claimedDispatch("retained-unreadable-" + damage.replace(' ', '-'));
        assertThat(reserve(dispatchId)).isEqualTo(FeedbackDispatchStateMachine.Reservation.UNKNOWN);
        assertThat(writeStarted(dispatchId)).isFalse();
    }

    // A linked issue's material is compared through its captured file digest, which retirement removes.
    @Test
    void shouldNotConfirmLinkedMaterialOnceItsCapturedFileDigestIsRetired() {
        var mapper = JsonMapper.builder().build();
        var work = reviewedPullRequest(true, ReviewedWorkFixtures.BASE);
        var issue = new Issue();
        issue.setProvider(work.getProvider());
        issue.setRepository(work.getRepository());
        issue.setNativeId(70203L);
        issue.setNumber(18);
        issue.setState(Issue.State.CLOSED);
        issue.setTitle("Acceptance criteria");
        issue.setBody("- [x] Confirm repair");
        issue.setCreatedAt(Instant.parse("2026-10-01T08:00:00Z"));
        issue.setClosedAt(Instant.parse("2026-10-02T08:00:00Z"));
        long issueId = issueRepository.saveAndFlush(issue).getId();
        transactions.executeWithoutResult(status -> {
            PullRequest merged = pullRequests.findById(work.getId()).orElseThrow();
            merged.setState(Issue.State.MERGED);
            merged.setMerged(true);
            merged.replaceClosingIssues(Set.of(issueRepository.getReferenceById(issueId)));
            pullRequests.saveAndFlush(merged);
        });
        Issue closing = issueRepository.findById(issueId).orElseThrow();
        PullRequest mergedWork = pullRequests.findById(work.getId()).orElseThrow();
        byte[] text = LinkedWorkItemContentSource.asText(closing);
        var linked = new SourceCapture(
                LinkedWorkItemContentSource.KIND,
                new SourceCaptureState.Available(
                        SourceContentState.NON_EMPTY,
                        SourceCompleteness.COMPLETE,
                        new SourceCaptureFacts(Instant.now(), null, null, null)),
                List.of(new SourceArtifact(
                        "context/linked_work_items/18.md",
                        "text/markdown",
                        ProvenanceDigest.sha256Hex(text),
                        text.length)));
        AgentJob job = jobRepository.findById(jobId).orElseThrow();
        ObjectNode snapshot = (ObjectNode) Objects.requireNonNull(job.getEvidenceSnapshot());
        JobFolderIndex original = mapper.treeToValue(snapshot.path("manifest"), JobFolderIndex.class);
        var sources = new ArrayList<>(original.sources());
        sources.add(linked);
        snapshot.set(
                "manifest",
                mapper.valueToTree(new JobFolderIndex(
                        original.contractVersion(),
                        original.catalogDigest(),
                        original.artifactKind(),
                        original.capturedAt(),
                        sources)));
        job.setEvidenceSnapshot(snapshot);
        jobRepository.saveAndFlush(job);
        String revision = LinkedWorkItemContentSource.currentClosingMaterialKey(
                        workspace.getId(), mergedWork, List.of(closing))
                .orElseThrow()
                .revision()
                .value();

        assertThat(reviewedWorkChanges.linkedCaptureCurrent(workspace.getId(), jobId, work.getId(), revision))
                .isTrue();

        retire();

        assertThat(LinkedWorkItemContentSource.currentClosingMaterialKey(
                                workspace.getId(),
                                pullRequests.findById(work.getId()).orElseThrow(),
                                List.of(issueRepository.findById(issueId).orElseThrow()))
                        .orElseThrow()
                        .revision()
                        .value())
                .isEqualTo(revision);
        assertThat(reviewedWorkChanges.linkedCaptureCurrent(workspace.getId(), jobId, work.getId(), revision))
                .isFalse();
    }

    private JsonNode snapshot() {
        return Objects.requireNonNull(
                jobRepository.findById(jobId).orElseThrow().getEvidenceSnapshot());
    }

    /** Retires the completed job's staged files through the repository's own admission-time update. */
    private JsonNode retire() {
        AgentJob job = jobRepository.findById(jobId).orElseThrow();
        job.setWorkerId("retirement-worker");
        jobRepository.saveAndFlush(job);
        Integer retired = transactions.execute(status ->
                jobRepository.discardRetiredArtifactInventory(jobId, workspace.getId(), 0, "retirement-worker"));
        assertThat(retired).isEqualTo(1);
        return snapshot();
    }

    private UUID claimedDispatch(String destination) {
        UUID dispatchId = insertDispatch(workspace.getId(), jobId, destination);
        assertThat(claim(dispatchId, "reserving", Instant.now().plusSeconds(60)))
                .isEqualTo(1);
        return dispatchId;
    }

    /** The final locked check of the real policy bean, then the lease-guarded write reservation. */
    private FeedbackDispatchStateMachine.Reservation reserve(UUID dispatchId) {
        AgentJob job = jobRepository.findById(jobId).orElseThrow();
        return stateMachine.reserve(
                () -> PracticeFeedbackDeliveryPolicy.Decision.allowed(deliveryPolicy.lockedReviewedRevision(job, null)),
                () -> dispatchRepository.beginWrite(dispatchId, workspace.getId(), "reserving"));
    }

    private boolean writeStarted(UUID dispatchId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT write_started FROM feedback_dispatch WHERE id = ?", Boolean.class, dispatchId));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Race latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private PullRequest reviewedPullRequest() {
        return reviewedPullRequest(false, null);
    }

    /**
     * A captured pull request; its pinned change is {@code ReviewedWorkFixtures.BASE:head}, and {@code mirrorBase} is
     * what the mirror holds as its base now.
     */
    private PullRequest reviewedPullRequest(boolean gitLab, @Nullable String mirrorBase) {
        var mapper = JsonMapper.builder().build();
        var repository = new Repository();
        repository.setProvider(gitLab ? ensureGitLabProvider() : ensureGitHubProvider());
        repository.setNativeId(70201L);
        repository.setName("reservation");
        repository.setNameWithOwner("dispatch-org/reservation");
        repository = repositories.saveAndFlush(repository);
        var monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(repository.getNameWithOwner());
        monitors.saveAndFlush(monitor);
        var work = new PullRequest();
        work.setProvider(repository.getProvider());
        work.setNativeId(70202L);
        work.setRepository(repository);
        work.setNumber(1);
        work.setState(Issue.State.OPEN);
        work.setTitle("Original title");
        work.setBody("Original description");
        work.setHeadRefOid("b".repeat(40));
        if (mirrorBase != null) work.setBaseRefOid(mirrorBase);
        work = pullRequests.saveAndFlush(work);
        AgentJob job = jobRepository.findById(jobId).orElseThrow();
        job.setMetadata(mapper.createObjectNode()
                .put("pull_request_id", work.getId())
                .put("repository_id", repository.getId())
                .put("repository_full_name", repository.getNameWithOwner())
                .put("pr_number", work.getNumber())
                .put("commit_sha", work.getHeadRefOid()));
        var manifest = ReviewedWorkFixtures.pullRequestManifest(Instant.now(), work.getBody(), work.getHeadRefOid());
        var capture = ReviewedWork.captured(
                        mapper.writeValueAsBytes(manifest),
                        Map.of(
                                "context/metadata.json",
                                ReviewedWorkFixtures.metadata(
                                        mapper, work.getTitle(), work.getBody(), work.getHeadRefOid())),
                        work.getId(),
                        mapper)
                .orElseThrow();
        job.setEvidenceSnapshot(mapper.createObjectNode().set("manifest", mapper.valueToTree(manifest)));
        ((ObjectNode) Objects.requireNonNull(job.getEvidenceSnapshot()))
                .set(ReviewedWork.SNAPSHOT_KEY, mapper.valueToTree(capture));
        jobRepository.saveAndFlush(job);
        return work;
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
    void shouldStoreTheInlineReceiptBeforeARequestOnlyUnderTheLiveLeaseAndReadItBackAsWritten() {
        UUID dispatchId = insertDispatch(workspace.getId(), jobId, "inline-fence");
        String receipt = """
            [{"deliveryKey":"observation:a:0","path":"src/A.java","startLine":10,"disposition":"FAILED",\
            "writeMayHaveStarted":true},\
            {"deliveryKey":"observation:b:0","path":"src/B.java","startLine":20,"disposition":"FAILED",\
            "writeMayHaveStarted":false}]""";
        assertThat(beginInlineWrite(dispatchId, "first", receipt)).isZero();
        assertThat(claim(dispatchId, "first", Instant.now().plusSeconds(60))).isEqualTo(1);

        assertThat(beginInlineWrite(dispatchId, "first", receipt)).isEqualTo(1);
        assertThat(beginInlineWrite(dispatchId, "second", "[]")).isZero();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT inline_write_started FROM feedback_dispatch WHERE id = ?", Boolean.class, dispatchId))
                .isTrue();
        assertThat(jdbcTemplate.queryForList("""
                        SELECT element->>'writeMayHaveStarted' FROM feedback_dispatch,
                               jsonb_array_elements(delivered_placements) AS element
                         WHERE id = ? ORDER BY element->>'deliveryKey'
                        """, String.class, dispatchId)).containsExactly("true", "false");
        jdbcTemplate.update(
                "UPDATE feedback_dispatch SET lease_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                dispatchId);
        assertThat(beginInlineWrite(dispatchId, "first", "[]")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT jsonb_array_length(delivered_placements) FROM feedback_dispatch WHERE id = ?",
                        Integer.class,
                        dispatchId))
                .isEqualTo(2);
    }

    @Test
    void shouldReopenAnUnsentWriteOnlyForTheLeaseThatClosedIt() {
        UUID dispatchId = insertDispatch(workspace.getId(), jobId, "unsent-write");
        assertThat(releaseUnsentWrite(dispatchId, "first")).isZero();
        assertThat(claim(dispatchId, "first", Instant.now().plusSeconds(60))).isEqualTo(1);
        assertThat(beginWrite(dispatchId, "first")).isEqualTo(1);

        assertThat(releaseUnsentWrite(dispatchId, "second")).isZero();
        assertThat(releaseUnsentWrite(dispatchId, "first")).isEqualTo(1);
        assertThat(releaseUnsentWrite(dispatchId, "first")).isZero();
        assertThat(beginWrite(dispatchId, "first")).isEqualTo(1);

        jdbcTemplate.update(
                "UPDATE feedback_dispatch SET lease_expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                dispatchId);
        assertThat(releaseUnsentWrite(dispatchId, "first")).isZero();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT write_started FROM feedback_dispatch WHERE id = ?", Boolean.class, dispatchId))
                .isTrue();
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
                "https://github.com/owner/repo/pull/42#issuecomment-1",
                "inline delivery incomplete",
                null,
                "[]",
                Instant.now())));

        assertThat(finished).isEqualTo(1);
        assertThat(dispatchRepository.findByIdAndWorkspaceId(dispatchId, workspace.getId()))
                .hasValueSatisfying(dispatch -> {
                    assertThat(dispatch.getState()).isEqualTo(FeedbackDispatchState.UNCERTAIN);
                    assertThat(dispatch.getDeliveredExternalRef()).isEqualTo("summary-42");
                    assertThat(dispatch.getDeliveredExternalUrl())
                            .isEqualTo("https://github.com/owner/repo/pull/42#issuecomment-1");
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
        int loaded = Objects.requireNonNull(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM feedback_dispatch WHERE id = ?", Integer.class, dispatchId));
        return transactions.execute(
                status -> dispatchRepository.claim(dispatchId, workspace.getId(), owner, leaseUntil, 8, loaded));
    }

    private int beginWrite(UUID dispatchId, String owner) {
        return transactions.execute(status -> dispatchRepository.beginWrite(dispatchId, workspace.getId(), owner));
    }

    private int beginInlineWrite(UUID dispatchId, String owner, String placements) {
        return transactions.execute(
                status -> dispatchRepository.beginInlineWrite(dispatchId, workspace.getId(), owner, placements));
    }

    private int releaseUnsentWrite(UUID dispatchId, String owner) {
        return transactions.execute(
                status -> dispatchRepository.releaseUnsentWrite(dispatchId, workspace.getId(), owner));
    }

    private int claimProjection(UUID dispatchId, String owner) {
        return transactions.execute(status -> dispatchRepository.claimProjection(
                dispatchId, workspace.getId(), owner, Instant.now().plusSeconds(60)));
    }

    private int markProjected(UUID dispatchId, String owner) {
        return transactions.execute(status -> dispatchRepository.markProjected(dispatchId, workspace.getId(), owner));
    }

    private UUID insertDispatch(long workspaceId, UUID owningJobId, String key) {
        return Objects.requireNonNull(
                tryInsertDispatch(workspaceId, owningJobId, key), "the insert this test builds on must land");
    }

    private UUID saveJob(Workspace owningWorkspace, AgentJobType type, ArtifactKind kind) {
        AgentJob job = new AgentJob();
        job.setWorkspace(owningWorkspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(type);
        job.setArtifactKind(kind);
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setConfigSnapshot(JsonNodeFactory.instance.objectNode());
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
                        .checks(JsonNodeFactory.instance.arrayNode())
                        .facts(JsonNodeFactory.instance.objectNode())
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
