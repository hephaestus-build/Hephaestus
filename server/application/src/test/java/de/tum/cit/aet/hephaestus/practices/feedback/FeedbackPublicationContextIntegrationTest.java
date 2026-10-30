package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.HistoryCopies;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.AdmittedResultProjectionService;
import de.tum.cit.aet.hephaestus.agent.handler.FeedbackCompositionService;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeResultStaging;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyRecorder;
import de.tum.cit.aet.hephaestus.integration.core.framework.RegisteredItemLabels;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyDisposition;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.LookupPresence;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecision;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackDecisionService;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.dto.CreateFeedbackDecisionRequestDTO;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultMarkService;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackBasisInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckFacts;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDecisionOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendAction;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackSendState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackHistory;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewWriteGuard;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * A public piece may begin a provider write only while the communication its composer read on the same work, for the
 * same person, is unchanged and no other review's write has an unknown outcome.
 */
class FeedbackPublicationContextIntegrationTest extends AbstractFeedbackDeliveryIntegrationTest {
    @Autowired
    WebTestClient client;

    @Autowired
    FeedbackPublicationContext publication;

    @Autowired
    FeedbackHistoryQuery history;

    @Autowired
    FeedbackDecisionService decisionService;

    @Autowired
    FeedbackContentDigest digests;

    @Autowired
    ResultMarkService marks;

    @Test
    void shouldWithholdAPieceComposedBeforeAnotherReviewPostedOnTheSameWork() {
        UUID review = review();
        String read = contextFor(review);
        sibling(developerId, itemId, FeedbackCopyState.POSTED);
        var piece = publicPiece(review, "Name the missing failure case.", read);
        newSender().send(workspace.getId(), piece);
        assertThat(provider.summaryCreates).isZero();
        assertWithheld(piece, FeedbackStopReason.PUBLIC_HISTORY_CHANGED);
    }

    @Test
    void shouldPostAPieceWhoseComposerAlreadyReadTheOtherReviewsFeedback() {
        sibling(developerId, itemId, FeedbackCopyState.POSTED);
        UUID review = review();
        var piece = publicPiece(review, "Name the missing failure case.", contextFor(review));
        newSender().send(workspace.getId(), piece);
        assertThat(provider.summaryCreates).isEqualTo(1);
        assertThat(feedback.findById(piece).orElseThrow().getState()).isEqualTo(FeedbackState.DELIVERED);
    }

    @Test
    void shouldHoldACreateWhileAnotherReviewsWriteHasNoKnownOutcomeEvenAfterItsSendSettled() {
        UUID review = review();
        var piece = publicPiece(review, "Name the missing failure case.", contextFor(review));
        var other = sibling(developerId, itemId, FeedbackCopyState.WRITE_STARTED);
        jdbc.update("UPDATE feedback_copy SET readback='UNKNOWN' WHERE feedback_id=?", other.getId());
        settledSend(other);
        newSender().send(workspace.getId(), piece);
        assertThat(provider.summaryCreates).isZero();
        assertWithheld(piece, FeedbackStopReason.PUBLIC_DELIVERY_UNRESOLVED);
    }

    @Test
    void shouldReleaseTheHoldOnlyWhenTheOtherWriteIsProvedAbsent() {
        UUID review = review();
        var piece = publicPiece(review, "Name the missing failure case.", contextFor(review));
        var other = sibling(developerId, itemId, FeedbackCopyState.WRITE_STARTED);
        jdbc.update("UPDATE feedback_copy SET state='FAILED', readback='ABSENT' WHERE feedback_id=?", other.getId());
        newSender().send(workspace.getId(), piece);
        assertThat(provider.summaryCreates).isEqualTo(1);
    }

    @Test
    void shouldNotLetOtherPeopleOrOtherWorkContend() {
        UUID review = review();
        var piece = publicPiece(review, "Name the missing failure case.", contextFor(review));
        var otherDeveloper = persistUser("mentor");
        ensureMembership(workspace, otherDeveloper, Membership.WorkspaceRole.MEMBER);
        long otherPerson = accountId(otherDeveloper);
        sibling(otherPerson, itemId, FeedbackCopyState.POSTED);
        sibling(otherPerson, itemId, FeedbackCopyState.WRITE_STARTED);
        sibling(developerId, otherItem(), FeedbackCopyState.POSTED);
        sibling(developerId, otherItem(), FeedbackCopyState.WRITE_STARTED);
        newSender().send(workspace.getId(), piece);
        assertThat(provider.summaryCreates).isEqualTo(1);
    }

    @Test
    void shouldTreatAWithdrawalAfterTheReadAsChangedHistory() {
        var other = sibling(developerId, itemId, FeedbackCopyState.POSTED);
        UUID review = review();
        var piece = publicPiece(review, "Name the missing failure case.", contextFor(review));
        withdraw(other);
        newSender().send(workspace.getId(), piece);
        assertThat(provider.summaryCreates).isZero();
        assertWithheld(piece, FeedbackStopReason.PUBLIC_HISTORY_CHANGED);
    }

    @Test
    void shouldCheckAnUntokenedPieceAgainstEmptyHistoryInsteadOfPassingIt() {
        prepare(false);
        sibling(developerId, itemId, FeedbackCopyState.POSTED);
        sender.send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isZero();
        assertWithheld(feedbackId, FeedbackStopReason.PUBLIC_HISTORY_CHANGED);
    }

    @Test
    void shouldDecideFromOneSnapshotWhenAReceiptCommitsAroundTheDecision() throws Exception {
        var other = sibling(developerId, itemId, FeedbackCopyState.WRITE_STARTED);
        UUID review = review();
        String read = contextFor(review);
        UUID copy = FeedbackIds.copy(other.getId(), FeedbackCopyKind.SUMMARY, 0);
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var receipt = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    assertThat(copies.confirmPosted(
                                    workspace.getId(),
                                    other.getId(),
                                    copy,
                                    AT.plusSeconds(5),
                                    "late-receipt",
                                    "https://example.test/late-receipt",
                                    FeedbackCopyKind.SUMMARY.name()))
                            .isEqualTo(1);
                    holding.countDown();
                    await(release);
                }));
        await(holding);
        // Before the receipt commits: the old context, with the write still unresolved.
        assertThat(refusal(review, read)).contains(FeedbackStopReason.PUBLIC_DELIVERY_UNRESOLVED);
        release.countDown();
        receipt.get(10, TimeUnit.SECONDS);
        // After it commits: the write is resolved, and the context it changed no longer matches.
        assertThat(refusal(review, read)).contains(FeedbackStopReason.PUBLIC_HISTORY_CHANGED);
    }

    @Test
    void shouldHoldASecondCreateOnTheWorksItemLockUntilTheFirstFenceCommits() throws Exception {
        UUID firstReview = review(itemId);
        UUID secondReview = review(itemId);
        long elsewhere = otherItem();
        UUID elsewhereReview = review(elsewhere);
        var first = publicPiece(firstReview, itemId, "Name the missing failure case.", contextFor(firstReview, itemId));
        var second =
                publicPiece(secondReview, itemId, "Cover the empty input as well.", contextFor(secondReview, itemId));
        var unrelated = publicPiece(
                elsewhereReview, elsewhere, "Explain the retry limit.", contextFor(elsewhereReview, elsewhere));
        for (var piece : List.of(first, second, unrelated))
            assertThat(transactions.claim(workspace.getId(), piece, "owner-" + piece))
                    .isNotNull();
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holdingPid = new AtomicInteger();
        // The first fence pauses inside its own transaction, after it holds the work's item lock.
        doAnswer(call -> {
                    Feedback row = call.getArgument(0);
                    FeedbackCheckStage stage = call.getArgument(1);
                    if (row.getId().equals(first) && stage == FeedbackCheckStage.EGRESS) {
                        holdingPid.set(
                                Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                        holding.countDown();
                        await(release);
                    }
                    return allowed(stage);
                })
                .when(gate)
                .evaluate(any(), any(), anyBoolean());
        var fences = new ArrayList<CompletableFuture<Boolean>>();
        var firstFence = CompletableFuture.supplyAsync(() -> fence(first));
        fences.add(firstFence);
        try {
            await(holding);
            var secondFence = CompletableFuture.supplyAsync(() -> fence(second));
            fences.add(secondFence);
            // Match the actual holder's backend, so an unrelated lock waiter cannot satisfy this proof.
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> Objects.requireNonNull(jdbc.queryForObject(
                                    "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                                            + " AND wait_event_type = 'Lock' AND ? = ANY(pg_blocking_pids(pid))"
                                            + " AND query ILIKE '%from item%'",
                                    Long.class, holdingPid.get()))
                            > 0);
            assertThat(secondFence).isNotDone();
            // Another work's create does not wait for this work's lock.
            assertThat(fence(unrelated)).isTrue();
            release.countDown();
            assertThat(firstFence.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(secondFence.get(20, TimeUnit.SECONDS)).isFalse();
            assertWithheld(second, FeedbackStopReason.PUBLIC_DELIVERY_UNRESOLVED);
            assertThat(copies.findById(FeedbackIds.copy(second, FeedbackCopyKind.SUMMARY, 0))
                            .orElseThrow()
                            .getWriteStartedAt())
                    .isNull();
        } finally {
            release.countDown();
            for (var future : fences) future.get(20, TimeUnit.SECONDS);
        }
    }

    @Test
    void shouldCommitAStaleApprovalRefusalAndItsWithholding() {
        when(gate.requiresApproval(any())).thenReturn(true);
        UUID firstReview = review();
        UUID secondReview = review();
        String firstRead = contextFor(firstReview);
        String secondRead = contextFor(secondReview);
        var first = publicPiece(firstReview, "Name the missing failure case.", firstRead);
        var second = publicPiece(secondReview, "Cover the empty input as well.", secondRead);
        assertThat(feedback.findById(second).orElseThrow().getState()).isEqualTo(FeedbackState.AWAITING_APPROVAL);
        assertThat(approve(first).refusal()).isNull();
        newSender().send(workspace.getId(), first);
        assertThat(provider.summaryCreates).isEqualTo(1);

        var refused = approve(second);
        assertThat(refused.refusal()).isEqualTo(FeedbackStopReason.PUBLIC_HISTORY_CHANGED);
        assertThat(refused.state()).isEqualTo(FeedbackState.WITHHELD);
        var row = feedback.findById(second).orElseThrow();
        assertThat(row.getState()).isEqualTo(FeedbackState.WITHHELD);
        assertThat(row.getStopReason()).isEqualTo(FeedbackStopReason.PUBLIC_HISTORY_CHANGED);
        var approved = Objects.requireNonNull(row.getChecks().get(FeedbackCheckStage.APPROVED));
        var check = approved.checks().getFirst();
        assertThat(check.allowed()).isFalse();
        assertThat(check.reason()).isEqualTo(FeedbackStopReason.PUBLIC_HISTORY_CHANGED);
        assertThat(decisions.findLatestApproval(workspace.getId(), second)).isEmpty();
        assertThat(sends.findById(second)).isEmpty();
        assertThatThrownBy(() -> approve(second)).isInstanceOf(ResponseStatusException.class);
        assertThat(provider.summaryCreates).isEqualTo(1);
    }

    @Test
    void shouldKeepAwaitingApprovalWhileAnotherWriteIsUnresolved() {
        when(gate.requiresApproval(any())).thenReturn(true);
        UUID review = review();
        var piece = publicPiece(review, "Name the missing failure case.", contextFor(review));
        sibling(developerId, itemId, FeedbackCopyState.WRITE_STARTED);
        var refused = approve(piece);
        assertThat(refused.refusal()).isEqualTo(FeedbackStopReason.PUBLIC_DELIVERY_UNRESOLVED);
        assertThat(refused.state()).isEqualTo(FeedbackState.AWAITING_APPROVAL);
        var row = feedback.findById(piece).orElseThrow();
        assertThat(row.getState()).isEqualTo(FeedbackState.AWAITING_APPROVAL);
        var approved = Objects.requireNonNull(row.getChecks().get(FeedbackCheckStage.APPROVED));
        assertThat(approved.checks().getFirst().reason()).isEqualTo(FeedbackStopReason.PUBLIC_DELIVERY_UNRESOLVED);
    }

    @Test
    @WithAdminUser
    void shouldAnswerConflictWithTheChangedHistoryCodeAndCommitTheWithholdingWhenAnApprovalIsStale() {
        when(gate.requiresApproval(any())).thenReturn(true);
        UUID firstReview = review();
        UUID secondReview = review();
        String firstRead = contextFor(firstReview);
        String secondRead = contextFor(secondReview);
        var first = publicPiece(firstReview, "Name the missing failure case.", firstRead);
        var second = publicPiece(secondReview, "Cover the empty input as well.", secondRead);
        assertThat(approve(first).refusal()).isNull();
        newSender().send(workspace.getId(), first);
        approveOverHttp(second)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("PUBLIC_HISTORY_CHANGED")
                .jsonPath("$.state")
                .isEqualTo("WITHHELD");
        var row = feedback.findById(second).orElseThrow();
        assertThat(row.getState()).isEqualTo(FeedbackState.WITHHELD);
        assertThat(row.getStopReason()).isEqualTo(FeedbackStopReason.PUBLIC_HISTORY_CHANGED);
        assertThat(decisions.findLatestApproval(workspace.getId(), second)).isEmpty();
        assertThat(sends.findById(second)).isEmpty();
        assertThat(provider.summaryCreates).isEqualTo(1);
    }

    @Test
    @WithAdminUser
    void shouldAnswerConflictWithTheUnresolvedCodeAndKeepAwaitingApprovalWhenAnotherWriteIsUnknown() {
        when(gate.requiresApproval(any())).thenReturn(true);
        UUID review = review();
        var piece = publicPiece(review, "Name the missing failure case.", contextFor(review));
        sibling(developerId, itemId, FeedbackCopyState.WRITE_STARTED);
        approveOverHttp(piece)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("PUBLIC_DELIVERY_UNRESOLVED")
                .jsonPath("$.state")
                .isEqualTo("AWAITING_APPROVAL");
        var row = feedback.findById(piece).orElseThrow();
        assertThat(row.getState()).isEqualTo(FeedbackState.AWAITING_APPROVAL);
        var approved = Objects.requireNonNull(row.getChecks().get(FeedbackCheckStage.APPROVED));
        assertThat(approved.checks().getFirst().reason()).isEqualTo(FeedbackStopReason.PUBLIC_DELIVERY_UNRESOLVED);
        assertThat(decisions.findLatestApproval(workspace.getId(), piece)).isEmpty();
        assertThat(sends.findById(piece)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(LookupPresence.class)
    void shouldRecheckAnUnknownWriteOnlyByReadingItBack(LookupPresence presence) {
        settledUnknown();
        var summary = children().getFirst();
        if (presence == LookupPresence.FOUND)
            provider.physical.put(summary.getId(), current().getBody());
        provider.readback = presence == LookupPresence.UNKNOWN ? LookupPresence.UNKNOWN : LookupPresence.FOUND;
        int attempts = sends.findById(feedbackId).orElseThrow().getAttempts();
        store.recheck(workspace.getId(), feedbackId, developerId);
        due();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isZero();
        assertThat(provider.noteCreates).isZero();
        var after = children();
        assertThat(after.getFirst().getId()).isEqualTo(summary.getId());
        assertThat(after.getFirst().getWriteKey()).isEqualTo(summary.getWriteKey());
        assertThat(after.getFirst().getWriteStartedAt()).isEqualTo(summary.getWriteStartedAt());
        assertThat(after.getFirst().getState())
                .isEqualTo(
                        switch (presence) {
                            case FOUND -> FeedbackCopyState.POSTED;
                            case ABSENT -> FeedbackCopyState.FAILED;
                            case UNKNOWN -> FeedbackCopyState.WRITE_STARTED;
                        });
        assertThat(after.get(1).getState()).isEqualTo(FeedbackCopyState.WITHHELD);
        assertThat(after.get(1).getWriteStartedAt()).isNull();
        assertThat(sends.findById(feedbackId).orElseThrow().getAttempts()).isGreaterThan(attempts);
    }

    @ParameterizedTest
    @EnumSource(LookupPresence.class)
    void shouldRecheckAWithdrawnUnknownWriteWithoutCreatingAnotherCopy(LookupPresence presence) {
        settledUnknown();
        var summary = children().getFirst();
        decisionService.decide(
                workspace.getId(),
                feedbackId,
                developerId,
                new FeedbackDecisionInput(
                        FeedbackDecisionAction.WITHDRAW, null, "The feedback should be withdrawn.", null));
        assertThat(visibility.withdrawn(workspace.getId(), feedbackId)).isTrue();
        assertThat(sends.findById(feedbackId).orElseThrow().getState()).isEqualTo(FeedbackSendState.DONE);
        UUID nextReview = review();
        String priorContext = contextFor(nextReview);
        if (presence == LookupPresence.FOUND)
            provider.physical.put(summary.getId(), current().getBody());
        provider.readback = presence == LookupPresence.UNKNOWN ? LookupPresence.UNKNOWN : LookupPresence.FOUND;

        store.recheck(workspace.getId(), feedbackId, developerId);
        assertThat(recheckAudit(workspace.getId()))
                .singleElement()
                .satisfies(row -> assertThat(
                                Objects.requireNonNull(mapper.readTree(String.valueOf(row.get("new_value"))))
                                        .path("withdrawn")
                                        .asBoolean())
                        .isTrue());
        due();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isZero();
        assertThat(provider.noteCreates).isZero();
        var after = children();
        assertThat(after.getFirst().getId()).isEqualTo(summary.getId());
        assertThat(after.getFirst().getWriteKey()).isEqualTo(summary.getWriteKey());
        assertThat(after.getFirst().getWriteStartedAt()).isEqualTo(summary.getWriteStartedAt());
        assertThat(after.get(1).getState()).isEqualTo(FeedbackCopyState.WITHHELD);
        assertThat(after.get(1).getWriteStartedAt()).isNull();
        assertThat(visibility.withdrawn(workspace.getId(), feedbackId)).isTrue();
        if (presence == LookupPresence.FOUND) {
            var correction = sends.findById(feedbackId).orElseThrow();
            assertThat(correction.getAction()).isEqualTo(FeedbackSendAction.RETRACT);
            assertThat(correction.getState()).isEqualTo(FeedbackSendState.PENDING);
            due();
            newSender().send(workspace.getId(), feedbackId);
            assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.RETRACTED);
            assertThat(provider.updates).isEqualTo(1);
            assertThat(provider.summaryCreates).isZero();
            assertThat(provider.noteCreates).isZero();
        } else if (presence == LookupPresence.ABSENT) {
            assertThat(after.getFirst().getState()).isEqualTo(FeedbackCopyState.FAILED);
            assertThat(after.getFirst().getReadback()).isEqualTo(FeedbackCopyReadback.ABSENT);
            var next = publicPiece(nextReview, "Cover the empty input as well.", priorContext);
            newSender().send(workspace.getId(), next);
            assertThat(provider.summaryCreates).isEqualTo(1);
            assertThat(feedback.findById(next).orElseThrow().getState()).isEqualTo(FeedbackState.DELIVERED);
        } else {
            assertThat(after.getFirst().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
            assertThat(after.getFirst().getReadback()).isNotEqualTo(FeedbackCopyReadback.ABSENT);
            var next = publicPiece(nextReview, "Cover the empty input as well.", priorContext);
            newSender().send(workspace.getId(), next);
            assertWithheld(next, FeedbackStopReason.PUBLIC_DELIVERY_UNRESOLVED);
            assertThat(provider.summaryCreates).isZero();
            assertThat(provider.noteCreates).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "WITHDRAWAL, FOUND",
        "WITHDRAWAL, ABSENT",
        "WITHDRAWAL, UNKNOWN",
        "INCORRECT_MARK, FOUND",
        "INCORRECT_MARK, ABSENT",
        "INCORRECT_MARK, UNKNOWN"
    })
    void shouldRecheckAnUnknownNoteWhenTheRetractionOfItsPostedSummarySettled(
            Correction correction, LookupPresence presence) {
        prepare(true);
        var summary = children().getFirst();
        var note = children().get(1);
        settledPartialPackage(summary, note);
        var started = children().get(1);
        if (correction == Correction.WITHDRAWAL)
            decisionService.decide(
                    workspace.getId(),
                    feedbackId,
                    developerId,
                    new FeedbackDecisionInput(
                            FeedbackDecisionAction.WITHDRAW, null, "The feedback should be withdrawn.", null));
        else markedIncorrect();
        // The retraction replaced the post; it corrects the posted summary and never reads the started note back.
        assertThat(sends.findById(feedbackId).orElseThrow().getAction()).isEqualTo(FeedbackSendAction.RETRACT);
        settleRetraction();
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.RETRACTED);
        assertThat(children().get(1).getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
        UUID nextReview = review();
        String priorContext = contextFor(nextReview);
        if (presence == LookupPresence.FOUND) provider.physical.put(note.getId(), note.getBody());
        provider.readback = presence == LookupPresence.UNKNOWN ? LookupPresence.UNKNOWN : LookupPresence.FOUND;

        assertThatCode(() -> store.recheck(workspace.getId(), feedbackId, developerId))
                .doesNotThrowAnyException();
        assertThat(recheckAudit(workspace.getId())).singleElement().satisfies(row -> {
            assertThat(Objects.requireNonNull(mapper.readTree(String.valueOf(row.get("old_value"))))
                            .path("action")
                            .asString())
                    .isEqualTo("RETRACT");
            assertThat(Objects.requireNonNull(mapper.readTree(String.valueOf(row.get("new_value"))))
                            .path("action")
                            .asString())
                    .isEqualTo("POST");
        });
        due();
        newSender().send(workspace.getId(), feedbackId);
        assertThat(provider.summaryCreates).isZero();
        assertThat(provider.noteCreates).isZero();
        var after = children();
        assertThat(after.getFirst().getState()).isEqualTo(FeedbackCopyState.RETRACTED);
        assertThat(after.get(1).getId()).isEqualTo(started.getId());
        assertThat(after.get(1).getWriteKey()).isEqualTo(started.getWriteKey());
        assertThat(after.get(1).getWriteStartedAt()).isEqualTo(started.getWriteStartedAt());
        switch (presence) {
            case FOUND -> {
                assertThat(after.get(1).getState()).isEqualTo(FeedbackCopyState.POSTED);
                var retraction = sends.findById(feedbackId).orElseThrow();
                assertThat(retraction.getAction()).isEqualTo(FeedbackSendAction.RETRACT);
                assertThat(retraction.getState()).isEqualTo(FeedbackSendState.PENDING);
                due();
                newSender().send(workspace.getId(), feedbackId);
                assertThat(children().get(1).getState()).isEqualTo(FeedbackCopyState.RETRACTED);
                assertThat(provider.physical).doesNotContainKey(note.getId());
                assertThat(provider.summaryCreates).isZero();
                assertThat(provider.noteCreates).isZero();
            }
            case ABSENT -> {
                assertThat(after.get(1).getState()).isEqualTo(FeedbackCopyState.FAILED);
                assertThat(after.get(1).getReadback()).isEqualTo(FeedbackCopyReadback.ABSENT);
                var next = publicPiece(nextReview, "Cover the empty input as well.", priorContext);
                newSender().send(workspace.getId(), next);
                assertThat(provider.summaryCreates).isEqualTo(1);
                assertThat(feedback.findById(next).orElseThrow().getState()).isEqualTo(FeedbackState.DELIVERED);
            }
            case UNKNOWN -> {
                assertThat(after.get(1).getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
                assertThat(after.get(1).getReadback()).isNotEqualTo(FeedbackCopyReadback.ABSENT);
                var next = publicPiece(nextReview, "Cover the empty input as well.", priorContext);
                newSender().send(workspace.getId(), next);
                assertWithheld(next, FeedbackStopReason.PUBLIC_DELIVERY_UNRESOLVED);
                assertThat(provider.summaryCreates).isZero();
            }
        }
    }

    @Test
    void shouldRefuseARecheckWithoutAnUnknownWriteOrWhileItsSendIsOpen() {
        prepare(true);
        assertThatThrownBy(() -> store.recheck(workspace.getId(), feedbackId, developerId))
                .isInstanceOf(ResponseStatusException.class);
        settledUnknownFromPrepared();
        store.recheck(workspace.getId(), feedbackId, developerId);
        assertThatThrownBy(() -> store.recheck(workspace.getId(), feedbackId, developerId))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.retry(workspace.getId(), feedbackId, developerId))
                .isInstanceOf(ResponseStatusException.class);
        // Only the accepted request reached the audit trail; the refused ones rolled back.
        assertThat(recheckAudit(workspace.getId())).hasSize(1);
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
        assertThat(children().get(1).getState()).isEqualTo(FeedbackCopyState.PLANNED);
    }

    @Test
    void shouldReturnTheHistoryPageAndItsContextFromTheServicesOneSnapshot() throws Exception {
        var other = sibling(developerId, itemId, FeedbackCopyState.WRITE_STARTED);
        String before = contextFor(reviewId, itemId);
        var issued = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        var page = new ArrayList<FeedbackHistoryEntry>();
        // A test-local delegate pauses the real read between issuing the context and reading the page.
        var pausing = new FeedbackHistoryQuery() {
            @Override
            public List<FeedbackHistoryEntry> forDeveloper(long workspaceId, long developer, Instant since, int limit) {
                return history.forDeveloper(workspaceId, developer, since, limit);
            }

            @Override
            public Optional<FeedbackHistoryEntry> find(long workspaceId, UUID feedbackId, long developer) {
                return history.find(workspaceId, feedbackId, developer);
            }

            @Override
            public List<FeedbackHistoryEntry> forItem(long workspaceId, long item, long viewer, int limit) {
                var entries = history.forItem(workspaceId, item, viewer, limit);
                page.addAll(entries);
                return entries;
            }

            @Override
            public Optional<PrivateFeedbackHistory> forPrivateComposition(
                    long workspaceId, long developer, Collection<PrivateFeedbackHistory.Route> routes, int limit) {
                return history.forPrivateComposition(workspaceId, developer, routes, limit);
            }

            @Override
            public Optional<FeedbackStopReason> publicationRefusal(
                    long workspaceId, long item, UUID review, long developer, @Nullable String context) {
                return history.publicationRefusal(workspaceId, item, review, developer, context);
            }

            @Override
            public String publicationContext(long workspaceId, long item, UUID review, Collection<Long> developerIds) {
                String context = history.publicationContext(workspaceId, item, review, developerIds);
                issued.countDown();
                await(committed);
                return context;
            }
        };
        var job = ownedAdmittedJob();
        var service = admissionService(job, pausing);
        var read = CompletableFuture.supplyAsync(() -> service.publicFeedbackHistory(
                new ResultAdmissionService.AdmissionIdentity(job.getId(), workspace.getId(), 0, "worker")));
        try {
            await(issued);
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(copies.confirmPosted(
                                workspace.getId(),
                                other.getId(),
                                FeedbackIds.copy(other.getId(), FeedbackCopyKind.SUMMARY, 0),
                                AT.plusSeconds(5),
                                "receipt-between-reads",
                                "https://example.test/receipt",
                                FeedbackCopyKind.SUMMARY.name()))
                        .isEqualTo(1);
                jdbc.update(
                        "UPDATE feedback SET delivered_at=? WHERE workspace_id=? AND id=?",
                        Timestamp.from(AT.plusSeconds(5)),
                        workspace.getId(),
                        other.getId());
            });
            committed.countDown();
            var response = read.get(20, TimeUnit.SECONDS);
            // The real service's snapshot predates the receipt; a fresh read outside it sees the receipt.
            assertThat(response.publicationContext()).isEqualTo(before);
            assertThat(page)
                    .filteredOn(entry -> entry.feedbackId().equals(other.getId()))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.deliveredAt()).isNull());
            assertThat(response.history().feedback()).isEmpty();
            assertThat(history.forItem(workspace.getId(), itemId, developerId, 100))
                    .filteredOn(entry -> entry.feedbackId().equals(other.getId()))
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.deliveredAt()).isNotNull());
            assertThat(contextFor(reviewId, itemId)).isNotEqualTo(before);
        } finally {
            committed.countDown();
            read.get(20, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @WithAdminUser
    void shouldRecordWhoAskedForARecheckOnTheConfigurationAuditTrail(boolean withdrawn) {
        settledUnknown();
        if (withdrawn)
            decisionService.decide(
                    workspace.getId(),
                    feedbackId,
                    developerId,
                    new FeedbackDecisionInput(
                            FeedbackDecisionAction.WITHDRAW, null, "The feedback should be withdrawn.", null));
        client.post()
                .uri(route() + "/" + feedbackId + "/recheck")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.state")
                .exists();
        var rows = recheckAudit(workspace.getId());
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("actor_account_id")).isEqualTo(developerId);
            assertThat(row.get("entity_id")).isEqualTo(feedbackId.toString());
            assertThat(row.get("action")).isEqualTo("UPDATED");
            assertThat(row.get("occurred_at")).isNotNull();
            var snapshot = Objects.requireNonNull(mapper.readTree(String.valueOf(row.get("new_value"))));
            assertThat(snapshot.path("state").asString()).isEqualTo("PENDING");
            assertThat(snapshot.path("withdrawn").asBoolean()).isEqualTo(withdrawn);
        });
        assertThat(sends.findById(feedbackId).orElseThrow().getState()).isEqualTo(FeedbackSendState.PENDING);
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
    }

    @Test
    @WithAdminUser
    void shouldNotRecheckAnotherWorkspacesFeedback() {
        settledUnknown();
        var other = createWorkspace(
                "recheck-other", "Other workspace", "recheck-org", AccountType.ORG, persistUser("other-owner"));
        ensureAdminMembership(other);
        client.post()
                .uri("/workspaces/" + other.getWorkspaceSlug() + "/practices/reviews/feedback/" + feedbackId
                        + "/recheck")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        assertThat(recheckAudit(workspace.getId())).isEmpty();
        assertThat(recheckAudit(other.getId())).isEmpty();
        assertThat(sends.findById(feedbackId).orElseThrow().getState()).isEqualTo(FeedbackSendState.DONE);
    }

    @Test
    @WithMentorUser
    void shouldRequireWorkspaceAdministrationToRecheck() {
        settledUnknown();
        ensureMembership(workspace, persistUser("mentor"), Membership.WorkspaceRole.MEMBER);
        client.post()
                .uri(route() + "/" + feedbackId + "/recheck")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(recheckAudit(workspace.getId())).isEmpty();
        assertThat(sends.findById(feedbackId).orElseThrow().getState()).isEqualTo(FeedbackSendState.DONE);
    }

    private Feedback sibling(long developer, long item, FeedbackCopyState state) {
        var parent = feedback.saveAndFlush(Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .reviewId(review(item))
                .itemId(item)
                .developerId(developer)
                .destination(FeedbackDestination.ON_WORK)
                .state(state == FeedbackCopyState.POSTED ? FeedbackState.DELIVERED : FeedbackState.UNCONFIRMED)
                .body("Earlier advice on this work.")
                .createdAt(AT)
                .deliveredAt(state == FeedbackCopyState.POSTED ? AT : null)
                .build());
        summary(parent, state);
        return parent;
    }

    private UUID publicPiece(UUID review, String body, @Nullable String context) {
        return publicPiece(review, itemId, body, context);
    }

    private UUID publicPiece(UUID review, long item, String body, @Nullable String context) {
        Result support = supportIn(review, item);
        UUID id = FeedbackIds.onWork(workspace.getId(), review, developerId);
        UUID copy = FeedbackIds.copy(id, FeedbackCopyKind.SUMMARY, 0);
        var trace = new FeedbackCompositionTrace(
                List.of(new FeedbackCompositionTrace.CopySupport(
                        FeedbackCopyKind.SUMMARY,
                        0,
                        List.of(new FeedbackCompositionTrace.Support(support.getId(), null)))),
                List.of(),
                null,
                null,
                context);
        store.prepareAll(
                workspace.getId(),
                review,
                List.of(new FeedbackDraft(
                        id,
                        workspace.getId(),
                        review,
                        item,
                        developerId,
                        FeedbackDestination.ON_WORK,
                        FeedbackState.READY,
                        null,
                        body,
                        List.of(new FeedbackBasisInput(support.getId(), null, 0)),
                        List.of(new FeedbackCopyInput(
                                copy,
                                FeedbackCopyKind.SUMMARY,
                                0,
                                null,
                                null,
                                null,
                                null,
                                null,
                                copy.toString(),
                                FeedbackIds.writeMarker(copy))),
                        Map.of(),
                        trace,
                        null)));
        return id;
    }

    /** What a history read for {@code review} issues now. */
    private String contextFor(UUID review) {
        return contextFor(review, itemId);
    }

    private String contextFor(UUID review, long item) {
        return publication.issue(workspace.getId(), item, review, List.of(developerId));
    }

    private Optional<FeedbackStopReason> refusal(UUID review, String context) {
        return Objects.requireNonNull(new TransactionTemplate(transactionManager)
                .execute(status -> publication.refusal(workspace.getId(), itemId, review, developerId, context)));
    }

    private void withdraw(Feedback parent) {
        decide(parent, FeedbackDecisionAction.WITHDRAW, 60);
    }

    private void decide(Feedback parent, FeedbackDecisionAction action, long afterSeconds) {
        decisions.saveAndFlush(FeedbackDecision.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspace.getId())
                .feedbackId(parent.getId())
                .action(action)
                .actorAccountId(developerId)
                .note("Changed after the history read.")
                .decidedAt(AT.plusSeconds(afterSeconds))
                .build());
    }

    private UUID review() {
        return review(itemId);
    }

    private UUID review(long item) {
        UUID review = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at,created_at)"
                        + " VALUES (?,?,?,'ITEM','v1','REQUEST','RUNNING',?,?)",
                review,
                workspace.getId(),
                item,
                Timestamp.from(AT),
                Timestamp.from(AT));
        return review;
    }

    private Result supportIn(UUID review, long item) {
        UUID ownReview = reviewId;
        long ownItem = itemId;
        reviewId = review;
        itemId = item;
        try {
            return negativeResult();
        } finally {
            reviewId = ownReview;
            itemId = ownItem;
        }
    }

    private long otherItem() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO item(workspace_id,kind,source_id,created_at) VALUES (?,'scm.pull_request',?,?)"
                        + " RETURNING id",
                Long.class,
                workspace.getId(),
                1000 + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100_000),
                Timestamp.from(AT)));
    }

    private void settledSend(Feedback parent) {
        sends.saveAndFlush(FeedbackSend.builder()
                .feedbackId(parent.getId())
                .workspaceId(workspace.getId())
                .action(FeedbackSendAction.POST)
                .state(FeedbackSendState.DONE)
                .attempts(8)
                .nextAttemptAt(AT)
                .createdAt(AT)
                .updatedAt(AT)
                .build());
    }

    /** The fixture piece: its summary write started and its send settled at the readback limit; its note planned. */
    private void settledUnknown() {
        prepare(true);
        settledUnknownFromPrepared();
    }

    private void settledUnknownFromPrepared() {
        String owner = "exhausted-owner";
        assertThat(transactions.claim(workspace.getId(), feedbackId, owner)).isNotNull();
        var summary = children().getFirst();
        assertThat(transactions.fence(workspace.getId(), feedbackId, owner, List.of(summary.getId()), List.of()))
                .isTrue();
        transactions.finish(workspace.getId(), feedbackId, owner, true);
        assertThat(sends.findById(feedbackId).orElseThrow().getState()).isEqualTo(FeedbackSendState.DONE);
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
        assertThat(children().get(1).getState()).isEqualTo(FeedbackCopyState.PLANNED);
    }

    /** A posted summary and a note whose write stayed unknown after the post settled its readbacks. */
    private void settledPartialPackage(FeedbackCopy summary, FeedbackCopy note) {
        String owner = "partial-owner";
        assertThat(transactions.claim(workspace.getId(), feedbackId, owner)).isNotNull();
        assertThat(transactions.fence(workspace.getId(), feedbackId, owner, List.of(summary.getId()), List.of()))
                .isTrue();
        provider.physical.put(summary.getId(), current().getBody());
        transactions.receipt(
                workspace.getId(),
                feedbackId,
                owner,
                List.of(new CopyReceipt(
                        summary.getId(),
                        CopyDisposition.POSTED,
                        summary.getId().toString(),
                        "https://example.test/comments/" + summary.getId(),
                        true,
                        null)));
        assertThat(transactions.fence(workspace.getId(), feedbackId, owner, List.of(note.getId()), List.of()))
                .isTrue();
        transactions.finish(workspace.getId(), feedbackId, owner, true);
        var post = sends.findById(feedbackId).orElseThrow();
        assertThat(post.getAction()).isEqualTo(FeedbackSendAction.POST);
        assertThat(post.getState()).isEqualTo(FeedbackSendState.DONE);
        assertThat(children().getFirst().getState()).isEqualTo(FeedbackCopyState.POSTED);
        assertThat(children().get(1).getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
    }

    /** The real gate refuses feedback on an incorrect basis; this stand-in refuses only the marked piece. */
    private void markedIncorrect() {
        doAnswer(call -> {
                    Feedback row = call.getArgument(0);
                    FeedbackCheckStage stage = call.getArgument(1);
                    return row.getId().equals(feedbackId)
                            ? new FeedbackCheck(
                                    stage,
                                    false,
                                    FeedbackStopReason.RESULT_MARKED_INCORRECT,
                                    Instant.now(),
                                    new FeedbackCheckFacts(0, 0, true, true, true, true, false, true, true, false))
                            : allowed(stage);
                })
                .when(gate)
                .evaluate(any(), any(), anyBoolean());
        marks.create(
                workspace.getId(),
                supportingResult.getId(),
                ResultMarkKind.INCORRECT,
                developerId,
                "The basis is wrong.");
    }

    private void settleRetraction() {
        for (int attempt = 0;
                attempt < 10 && sends.findById(feedbackId).orElseThrow().getState() != FeedbackSendState.DONE;
                attempt++) {
            due();
            newSender().send(workspace.getId(), feedbackId);
        }
        var retraction = sends.findById(feedbackId).orElseThrow();
        assertThat(retraction.getAction()).isEqualTo(FeedbackSendAction.RETRACT);
        assertThat(retraction.getState()).isEqualTo(FeedbackSendState.DONE);
    }

    private enum Correction {
        WITHDRAWAL,
        INCORRECT_MARK
    }

    private FeedbackDecisionOutcome approve(UUID piece) {
        return decisionService.decide(
                workspace.getId(),
                piece,
                developerId,
                new FeedbackDecisionInput(
                        FeedbackDecisionAction.APPROVE, null, null, digests.current(workspace.getId(), piece)));
    }

    private WebTestClient.ResponseSpec approveOverHttp(UUID piece) {
        return client.post()
                .uri(route() + "/" + piece + "/decisions")
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(new CreateFeedbackDecisionRequestDTO(
                        FeedbackDecisionAction.APPROVE, null, null, digests.current(workspace.getId(), piece)))
                .exchange();
    }

    private void assertWithheld(UUID piece, FeedbackStopReason reason) {
        var row = feedback.findById(piece).orElseThrow();
        assertThat(row.getState()).isEqualTo(FeedbackState.WITHHELD);
        assertThat(row.getStopReason()).isEqualTo(reason);
        assertThat(copies.findByWorkspaceIdAndFeedbackIdOrderByPositionAsc(workspace.getId(), piece))
                .allSatisfy(copy -> assertThat(copy.getWriteStartedAt()).isNull());
    }

    private boolean fence(UUID piece) {
        return transactions.fence(
                workspace.getId(),
                piece,
                "owner-" + piece,
                List.of(FeedbackIds.copy(piece, FeedbackCopyKind.SUMMARY, 0)),
                List.of());
    }

    private static FeedbackCheck allowed(FeedbackCheckStage stage) {
        return new FeedbackCheck(
                stage,
                true,
                null,
                Instant.now(),
                new FeedbackCheckFacts(0, 0, true, true, true, true, false, true, true, false));
    }

    private String route() {
        return "/workspaces/" + workspace.getWorkspaceSlug() + "/practices/reviews/feedback";
    }

    private List<Map<String, @Nullable Object>> recheckAudit(long workspace) {
        return jdbc.queryForList(
                "SELECT actor_account_id, entity_id, action, occurred_at, old_value, new_value FROM config_audit_event"
                        + " WHERE workspace_id=? AND entity_type='FEEDBACK_DELIVERY_RECHECK'",
                workspace);
    }

    /** A running attempt of the fixture review that admission accepted, owned by worker "worker". */
    private ReviewJob ownedAdmittedJob() {
        var job = RuntimeReviewTestData.job();
        job.setWorkspaceId(workspace.getId());
        job.setReviewId(reviewId);
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setLeasedUntil(Instant.now().plusSeconds(3600));
        job.setWorkerId("worker");
        job.setAdmissionAttempt(0);
        job.setAdmissionDigest("admitted");
        return job;
    }

    /** The production service and its own history snapshot; only ownership and late-copy custody are stand-ins. */
    private ResultAdmissionService admissionService(ReviewJob job, FeedbackHistoryQuery query) {
        var jobs = mock(ReviewJobRepository.class);
        when(jobs.findByIdAndWorkspaceId(job.getId(), workspace.getId())).thenReturn(Optional.of(job));
        when(jobs.findByIdAndWorkspaceIdForUpdate(job.getId(), workspace.getId()))
                .thenReturn(Optional.of(job));
        var base = RuntimeReviewTestData.assignment(developerId, null);
        var item = new ItemRef(workspace.getId(), itemId, new ItemKind("scm.pull_request"), 42L, null, null);
        var assignment = new ReviewAssignmentSnapshot(
                new ReviewAssignmentId(reviewId, base.id().practiceId(), itemId),
                item,
                base.version(),
                developerId,
                base.practiceSlug(),
                base.practice());
        var assignments = mock(ReviewJobAssignments.class);
        when(assignments.review(job))
                .thenReturn(new ReviewSnapshot(
                        reviewId,
                        workspace.getId(),
                        item,
                        ReviewKind.ITEM,
                        base.version(),
                        ReviewOrigin.REQUEST,
                        1,
                        List.of(base.practice())));
        when(assignments.load(job)).thenReturn(List.of(assignment));
        var files = mock(JobEvidenceFiles.class);
        when(files.indexLateRead(any(), any(), any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        var source = new ReviewHistoryContentSource(
                mock(ResultHistoryQuery.class),
                query,
                new HistoryCopies(mock(PersonDataCopyRecorder.class)),
                mock(RegisteredItemLabels.class),
                mapper);
        return new ResultAdmissionService(
                jobs,
                mock(AdmittedResultQuery.class),
                assignments,
                mock(AdmittedResultProjectionService.class),
                files,
                source,
                mapper,
                transactionManager,
                mock(FeedbackCompositionService.class),
                mock(ReviewWriteGuard.class),
                mock(PracticeResultStaging.class));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
