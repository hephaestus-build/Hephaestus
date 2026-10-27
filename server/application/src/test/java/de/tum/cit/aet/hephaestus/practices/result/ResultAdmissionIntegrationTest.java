package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventRecorded;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemEventService;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.itemevent.ItemEventLedgerProperties;
import de.tum.cit.aet.hephaestus.integration.core.itemevent.ItemEventRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.CandidateRefusal;
import de.tum.cit.aet.hephaestus.integration.core.spi.FoundBy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventInput;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.review.AbstractResultAdmissionIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRequestRejected;
import de.tum.cit.aet.hephaestus.practices.spi.AdmissionReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultQuery;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FrozenModel;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentStore;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCaptureDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompleted;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCompletion;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewPracticeStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import de.tum.cit.aet.hephaestus.practices.spi.WithheldItem;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class ResultAdmissionIntegrationTest extends AbstractResultAdmissionIntegrationTest {
    @Autowired
    ApplicationEvents applicationEvents;

    @Autowired
    JsonMapper json;

    @Autowired
    WebTestClient http;

    @Autowired
    ItemEventLedgerProperties eventProperties;

    @Autowired
    ItemEventService itemEvents;

    @Autowired
    ItemEventRepository eventRows;

    @Autowired
    ItemIndexService itemIndex;

    @Autowired
    ApplicationEventPublisher publishedEvents;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    ResultStore store;

    @Autowired
    ResultRepository results;

    @Autowired
    ResultCurrentness currentness;

    @Autowired
    PracticeRepository practices;

    @Autowired
    AdmittedResultQuery proof;

    @Autowired
    ReviewRepository reviews;

    @Autowired
    ReviewService service;

    @Autowired
    ReviewAssignmentStore assignments;

    @Autowired
    ReviewPracticeRepository pinnedPractices;

    @Autowired
    ReviewSnapshotQuery snapshots;

    @Autowired
    ReviewAssignmentQuery executable;

    ReviewIntegrationData.Scenario scenario;

    long scenarioAccountId;

    @BeforeEach
    void setup() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        scenarioAccountId = Objects.requireNonNull(scenario.account().getId());
        when(custody.find(anyLong(), anyList()))
                .thenAnswer(call -> new HistoryCustody(true, false, List.of(), List.of(), call.getArgument(1)));
    }

    void running(ReviewAdmissionData.Pending p, int attempt) {
        admissions.running(p, attempt);
        when(runtime.activeJobIds(p.review().getWorkspaceId(), p.review().getId()))
                .thenReturn(Set.of(p.jobId()));
        when(runtime.unsettledJobIds(p.review().getWorkspaceId(), p.review().getId()))
                .thenReturn(Set.of());
        when(runtime.runtime(p.review().getWorkspaceId(), p.review().getId()))
                .thenReturn(Optional.of(new ReviewRuntimeSnapshot(List.of(new ReviewJobSnapshot(
                        p.jobId(),
                        ReviewJobStatus.RUNNING,
                        attempt,
                        ProcessingStatus.PENDING,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        "test-trace")))));
    }

    AdmissionReceipt admit(ReviewAdmissionData.Pending p, ResultOutcome outcome) {
        running(p, 1);
        return store.admit(
                p.review().getWorkspaceId(),
                p.review().getId(),
                p.jobId(),
                1,
                List.of(admissions.candidate(p, outcome)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"scm.repository", "scm.file", "slack.thread", "outline.document"})
    void shouldKeepOtherSourceContractsWithoutMaterializingTheirCurrentSource(String kind) {
        var item = itemIndex.upsert(new ItemRegistration(
                scenario.workspace().getId(),
                new ItemKind(kind),
                UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE,
                null,
                null));
        doThrow(new AssertionError("Current source materialization is outside this work contract"))
                .when(productItems)
                .current(item);
        new TransactionTemplate(transactions)
                .executeWithoutResult(ignored -> assertThat(currentness.prepareClaim(
                                item.workspaceId(), item.itemId(), "frozen-scan", Instant.now()))
                        .isNull());
    }

    @Test
    void shouldRefuseCurrentnessForAnItemInAnotherWorkspaceWithoutChangingEitherClaim() {
        var at = Instant.now();
        var current = data.result(scenario, ResultOutcome.MET, at);
        var other = data.scenario();
        var untouched = data.result(other, ResultOutcome.MET, at);
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
                        .executeWithoutResult(ignored -> currentness.prepareClaim(
                                other.workspace().getId(), scenario.item().getId(), "foreign", Instant.now())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(results.findById(current.getId()).orElseThrow().getEndedAt()).isNull();
        assertThat(results.findById(untouched.getId()).orElseThrow().getEndedAt())
                .isNull();
    }

    @Test
    void recordsOneResultAndAllocatedChildProofForItsExactAttempt() {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        var receipt = admit(pending, ResultOutcome.NOT_MET);
        assertThat(receipt.resultIds()).hasSize(1);
        var snapshots = proof.forAttempt(
                pending.review().getWorkspaceId(), pending.review().getId(), pending.jobId(), 1);
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.getFirst().id()).isEqualTo(receipt.resultIds().getFirst());
        assertThat(snapshots.getFirst().problems()).hasSize(1);
        assertThat(snapshots.getFirst().problems().getFirst().id()).isNotNull();
        assertThat(proof.forAttempt(
                        pending.review().getWorkspaceId(), pending.review().getId(), pending.jobId(), 2))
                .isEmpty();
        assertThat(proof.forAttempt(
                        pending.review().getWorkspaceId(), pending.review().getId(), UUID.randomUUID(), 1))
                .isEmpty();
        assertThat(proof.forAttempt(
                        pending.review().getWorkspaceId() + 1, pending.review().getId(), pending.jobId(), 1))
                .isEmpty();
    }

    @Test
    void sameBodyReturnsSameIdsAndDifferentBodyIsConflict() {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        var first = admit(pending, ResultOutcome.NOT_MET);
        var repeated = admit(pending, ResultOutcome.NOT_MET);
        assertThat(repeated).isEqualTo(first);
        assertThatThrownBy(() -> admit(pending, ResultOutcome.MET)).isInstanceOf(ResponseStatusException.class);
        assertThat(results.findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(
                        pending.review().getWorkspaceId(), pending.review().getId()))
                .hasSize(1);
    }

    @Test
    void olderLateAdmissionIsEndedOutrankedWithoutReplacingTheNewerClaim() {
        var older = admissions.pending(scenario, Instant.now().minusSeconds(120), scenarioAccountId);
        var newer = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        var current = admit(newer, ResultOutcome.MET).resultIds().getFirst();
        var late = admit(older, ResultOutcome.NOT_MET).resultIds().getFirst();
        assertThat(results.findByIdAndWorkspaceId(late, scenario.workspace().getId())
                        .orElseThrow()
                        .getEndReason())
                .isEqualTo(ResultEndReason.OUTRANKED);
        assertThat(results.findCurrentClaim(
                                scenario.workspace().getId(),
                                scenario.practice().getId(),
                                scenario.item().getId(),
                                scenarioAccountId)
                        .orElseThrow()
                        .getId())
                .isEqualTo(current);
    }

    @Test
    void markedCurrentClaimStillOccupiesUntilANewerAdmissionReplacesIt() {
        var first = admissions.pending(scenario, Instant.now().minusSeconds(120), scenarioAccountId);
        var current = admit(first, ResultOutcome.NOT_MET).resultIds().getFirst();
        store.mark(
                scenario.workspace().getId(),
                current,
                ResultMarkKind.INCORRECT,
                scenarioAccountId,
                "The claim is incorrect");
        var next = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        var replacement = admit(next, ResultOutcome.MET).resultIds().getFirst();
        assertThat(results.findById(current).orElseThrow().getEndReason()).isEqualTo(ResultEndReason.REPLACED);
        assertThat(results.findById(replacement).orElseThrow().getEndedAt()).isNull();
    }

    @Test
    void cancellationFenceRejectsLaterAdmissionAndKeepsAlreadyAdmittedProof() {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        var saved = admit(pending, ResultOutcome.NOT_MET).resultIds().getFirst();
        service.cancel(scenario.workspace().getId(), pending.review().getId(), "CANCELLED_BY_ADMIN");
        assertThatThrownBy(() -> admit(pending, ResultOutcome.NOT_MET)).isInstanceOf(ResponseStatusException.class);
        assertThat(results.findById(saved)).isPresent();
        assertThat(reviews.findById(pending.review().getId()).orElseThrow().getStatus())
                .isEqualTo(ReviewStatus.RUNNING);
    }

    @ParameterizedTest
    @EnumSource(ResultOutcome.class)
    void shouldAdmitTheOutcomeWithItsRequiredProblemShape(ResultOutcome outcome) {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        var receipt = admit(pending, outcome);
        assertThat(receipt.withheldItems()).isEmpty();
        assertThat(receipt.resultIds()).hasSize(1);
        var result = results.findById(receipt.resultIds().getFirst()).orElseThrow();
        assertThat(result.getOutcome()).isEqualTo(outcome);
        assertThat(result.getSeverity()).isEqualTo(outcome == ResultOutcome.NOT_MET ? Severity.MAJOR : null);
    }

    @ParameterizedTest
    @EnumSource(ResultOutcome.class)
    void shouldWithholdAnOutcomeWhoseProblemShapeDoesNotMatch(ResultOutcome outcome) {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        running(pending, 1);
        var candidate = admissions.candidate(pending, outcome);
        var problems = outcome == ResultOutcome.NOT_MET
                ? List.<ProblemCandidate>of()
                : admissions.candidate(pending, ResultOutcome.NOT_MET).problems();
        var invalid = new ResultCandidate(
                candidate.practiceId(),
                candidate.practiceVersionId(),
                candidate.itemId(),
                candidate.itemVersion(),
                candidate.developerId(),
                candidate.outcome(),
                candidate.summary(),
                candidate.explanation(),
                candidate.evidence(),
                candidate.example(),
                candidate.coverage(),
                problems);
        var receipt = store.admit(
                scenario.workspace().getId(), pending.review().getId(), pending.jobId(), 1, List.of(invalid));
        assertThat(receipt.resultIds()).isEmpty();
        assertThat(receipt.withheldItems()).hasSize(1);
        assertThat(receipt.withheldItems().getFirst().reason())
                .isEqualTo(CandidateRefusal.OUTCOME_PROBLEM_MISMATCH.wireValue());
        assertThat(jdbc.sql("SELECT COUNT(*) FROM result WHERE workspace_id=:ws AND review_id=:review")
                        .param("ws", scenario.workspace().getId())
                        .param("review", pending.review().getId())
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    @Test
    void shouldWaitForTerminalProcessingBeforeClosingACancelledReview() {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        long workspaceId = scenario.workspace().getId();
        UUID reviewId = pending.review().getId();
        jdbc.sql("UPDATE review_job SET"
                        + " status='FAILED',processing_status='PENDING',started_at=now(),completed_at=now() WHERE"
                        + " workspace_id=:ws AND id=:id")
                .param("ws", workspaceId)
                .param("id", pending.jobId())
                .update();
        when(runtime.activeJobIds(workspaceId, reviewId)).thenReturn(Set.of());
        when(runtime.unsettledJobIds(workspaceId, reviewId)).thenReturn(Set.of(pending.jobId()));
        var completion = new ReviewCompletion(
                ReviewStatus.COMPLETE,
                null,
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                pending.review().getOrderedAt());

        assertThatThrownBy(() -> service.complete(workspaceId, reviewId, completion))
                .isInstanceOf(ReviewRequestRejected.class);
        assertThat(service.cancel(workspaceId, reviewId, "CANCELLED_BY_ADMIN")).isTrue();
        assertThat(reviews.findById(reviewId).orElseThrow().getCompletedAt()).isNull();
        assertThat(reviews.findById(reviewId).orElseThrow().getStatus()).isEqualTo(ReviewStatus.RUNNING);

        var assignment = new ReviewAssignmentId(
                reviewId, scenario.practice().getId(), scenario.item().getId());
        assertThatThrownBy(() -> assignments.withhold(
                        workspaceId,
                        assignment,
                        CandidateRefusal.QUOTE_UNVERIFIED,
                        "A cancelled invocation cannot admit new refusal output."))
                .isInstanceOf(IllegalStateException.class);
        assignments.fail(
                workspaceId,
                assignment,
                ReviewStopReason.CANCELLED,
                "The invoked execution stopped before admitting a result.");
        assertThat(jdbc.sql("SELECT state FROM review_item WHERE workspace_id=:ws AND review_id=:review")
                        .param("ws", workspaceId)
                        .param("review", reviewId)
                        .query(String.class)
                        .single())
                .isEqualTo("WITHHELD");
        assertThat(jdbc.sql("SELECT COUNT(*) FROM result WHERE workspace_id=:ws AND review_id=:review")
                        .param("ws", workspaceId)
                        .param("review", reviewId)
                        .query(Integer.class)
                        .single())
                .isZero();

        jdbc.sql("UPDATE review_job SET processing_status='DONE' WHERE workspace_id=:ws AND id=:id")
                .param("ws", workspaceId)
                .param("id", pending.jobId())
                .update();
        when(runtime.unsettledJobIds(workspaceId, reviewId)).thenReturn(Set.of());
        service.complete(workspaceId, reviewId, completion);
        assertThat(reviews.findById(reviewId).orElseThrow().getStatus()).isEqualTo(ReviewStatus.CANCELLED);
        assertThat(reviews.findById(reviewId).orElseThrow().getCompletedAt()).isEqualTo(completion.completedAt());
    }

    @Test
    void shouldKeepItemWithholdingSeparateFromResults() {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        var assignment = new ReviewAssignmentId(
                pending.review().getId(),
                scenario.practice().getId(),
                scenario.item().getId());
        assertThat(executable.executable(
                        scenario.workspace().getId(),
                        pending.review().getId(),
                        scenario.item().getId()))
                .containsExactly(assignment);
        assignments.withhold(
                scenario.workspace().getId(),
                assignment,
                CandidateRefusal.QUOTE_UNVERIFIED,
                "The captured text cannot verify this quote.");
        assertThat(executable.executable(
                        scenario.workspace().getId(),
                        pending.review().getId(),
                        scenario.item().getId()))
                .isEmpty();
        var receipt = admit(pending, ResultOutcome.NOT_MET);
        assertThat(receipt.resultIds()).isEmpty();
        assertThat(receipt.withheldItems())
                .containsExactly(new WithheldItem(
                        scenario.practice().getId(), scenario.item().getId(), "quote_unverified"));
        var pinned = pinnedPractices
                .findByWorkspaceIdAndReviewIdAndPracticeId(
                        scenario.workspace().getId(),
                        pending.review().getId(),
                        scenario.practice().getId())
                .orElseThrow();
        assertThat(pinned.getStatus()).isEqualTo(ReviewPracticeStatus.WITHHELD);
        assertThat(pinned.getReason()).isEqualTo("quote_unverified");
        assertThat(results.findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(
                        scenario.workspace().getId(), pending.review().getId()))
                .isEmpty();
        assertThat(reviews.findById(pending.review().getId()).orElseThrow().getAdmittedAt())
                .isNull();
    }

    @Test
    void shouldKeepTheFirstPublicAcceptanceOnTheProductReview() {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        admit(pending, ResultOutcome.MET);
        var acceptedAt = Instant.now();
        assertThat(assignments.publicReviewed(
                        scenario.workspace().getId(), pending.review().getId(), pending.jobId(), 1, acceptedAt))
                .isTrue();
        var recorded = snapshots
                .publicReviewedAt(scenario.workspace().getId(), pending.review().getId())
                .orElseThrow();
        assertThat(assignments.publicReviewed(
                        scenario.workspace().getId(),
                        pending.review().getId(),
                        pending.jobId(),
                        1,
                        acceptedAt.plusSeconds(60)))
                .isFalse();
        admissions.removeRuntime(pending);
        assertThat(snapshots.publicReviewedAt(
                        scenario.workspace().getId(), pending.review().getId()))
                .contains(recorded);
        assertThat(snapshots.publicReviewedAt(
                        scenario.workspace().getId() + 1, pending.review().getId()))
                .isEmpty();
        assertThat(reviews.findById(pending.review().getId()).orElseThrow().getCompletedAt())
                .isNull();
    }

    @Test
    void shouldRecordCaptureFailureWithoutCreatingAMissingEvidenceResult() {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        running(pending, 1);
        assignments.capture(
                scenario.workspace().getId(),
                pending.review().getId(),
                pending.jobId(),
                1,
                List.of(new ReviewCaptureDecision(
                        scenario.practice().getId(),
                        ReviewPracticeStatus.FAILED,
                        "CAPTURE_FAILED",
                        JsonNodeFactory.instance.objectNode().put("status", "FAILED"))));
        var receipt = admit(pending, ResultOutcome.COULD_NOT_DETERMINE);
        assertThat(receipt.resultIds()).isEmpty();
        assertThat(receipt.withheldItems())
                .containsExactly(new WithheldItem(
                        scenario.practice().getId(), scenario.item().getId(), "CAPTURE_FAILED"));
        var pinned = pinnedPractices
                .findByWorkspaceIdAndReviewIdAndPracticeId(
                        scenario.workspace().getId(),
                        pending.review().getId(),
                        scenario.practice().getId())
                .orElseThrow();
        assertThat(pinned.getStatus()).isEqualTo(ReviewPracticeStatus.FAILED);
        var capture = pinned.getCaptureDecision();
        assertThat(capture).isNotNull();
        assertThat(capture.path("status").asString()).isEqualTo("FAILED");
        assertThat(results.findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(
                        scenario.workspace().getId(), pending.review().getId()))
                .isEmpty();
    }

    @Test
    void shouldRetainMissingRoleWithholdingWithoutGrantingAnonymousModelAuthority() {
        var workspace = scenario.workspace().getId();
        var ref = productItems.require(workspace, scenario.item().getId());
        var pinned = ItemFixtures.snapshot(ref, Map.of("state", "OPEN", "draft", "false"))
                .version(new ItemVersion("selected-version", null))
                .integration(new IntegrationRef(IntegrationKind.GITHUB, workspace, "https://github.com", null))
                .label(ItemFixtures.label()
                        .itemId(ref.itemId())
                        .title("Selected pull request")
                        .noun("pull request")
                        .number(1)
                        .integration(IntegrationKind.GITHUB)
                        .build())
                .build();
        doReturn(pinned).when(productItems).pin(eq(ref), isNull());
        when(budget.canRequest(workspace, ReviewKind.ITEM, null)).thenReturn(true);
        when(models.models(workspace, List.of()))
                .thenReturn(List.of(
                        new BoundModelSlot("chat", ModelKind.CHAT, null),
                        new BoundModelSlot("decision", ModelKind.DECISION, null),
                        new BoundModelSlot("embedding", ModelKind.EMBEDDING, null),
                        new BoundModelSlot("reranking", ModelKind.RERANKING, null),
                        new BoundModelSlot("review", ModelKind.CHAT, null)));
        var id = service.request(new ReviewRequest(
                workspace,
                ref.itemId(),
                ReviewKind.ITEM,
                null,
                ReviewOrigin.REQUEST,
                null,
                scenarioAccountId,
                null,
                null,
                null,
                null,
                List.of(scenario.practice().getId()),
                false));
        assertThat(reviews.findById(id).orElseThrow().getStatus()).isEqualTo(ReviewStatus.PARTIAL);
        var held = pinnedPractices
                .findByWorkspaceIdAndReviewIdAndPracticeId(
                        workspace, id, scenario.practice().getId())
                .orElseThrow();
        assertThat(held.getStatus()).isEqualTo(ReviewPracticeStatus.WITHHELD);
        assertThat(held.getReason()).isEqualTo("ROLE_UNAVAILABLE");
        assertThat(executable.executable(workspace, id, ref.itemId())).isEmpty();
        assertThat(results.findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(workspace, id))
                .isEmpty();
    }

    @Test
    void legitimateNullDeveloperIsKeptRatherThanInvented() {
        var original = DefinitionTestFixtures.definition();
        scenario = data.scenario(new PracticeDefinition(
                original.name(),
                original.reviewOn(),
                original.appliesWhen(),
                null,
                original.reads(),
                original.reviewedBy(),
                original.criteria(),
                original.script(),
                original.scan(),
                original.lineNotes(),
                original.guidance()));
        var pending = admissions.pending(scenario, Instant.now(), null);
        var id = admit(pending, ResultOutcome.MET).resultIds().getFirst();
        assertThat(results.findById(id).orElseThrow().getDeveloperId()).isNull();
    }

    @Test
    void zeroAttemptIsAnExactValidExecutionAttempt() {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        running(pending, 0);
        var receipt = store.admit(
                scenario.workspace().getId(),
                pending.review().getId(),
                pending.jobId(),
                0,
                List.of(admissions.candidate(pending, ResultOutcome.MET)));
        assertThat(proof.forAttempt(
                        scenario.workspace().getId(), pending.review().getId(), pending.jobId(), 0))
                .extracting(AdmittedResultSnapshot::id)
                .containsExactlyElementsOf(receipt.resultIds());
    }

    @Test
    void feedbackModeOffStillRecordsTheResult() {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        scenario.practice().setFeedbackMode(FeedbackMode.OFF);
        var id = admit(pending, ResultOutcome.NOT_MET).resultIds().getFirst();
        assertThat(results.findById(id).orElseThrow().getOutcome()).isEqualTo(ResultOutcome.NOT_MET);
    }

    private ItemEventInput eventInput() {
        var definition = DefinitionTestFixtures.definition();
        scenario = data.scenario(new PracticeDefinition(
                definition.name(),
                definition.reviewOn(),
                definition.appliesWhen(),
                null,
                definition.reads(),
                definition.reviewedBy(),
                definition.criteria(),
                definition.script(),
                definition.scan(),
                definition.lineNotes(),
                definition.guidance()));
        long workspace = scenario.workspace().getId();
        var ref = productItems.require(workspace, scenario.item().getId());
        var version = new ItemVersion("event-version", null);
        var snapshot = ItemFixtures.snapshot(ref, Map.of())
                .version(version)
                .integration(new IntegrationRef(IntegrationKind.GITHUB, workspace, "https://github.com", null))
                .label(ItemFixtures.label()
                        .itemId(ref.itemId())
                        .title("Event pull request")
                        .noun("pull request")
                        .number(1)
                        .integration(IntegrationKind.GITHUB)
                        .build())
                .build();
        doReturn(snapshot).when(productItems).pin(eq(ref), eq(version));
        // Event admission pins the occasion; this stub stands for a source that retained it.
        doReturn(snapshot).when(productItems).pinOccasion(eq(ref), eq(version), any(UUID.class));
        doReturn(Optional.of(snapshot)).when(productItems).current(ref);
        doReturn(new ProductItemQuery.CurrentWorkVersion.Known(version.value()))
                .when(productItems)
                .currentWorkVersion(workspace, ref.itemId());
        when(budget.canRequest(workspace, ReviewKind.ITEM, null)).thenReturn(true);
        when(models.models(eq(workspace), anyList()))
                .thenReturn(List.of(
                        new BoundModelSlot("chat", ModelKind.CHAT, null),
                        new BoundModelSlot("decision", ModelKind.DECISION, null),
                        new BoundModelSlot("embedding", ModelKind.EMBEDDING, null),
                        new BoundModelSlot("reranking", ModelKind.RERANKING, null),
                        new BoundModelSlot(
                                "review",
                                ModelKind.CHAT,
                                new FrozenModel("instance:1", "OPENAI_COMPLETIONS", "test-model", null, Map.of()))));
        return new ItemEventInput(
                workspace,
                ref.itemId(),
                new ItemEventName("scm.pull_request.opened"),
                version.value(),
                Instant.now().minusSeconds(30),
                FoundBy.WEBHOOK,
                null,
                null);
    }

    @Test
    void shouldEndCurrentResultsWhenAnScmChangeEventIsRecordedWithoutEndingLaterResultsOnReplay() {
        var input = eventInput();
        var at = input.occurredAt().truncatedTo(ChronoUnit.MICROS);
        var current = data.result(scenario, ResultOutcome.NOT_MET, at.minusSeconds(30));
        var other = data.scenario();
        var untouched = data.result(other, ResultOutcome.MET, at.minusSeconds(30));
        var changed = new ItemEventInput(
                input.workspaceId(),
                input.itemId(),
                new ItemEventName("scm.pull_request.edited"),
                Objects.requireNonNull(input.itemVersion()),
                at,
                input.foundBy(),
                input.actorUserId(),
                input.requestedById());
        var reconciledAfter = Instant.now();
        var eventId = itemIndex.record(changed);
        var ended = results.findById(current.getId()).orElseThrow();
        assertThat(ended.getEndReason()).isEqualTo(ResultEndReason.WORK_CHANGED);
        assertThat(ended.getEndedAt()).isAfterOrEqualTo(reconciledAfter);
        assertThat(results.findById(untouched.getId()).orElseThrow().getEndedAt())
                .isNull();
        var later = data.result(
                scenario,
                ResultOutcome.MET,
                at.plusSeconds(1),
                at.plusSeconds(1),
                Objects.requireNonNull(input.itemVersion()));
        new TransactionTemplate(transactions)
                .executeWithoutResult(ignored -> publishedEvents.publishEvent(
                        new ItemEventRecorded(changed.workspaceId(), eventId, changed.itemId())));
        assertThat(results.findById(later.getId()).orElseThrow().getEndedAt()).isNull();
        assertThat(results.findById(current.getId()).orElseThrow().getEndedAt()).isEqualTo(ended.getEndedAt());
    }

    @Test
    void shouldKeepANewerResultCurrentWhenAnOlderRecordedScmEventIsDelivered() {
        var input = eventInput();
        var orderedAt = input.occurredAt().plusSeconds(1);
        var current = data.result(
                scenario, ResultOutcome.MET, orderedAt, orderedAt, Objects.requireNonNull(input.itemVersion()));
        var eventId = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            eventRows.insertIfAbsent(
                    eventId,
                    input.workspaceId(),
                    input.itemId(),
                    input.name().value(),
                    Objects.requireNonNull(input.itemVersion()),
                    null,
                    input.occurredAt(),
                    input.foundBy().name(),
                    input.actorUserId(),
                    input.requestedById(),
                    Instant.now(),
                    null,
                    null);
            publishedEvents.publishEvent(new ItemEventRecorded(input.workspaceId(), eventId, input.itemId()));
        });
        assertThat(eventRows
                        .findByIdAndWorkspaceId(eventId, input.workspaceId())
                        .orElseThrow()
                        .getState())
                .isEqualTo(ItemEventState.STARTED);
        assertThat(results.findById(current.getId()).orElseThrow().getEndedAt()).isNull();
    }

    @Test
    void shouldEndCurrentResultsAsRemovedWhenAnScmDeletionIsRecorded() {
        var kind = new ItemKind("scm.issue");
        scenario = data.scenario(
                kind,
                DefinitionTestFixtures.definition(kind, "Explain the issue", "Explain the requested work.", null));
        var at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var current = data.result(scenario, ResultOutcome.MET, at.minusSeconds(30));
        var workspace = scenario.workspace().getId();
        var item = scenario.item().getId();
        var eventId = Objects.requireNonNull(new TransactionTemplate(transactions).execute(ignored -> {
            itemIndex.remove(workspace, item, at);
            return itemIndex.record(new ItemEventInput(
                    workspace,
                    item,
                    new ItemEventName("scm.issue.deleted"),
                    Objects.requireNonNull(current.getItemVersion()),
                    at,
                    FoundBy.WEBHOOK,
                    null,
                    null));
        }));
        var ended = results.findById(current.getId()).orElseThrow();
        assertThat(ended.getEndReason()).isEqualTo(ResultEndReason.REMOVED);
        assertThat(ended.getEndedAt()).isAfterOrEqualTo(at);
        var event = eventRows.findByIdAndWorkspaceId(eventId, workspace).orElseThrow();
        assertThat(event.getState()).isEqualTo(ItemEventState.WITHHELD);
        assertThat(event.getReason()).isEqualTo(ReviewRequestReason.ITEM_GONE);
        assertThat(reviews.findByWorkspaceIdAndEventId(workspace, eventId)).isEmpty();
    }

    @Test
    void shouldStartExactlyOneEventReviewWhenAnItemEventIsRecordedAndReplayed() {
        var input = eventInput();
        var workspace = input.workspaceId();
        var ref = productItems.require(workspace, input.itemId());
        var eventId = itemEvents.record(input);
        var review = reviews.findByWorkspaceIdAndEventId(workspace, eventId).orElseThrow();
        assertThat(review.getOrigin()).isEqualTo(ReviewOrigin.EVENT);
        assertThat(review.getEventId()).isEqualTo(eventId);
        assertThat(review.getItemVersion()).isEqualTo("event-version");
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.QUEUED);
        assertThat(eventRows
                        .findByIdAndWorkspaceId(eventId, workspace)
                        .orElseThrow()
                        .getState())
                .isEqualTo(ItemEventState.STARTED);
        assertThat(itemEvents.record(input)).isEqualTo(eventId);
        new TransactionTemplate(transactions)
                .executeWithoutResult(ignored ->
                        publishedEvents.publishEvent(new ItemEventRecorded(workspace, eventId, ref.itemId())));
        assertThat(reviews.findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(workspace, ref.itemId()))
                .extracting(r -> r.getId())
                .containsExactly(review.getId());
    }

    @Test
    void shouldPreserveTheFrozenMethodAfterPersistingAnEventReview() {
        var input = eventInput();
        var model = new FrozenModel(
                "instance:1",
                "openai_completions",
                "test-model",
                null,
                Map.of(
                        "directModelTokensPerAttempt",
                        JsonNodeFactory.instance.numberNode(200000L),
                        "directModelSearchUnitsPerAttempt",
                        JsonNodeFactory.instance.numberNode(1000L)));
        var slots = List.of(
                new BoundModelSlot("chat", ModelKind.CHAT, model),
                new BoundModelSlot("decision", ModelKind.DECISION, null),
                new BoundModelSlot("embedding", ModelKind.EMBEDDING, null),
                new BoundModelSlot("reranking", ModelKind.RERANKING, null),
                new BoundModelSlot("review", ModelKind.CHAT, model));
        when(models.models(eq(input.workspaceId()), anyList())).thenReturn(slots);
        var expected = new ReviewMethod(1, reviews.modelDigest(json.writeValueAsString(slots)), slots);
        var eventId = itemEvents.record(input);
        var persisted = reviews.findByWorkspaceIdAndEventId(input.workspaceId(), eventId)
                .orElseThrow();
        var recordedMethod = pinnedPractices
                .findByWorkspaceIdAndReviewIdAndPracticeId(
                        input.workspaceId(),
                        persisted.getId(),
                        scenario.practice().getId())
                .orElseThrow()
                .getMethod();
        var method = Objects.requireNonNull(recordedMethod);
        assertThat(method).isEqualTo(expected);
        assertThat(method.hashCode()).isEqualTo(expected.hashCode());
    }

    @Test
    void shouldRecordBudgetWaitingAndStartTheSameEventAfterBudgetBecomesAvailable() {
        var input = eventInput();
        when(budget.canRequest(input.workspaceId(), ReviewKind.ITEM, null)).thenReturn(false);
        var offeredAt = Instant.now();
        var id = itemEvents.record(input);
        var waiting = eventRows.findByIdAndWorkspaceId(id, input.workspaceId()).orElseThrow();
        assertThat(waiting.getState()).isEqualTo(ItemEventState.WAITING);
        assertThat(waiting.getReason()).isEqualTo(ReviewRequestReason.BUDGET_EXHAUSTED);
        assertThat(waiting.getRetryAt()).isNotNull();
        assertThat(Objects.requireNonNull(waiting.getRetryAt()))
                .isBetween(
                        offeredAt.plus(eventProperties.pendingRetryAfter()).minusSeconds(1),
                        Instant.now().plus(eventProperties.pendingRetryAfter()).plusSeconds(1));
        assertThat(reviews.findByWorkspaceIdAndEventId(input.workspaceId(), id)).isEmpty();
        var laterAt = Instant.now();
        var later =
                data.result(scenario, ResultOutcome.MET, laterAt, laterAt, Objects.requireNonNull(input.itemVersion()));
        when(budget.canRequest(input.workspaceId(), ReviewKind.ITEM, null)).thenReturn(true);
        new TransactionTemplate(transactions)
                .executeWithoutResult(ignored ->
                        publishedEvents.publishEvent(new ItemEventRecorded(input.workspaceId(), id, input.itemId())));
        assertThat(eventRows
                        .findByIdAndWorkspaceId(id, input.workspaceId())
                        .orElseThrow()
                        .getState())
                .isEqualTo(ItemEventState.STARTED);
        assertThat(reviews.findByWorkspaceIdAndEventId(input.workspaceId(), id)
                        .orElseThrow()
                        .getOrigin())
                .isEqualTo(ReviewOrigin.EVENT);
        assertThat(results.findById(later.getId()).orElseThrow().getEndedAt()).isNull();
    }

    @Test
    void shouldRecordTheGateRefusalInsteadOfCallingDisabledPracticesMissing() {
        var input = eventInput();
        jdbc.sql("UPDATE workspace SET practices_enabled=false WHERE id=:id")
                .param("id", input.workspaceId())
                .update();
        var id = itemEvents.record(input);
        var decision = eventRows.findByIdAndWorkspaceId(id, input.workspaceId()).orElseThrow();
        assertThat(decision.getState()).isEqualTo(ItemEventState.WITHHELD);
        assertThat(decision.getReason()).isEqualTo(ReviewRequestReason.PRACTICES_DISABLED);
        assertThat(decision.getRetryAt()).isNull();
        assertThat(reviews.findByWorkspaceIdAndEventId(input.workspaceId(), id)).isEmpty();
    }

    @Test
    void shouldRejectAnUnmonitoredRepositoryAsIneligible() {
        long workspace = scenario.workspace().getId();
        long itemId = jdbc.sql("""
            INSERT INTO item(workspace_id,kind,source_id,created_at)
            VALUES (:workspace,'scm.repository',:source,now()) RETURNING id
            """)
                .param("workspace", workspace)
                .param("source", scenario.item().getSourceId())
                .query(Long.class)
                .single();
        var ref = productItems.require(workspace, itemId);
        var pinned = ItemFixtures.snapshot(ref, Map.of("defaultBranch", "main"))
                .version(new ItemVersion("a".repeat(40), null))
                .build();
        doReturn(pinned).when(productItems).pin(eq(ref), isNull());
        when(budget.canRequest(workspace, ReviewKind.SCAN, null)).thenReturn(true);
        var request = ReviewRequest.builder()
                .workspaceId(workspace)
                .itemId(itemId)
                .kind(ReviewKind.SCAN)
                .origin(ReviewOrigin.REQUEST)
                .requestedById(scenarioAccountId)
                .requestKey(UUID.randomUUID())
                .requestDigest("b".repeat(64))
                .practiceIds(List.of())
                .reuse(true)
                .build();
        assertThatThrownBy(() -> service.request(request))
                .isInstanceOfSatisfying(ReviewRequestRejected.class, refusal -> {
                    assertThat(refusal.reason()).isEqualTo(ReviewRequestRejected.Reason.ITEM_INELIGIBLE);
                    assertThat(refusal.getStatusCode().value()).isEqualTo(422);
                });
        assertThat(reviews.findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(workspace, itemId))
                .isEmpty();
    }

    @Test
    void shouldSettleAPermanentEventVersionConflictWithoutCreatingAReview() {
        var input = eventInput();
        var ref = productItems.require(input.workspaceId(), input.itemId());
        var wanted = new ItemVersion(Objects.requireNonNull(input.itemVersion()), null);
        var mismatch = ItemFixtures.snapshot(ref, Map.of())
                .version(new ItemVersion("different-version", null))
                .integration(
                        new IntegrationRef(IntegrationKind.GITHUB, input.workspaceId(), "https://github.com", null))
                .label(ItemFixtures.label()
                        .itemId(ref.itemId())
                        .title("Event work")
                        .noun("pull request")
                        .number(1)
                        .integration(IntegrationKind.GITHUB)
                        .build())
                .build();
        doReturn(mismatch).when(productItems).pin(eq(ref), eq(wanted));
        doReturn(mismatch).when(productItems).pinOccasion(eq(ref), eq(wanted), any(UUID.class));
        var id = itemEvents.record(input);
        var decision = eventRows.findByIdAndWorkspaceId(id, input.workspaceId()).orElseThrow();
        assertThat(decision.getState()).isEqualTo(ItemEventState.WITHHELD);
        assertThat(decision.getReason()).isEqualTo(ReviewRequestReason.VERSION_SUPERSEDED);
        assertThat(decision.getRetryAt()).isNull();
        assertThat(reviews.findByWorkspaceIdAndEventId(input.workspaceId(), id)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = ReviewStatus.class,
            names = {"COMPLETE", "PARTIAL", "FAILED", "CANCELLED"})
    void shouldPublishCompletionOnlyForCompletedReviewOutcomesWithAdmittedResults(ReviewStatus status) {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        admit(pending, ResultOutcome.MET);
        settle(pending);
        var completion = new ReviewCompletion(
                status, null, Instant.now(), pending.review().getOrderedAt());
        service.complete(scenario.workspace().getId(), pending.review().getId(), completion);
        service.complete(scenario.workspace().getId(), pending.review().getId(), completion);
        assertThat(reviews.findByIdAndWorkspaceId(
                                pending.review().getId(), scenario.workspace().getId())
                        .orElseThrow()
                        .getStatus())
                .isEqualTo(status);
        assertThat(applicationEvents.stream(ReviewCompleted.class).toList())
                .containsExactlyElementsOf(
                        status == ReviewStatus.COMPLETE || status == ReviewStatus.PARTIAL
                                ? List.of(new ReviewCompleted(
                                        pending.review().getId(),
                                        scenario.workspace().getId()))
                                : List.of());
    }

    @Test
    void shouldNotPublishCompletionWhenNoResultWasAdmitted() {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        running(pending, 1);
        settle(pending);
        service.complete(
                scenario.workspace().getId(),
                pending.review().getId(),
                new ReviewCompletion(
                        ReviewStatus.PARTIAL,
                        ReviewStopReason.EXECUTION_INCOMPLETE,
                        Instant.now(),
                        pending.review().getOrderedAt()));
        assertThat(applicationEvents.stream(ReviewCompleted.class)).isEmpty();
    }

    /**
     * A practice withheld because its stored definition does not fit the work (an older catalog version, an event
     * the item kind does not offer) needs an admin, so the review that ran everything else is still Partial.
     */
    @ParameterizedTest
    @ValueSource(strings = {"item_capability_unsupported", "source_contract_unsupported"})
    void shouldCloseTheReviewPartialWhenAPracticeWasWithheldForADefinitionMismatch(String reason) {
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        admissions.withheld(pending, reason);
        admit(pending, ResultOutcome.MET);
        settle(pending);

        service.complete(
                scenario.workspace().getId(),
                pending.review().getId(),
                new ReviewCompletion(
                        ReviewStatus.COMPLETE,
                        null,
                        Instant.now(),
                        pending.review().getOrderedAt()));

        var review = reviews.findByIdAndWorkspaceId(
                        pending.review().getId(), scenario.workspace().getId())
                .orElseThrow();
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.PARTIAL);
        assertThat(review.getStopReason()).isEqualTo(ReviewStopReason.EXECUTION_INCOMPLETE);
    }

    private void settle(ReviewAdmissionData.Pending pending) {
        assertThat(jdbc.sql("UPDATE review_job SET status=:status,processing_status='DONE',completed_at=now()"
                                + " WHERE workspace_id=:ws AND id=:id")
                        .param("status", ReviewJobStatus.COMPLETED.name())
                        .param("ws", scenario.workspace().getId())
                        .param("id", pending.jobId())
                        .update())
                .isOne();
        when(runtime.activeJobIds(scenario.workspace().getId(), pending.review().getId()))
                .thenReturn(Set.of());
        when(runtime.unsettledJobIds(
                        scenario.workspace().getId(), pending.review().getId()))
                .thenReturn(Set.of());
    }

    @ParameterizedTest
    @EnumSource(Membership.WorkspaceRole.class)
    void shouldReportTheServerReviewDetailsPermissionForTheEffectiveWorkspaceRole(Membership.WorkspaceRole role) {
        long workspaceId = scenario.workspace().getId();
        var ref = productItems.require(workspaceId, scenario.item().getId());
        var url = "https://github.com/example/project/pull/1";
        doReturn(Optional.of(ref)).when(productItems).atUrl(workspaceId, url);
        doReturn(ItemFixtures.snapshot(ref, Map.of()).build())
                .when(productItems)
                .pin(eq(ref), isNull());
        assertThat(jdbc.sql("UPDATE membership SET role=:role WHERE workspace_id=:ws AND account_id=:account")
                        .param("role", role.name())
                        .param("ws", workspaceId)
                        .param("account", scenarioAccountId)
                        .update())
                .isOne();
        var root = "/workspaces/" + scenario.workspace().getWorkspaceSlug();
        var bearer = ReviewIntegrationData.memberToken(scenario);
        boolean canInspect = role != Membership.WorkspaceRole.MEMBER;
        http.get()
                .uri(b -> b.path(root + "/practices/review-context")
                        .queryParam("url", url)
                        .build())
                .headers(h -> h.setBearerAuth(bearer))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.canInspectReviewDetails")
                .isEqualTo(canInspect);
        var response = http.get()
                .uri(root + "/practices/reviews")
                .headers(h -> h.setBearerAuth(bearer))
                .exchange();
        response.expectStatus().isEqualTo(canInspect ? 200 : 403).expectBody(Void.class);
    }
}
