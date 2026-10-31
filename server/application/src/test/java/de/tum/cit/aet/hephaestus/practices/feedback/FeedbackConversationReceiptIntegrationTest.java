package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationCopyOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationCopyReceipt;
import de.tum.cit.aet.hephaestus.practices.spi.CopyWriteFence;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackBasisInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyInput;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDraft;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * A conversation copy is claimed by the running attempt of a Heph turn of its developer, and settled by a receipt that
 * names the Heph answer that showed it. Turns and answers are ledger rows written here directly.
 */
class FeedbackConversationReceiptIntegrationTest extends AbstractFeedbackDeliveryIntegrationTest {
    @Autowired
    FeedbackContentDigest digests;

    @Autowired
    FeedbackCopyCleanupCustody custody;

    UUID conversationId, copyId, mentorConversationId, turnId;

    @BeforeEach
    void prepareConversation() {
        conversationId =
                FeedbackIds.conversation(workspace.getId(), reviewId, developerId, List.of(supportingResult.getId()));
        copyId = FeedbackIds.copy(conversationId, FeedbackCopyKind.CHAT_TURN, 0);
        mentorConversationId = mentorConversation(developerId);
        turnId = runningTurn(mentorConversationId);
        var draft = new FeedbackDraft(
                conversationId,
                workspace.getId(),
                reviewId,
                itemId,
                developerId,
                FeedbackDestination.CONVERSATION,
                FeedbackState.READY,
                null,
                "Discuss focused regression tests.",
                List.of(new FeedbackBasisInput(supportingResult.getId(), null, 0)),
                List.of(new FeedbackCopyInput(
                        copyId, FeedbackCopyKind.CHAT_TURN, 0, null, null, null, null, null, copyId.toString(), null)),
                Map.of(),
                null,
                null);
        store.prepareAll(workspace.getId(), reviewId, List.of(draft));
    }

    private UUID mentorConversation(long account) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO mentor_conversation(id,workspace_id,account_id,created_at,last_activity_at)"
                        + " VALUES (?,?,?,now(),now())",
                id,
                workspace.getId(),
                account);
        return id;
    }

    /** A turn whose first attempt runs now, as a worker's claim leaves it. */
    private UUID runningTurn(UUID conversation) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO mentor_turn(id,workspace_id,conversation_id,request_id,status,surface,attempt,lease_id,"
                        + "leased_until,started_at,created_at)"
                        + " VALUES (?,?,?,?,'RUNNING','APP',1,gen_random_uuid(),now()+interval '5 minutes',now(),now())",
                id,
                workspace.getId(),
                conversation,
                "request-" + id);
        return id;
    }

    /** The lease reaper's retry: the turn runs a second attempt, started after the first one's claims. */
    private void retry() {
        jdbc.update(
                "UPDATE mentor_turn SET attempt=2,started_at=clock_timestamp() WHERE id=? AND workspace_id=?",
                turnId,
                workspace.getId());
    }

    /** Heph's answer: its words, and the successful {@code show_feedback} call whose text showed the feedback. */
    private UUID answer() {
        return answer(new Call("call-1", conversationId.toString(), "Try a focused test.", true));
    }

    /**
     * One {@code show_feedback} call of Heph's answer.
     *
     * @param succeeded whether its tool result is no error; {@code null} for a call without a result
     */
    private record Call(
            String id,
            String feedbackId,
            String text,
            @Nullable Boolean succeeded) {}

    /** Heph's answer with its words and these calls, and the tool result of each call that has one. */
    private UUID answer(Call... calls) {
        UUID entry = UUID.randomUUID();
        var content = new StringBuilder("{\"type\":\"text\",\"text\":\"Some context.\"}");
        for (Call call : calls) {
            content.append(",{\"type\":\"toolCall\",\"id\":\"%s\",\"name\":\"show_feedback\",".formatted(call.id()))
                    .append("\"arguments\":{\"feedbackId\":\"%s\",\"text\":\"%s\"}}"
                            .formatted(call.feedbackId(), call.text()));
        }
        jdbc.update(
                "INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,payload,turn_id,"
                        + "created_at) VALUES (?,?,?,1,'HEPH','APP','Some context.',?::jsonb,?,now())",
                entry,
                workspace.getId(),
                mentorConversationId,
                "{\"type\":\"message\",\"id\":\"a1\",\"message\":{\"role\":\"assistant\",\"content\":[" + content
                        + "]}}",
                turnId);
        int seq = 2;
        for (Call call : calls) {
            if (call.succeeded() == null) continue;
            jdbc.update(
                    "INSERT INTO mentor_entry(id,workspace_id,conversation_id,seq,kind,surface,text,payload,turn_id,"
                            + "created_at) VALUES (?,?,?,?,'TOOL_RESULT','APP','',?::jsonb,?,now())",
                    UUID.randomUUID(),
                    workspace.getId(),
                    mentorConversationId,
                    seq++,
                    ("{\"type\":\"message\",\"id\":\"r-%s\",\"message\":{\"role\":\"toolResult\","
                                    + "\"toolCallId\":\"%s\",\"toolName\":\"show_feedback\",\"isError\":%s,"
                                    + "\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}}")
                            .formatted(call.id(), call.id(), !call.succeeded()),
                    turnId);
        }
        return entry;
    }

    private void endTurn() {
        jdbc.update(
                "UPDATE mentor_turn SET status='DONE',lease_id=NULL,leased_until=NULL,finished_at=now() WHERE id=?",
                turnId);
    }

    private Optional<CopyWriteFence> claim(int attempt) {
        return store.claimConversationCopy(workspace.getId(), conversationId, turnId, attempt);
    }

    private CopyWriteFence claim() {
        return claim(1).orElseThrow();
    }

    private FeedbackCopy chatCopy() {
        return copies.findById(copyId).orElseThrow();
    }

    private Feedback conversation() {
        return feedback.findById(conversationId).orElseThrow();
    }

    private ConversationCopyReceipt receipt(
            ConversationCopyOutcome outcome, @Nullable UUID entry, Instant at, @Nullable String reason) {
        return new ConversationCopyReceipt(turnId, entry, outcome, at, reason);
    }

    @Test
    void shouldCommitOneCopyAndTurnFenceBeforeReturningAClaim() {
        var fence = claim();
        assertThat(fence.feedbackId()).isEqualTo(conversationId);
        assertThat(fence.copyId()).isEqualTo(copyId);
        assertThat(fence.mentorTurnId()).isEqualTo(turnId);
        assertThat(chatCopy().getWriteStartedAt()).isEqualTo(fence.writeStartedAt());
        assertThat(chatCopy().getMentorTurnId()).isEqualTo(turnId);
        assertThat(chatCopy().getMentorEntryId()).isNull();
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
        assertThat(chatCopy().getBody()).isNull();
        assertThat(conversation().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(conversation().getDeliveredAt()).isNull();
        assertThat(store.claimConversationCopy(workspace.getId() + 1, conversationId, turnId, 1))
                .isEmpty();
    }

    @Test
    void shouldReturnTheHeldFenceToARepeatOfTheSameAttempt() {
        var fence = claim();
        assertThat(claim(1)).contains(fence);
        assertThat(store.unsettledConversationClaims(
                        workspace.getId(), turnId, fence.writeStartedAt(), fence.writeStartedAt()))
                .containsExactly(fence);
    }

    @Test
    void shouldRefuseALaterAttemptTheClaimOfALostOne() {
        claim();
        retry();
        assertThat(claim(1)).as("the lost attempt claims nothing more").isEmpty();
        assertThat(claim(2))
                .as("the retry does not take over the lost attempt's claim")
                .isEmpty();
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
    }

    @Test
    void shouldRefuseAnAttemptThatIsNotRunning() {
        retry();
        assertThat(claim(1)).isEmpty();
        endTurn();
        assertThat(claim(2)).isEmpty();
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.PLANNED);
        assertThat(conversation().getState()).isEqualTo(FeedbackState.READY);
    }

    @Test
    void shouldRefuseATurnOfAnotherDeveloperAndKeepTheCopyUnclaimed() {
        long other = Objects.requireNonNull(
                persistAccount("Another conversation owner").getId());
        UUID foreign = runningTurn(mentorConversation(other));
        assertThat(store.claimConversationCopy(workspace.getId(), conversationId, foreign, 1))
                .isEmpty();
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.PLANNED);
        assertThat(chatCopy().getWriteStartedAt()).isNull();
        assertThat(conversation().getState()).isEqualTo(FeedbackState.READY);
    }

    @Test
    void shouldMakeAClaimWaitForAnEndThatHoldsTheTurnAndThenRefuseIt() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var ending = CompletableFuture.runAsync(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> {
                    jdbc.queryForList("SELECT id FROM mentor_turn WHERE id=? FOR UPDATE", turnId);
                    locked.countDown();
                    try {
                        assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    jdbc.update(
                            "UPDATE mentor_turn SET status='DONE',lease_id=NULL,leased_until=NULL WHERE id=?", turnId);
                }));
        assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();
        var claiming = CompletableFuture.supplyAsync(() -> claim(1));
        waitForALockWait();
        assertThat(claiming).as("the claim waits for the end").isNotDone();
        release.countDown();
        ending.get(30, TimeUnit.SECONDS);
        assertThat(claiming.get(30, TimeUnit.SECONDS))
                .as("an ended attempt claims nothing")
                .isEmpty();
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.PLANNED);
    }

    private void waitForALockWait() throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock'"
                            + " AND query LIKE '%FOR SHARE OF t%'",
                    Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("The claim never waited for the turn lock");
    }

    @Test
    void shouldKeepTheFenceCommittedWhenAnEnclosingTransactionRollsBack() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    claim();
                    throw new IllegalStateException("outer rollback");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(chatCopy().getWriteStartedAt()).isNotNull();
        assertThat(conversation().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
    }

    @Test
    void shouldRecordOnlyTheWordsTheCompletedAnswerShowedAndPreserveApprovalContent() {
        var approvedContent = digests.current(workspace.getId(), conversationId);
        var fence = claim();
        UUID entry = answer();
        endTurn();
        Instant at = fence.writeStartedAt().plusSeconds(1);
        store.recordConversationReceipt(
                workspace.getId(), fence, receipt(ConversationCopyOutcome.DELIVERED, entry, at, null));
        assertThat(chatCopy().getBody())
                .as("the receipt holds the words of the call, never the answer's other text")
                .isEqualTo("Try a focused test.");
        assertThat(chatCopy().getMentorEntryId()).isEqualTo(entry);
        assertThat(chatCopy().getPostedAt()).isEqualTo(at);
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.POSTED);
        assertThat(conversation().getState()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(conversation().getDeliveredAt()).isEqualTo(at);
        assertThat(digests.current(workspace.getId(), conversationId)).isEqualTo(approvedContent);
        store.recordConversationReceipt(
                workspace.getId(),
                fence,
                receipt(ConversationCopyOutcome.FAILED, entry, at.plusSeconds(1), "Late failure"));
        assertThat(chatCopy().getPostedAt()).isEqualTo(at);
        assertThat(conversation().getState()).isEqualTo(FeedbackState.DELIVERED);
    }

    @Test
    void shouldRecordTheWordsOfACallThatNamesTheFeedbackInCapitalLetters() {
        var fence = claim();
        UUID entry = answer(
                new Call("call-1", conversationId.toString().toUpperCase(Locale.ROOT), "Try a focused test.", true));
        endTurn();
        Instant at = fence.writeStartedAt().plusSeconds(1);

        store.recordConversationReceipt(
                workspace.getId(), fence, receipt(ConversationCopyOutcome.DELIVERED, entry, at, null));

        assertThat(chatCopy().getBody()).isEqualTo("Try a focused test.");
        assertThat(conversation().getState()).isEqualTo(FeedbackState.DELIVERED);
    }

    @Test
    void shouldRecordOnlyTheWordsOfTheCallsThatSucceededWhenAnAnswerMixesThem() {
        var fence = claim();
        UUID entry = answer(
                new Call("call-1", conversationId.toString(), "Words of a refused call.", false),
                new Call("call-2", conversationId.toString(), "Try a focused test.", true),
                new Call("call-3", conversationId.toString(), "Words of a call without a result.", null));
        endTurn();
        Instant at = fence.writeStartedAt().plusSeconds(1);

        store.recordConversationReceipt(
                workspace.getId(), fence, receipt(ConversationCopyOutcome.DELIVERED, entry, at, null));

        assertThat(chatCopy().getBody()).isEqualTo("Try a focused test.");
    }

    @Test
    void shouldRefuseADeliveryWhenNoCallOfTheAnswerSucceeded() {
        var fence = claim();
        UUID entry = answer(new Call("call-1", conversationId.toString(), "Words of a refused call.", false));
        endTurn();
        Instant at = fence.writeStartedAt().plusSeconds(1);

        assertThatThrownBy(() -> store.recordConversationReceipt(
                        workspace.getId(), fence, receipt(ConversationCopyOutcome.DELIVERED, entry, at, null)))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(chatCopy().getBody()).isNull();
        assertThat(conversation().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
    }

    @Test
    void shouldRefuseADeliveryWithoutTheCompletedAnswerOfTheClaimingTurn() {
        var fence = claim();
        UUID entry = answer();
        Instant at = fence.writeStartedAt().plusSeconds(1);
        assertThatThrownBy(() -> store.recordConversationReceipt(
                        workspace.getId(), fence, receipt(ConversationCopyOutcome.DELIVERED, entry, at, null)))
                .as("the turn still runs")
                .isInstanceOf(ResponseStatusException.class);
        endTurn();
        assertThatThrownBy(() -> store.recordConversationReceipt(
                        workspace.getId(),
                        fence,
                        new ConversationCopyReceipt(turnId, null, ConversationCopyOutcome.DELIVERED, at, null)))
                .as("no answer is named")
                .isInstanceOf(ResponseStatusException.class);
        assertThat(conversation().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(chatCopy().getPostedAt()).isNull();
        assertThat(chatCopy().getBody()).isNull();
    }

    @Test
    void shouldPreserveUncertaintyAndFailureWithoutReopeningTheConversationCopy() {
        var fence = claim();
        store.recordConversationReceipt(
                workspace.getId(),
                fence,
                receipt(
                        ConversationCopyOutcome.UNCONFIRMED,
                        null,
                        fence.writeStartedAt().plusSeconds(1),
                        "No transport confirmation"));
        assertThat(conversation().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
        assertThat(store.unsettledConversationClaims(
                        workspace.getId(), turnId, fence.writeStartedAt(), fence.writeStartedAt()))
                .as("a settled claim is not settled again")
                .isEmpty();
        assertThatThrownBy(() -> custody.requireAcknowledged(workspace.getId(), List.of(conversationId)))
                .isInstanceOf(ResponseStatusException.class);
        store.recordConversationReceipt(
                workspace.getId(),
                fence,
                receipt(
                        ConversationCopyOutcome.FAILED,
                        null,
                        fence.writeStartedAt().plusSeconds(2),
                        "Transport failed"));
        assertThat(conversation().getState()).isEqualTo(FeedbackState.FAILED);
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.FAILED);
        assertThat(chatCopy().getWriteStartedAt()).isEqualTo(fence.writeStartedAt());
        assertThat(chatCopy().getMentorTurnId()).isEqualTo(turnId);
        assertThat(chatCopy().getBody()).isNull();
        assertThat(conversation().getDeliveredAt()).isNull();
        assertThat(claim(1)).isEmpty();
        assertThat(sends.findById(conversationId)).isEmpty();
        assertThatCode(() -> custody.requireAcknowledged(workspace.getId(), List.of(conversationId)))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRefuseForgedCopyTurnTimeOrWorkspaceBindings() {
        var fence = claim();
        var receipt = receipt(
                ConversationCopyOutcome.UNCONFIRMED,
                null,
                fence.writeStartedAt().plusSeconds(2),
                null);
        assertThatThrownBy(() -> store.recordConversationReceipt(
                        workspace.getId(),
                        new CopyWriteFence(conversationId, UUID.randomUUID(), turnId, fence.writeStartedAt()),
                        receipt))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.recordConversationReceipt(
                        workspace.getId(),
                        new CopyWriteFence(
                                conversationId,
                                copyId,
                                turnId,
                                fence.writeStartedAt().plusSeconds(1)),
                        receipt))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.recordConversationReceipt(
                        workspace.getId(),
                        fence,
                        new ConversationCopyReceipt(
                                UUID.randomUUID(), null, ConversationCopyOutcome.UNCONFIRMED, receipt.at(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.recordConversationReceipt(workspace.getId() + 1, fence, receipt))
                .isInstanceOf(RuntimeException.class);
        assertThat(chatCopy().getWriteStartedAt()).isEqualTo(fence.writeStartedAt());
        assertThat(chatCopy().getPostedAt()).isNull();
    }

    @Test
    void shouldRefuseAMarkedBasisBeforeCommittingAnyCopyFence() {
        jdbc.update(
                "INSERT INTO result_mark(id,workspace_id,result_id,kind,reason,created_at)"
                        + " VALUES (?,?,?,'INCORRECT','The claim needs correction.',now())",
                UUID.randomUUID(),
                workspace.getId(),
                supportingResult.getId());
        assertThat(claim(1)).isEmpty();
        assertThat(chatCopy().getWriteStartedAt()).isNull();
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.PLANNED);
    }
}
