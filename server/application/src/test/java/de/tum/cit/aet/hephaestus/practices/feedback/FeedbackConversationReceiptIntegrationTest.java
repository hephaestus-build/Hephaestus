package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.conversation.ConversationRedaction;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRunRows;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.practices.adapter.PracticesPersonDataCatalog;
import de.tum.cit.aet.hephaestus.practices.feedback.decision.FeedbackContentDigest;
import de.tum.cit.aet.hephaestus.practices.spi.ConversationClaims;
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
 * A conversation copy is claimed by the running attempt of a Hephaestus turn of its developer, and settled by a receipt that
 * names the Hephaestus answer that showed it. Turns and answers are ledger rows written here directly.
 */
class FeedbackConversationReceiptIntegrationTest extends AbstractFeedbackDeliveryIntegrationTest {
    @Autowired
    FeedbackContentDigest digests;

    @Autowired
    FeedbackCopyCleanupCustody custody;

    @Autowired
    OwnerScope owners;

    @Autowired
    PracticesPersonDataCatalog practicesStores;

    @Autowired
    PersonDataService personData;

    @Autowired
    ConversationRedaction redaction;

    UUID conversationId, copyId, ledgerConversationId, turnId;

    @BeforeEach
    void prepareConversation() {
        conversationId =
                FeedbackIds.conversation(workspace.getId(), reviewId, developerId, List.of(supportingResult.getId()));
        copyId = FeedbackIds.copy(conversationId, FeedbackCopyKind.CHAT_TURN, 0);
        ledgerConversationId = conversation(developerId);
        turnId = runningTurn(ledgerConversationId, developerId);
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

    private UUID conversation(long account) {
        UUID id = UUID.randomUUID();
        writeLedger(
                account,
                () -> jdbc.update(
                        "INSERT INTO conversation(id,workspace_id,account_id,created_at,last_activity_at)"
                                + " VALUES (?,?,?,now(),now())",
                        id,
                        workspace.getId(),
                        account));
        return id;
    }

    /** A turn whose first attempt runs now, as a worker's claim leaves it. */
    private UUID runningTurn(UUID conversation, long account) {
        UUID id = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        writeLedger(account, () -> {
            jdbc.update(ConversationTurnRunRows.insert(run, workspace.getId(), conversation, "RUNNING", 1));
            jdbc.update(
                    "INSERT INTO conversation_turn(id,workspace_id,conversation_id,account_id,request_id,status,surface,"
                            + "attempt,run_id,started_at,created_at) VALUES (?,?,?,?,?,'RUNNING','APP',1,?,now(),now())",
                    id,
                    workspace.getId(),
                    conversation,
                    account,
                    "request-" + id,
                    run);
        });
        return id;
    }

    private void writeLedger(long account, Runnable operation) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            owners.ofJob(account);
            operation.run();
        });
    }

    /** The lease reaper's retry: the turn runs a second attempt, started after the first one's claims. */
    private void retry() {
        writeLedger(developerId, () -> jdbc.execute(ConversationTurnRunRows.retry(turnId)));
    }

    /** Hephaestus's answer: its words, and the successful {@code show_feedback} call whose text showed the feedback. */
    private UUID answer() {
        return answer(new Call("call-1", conversationId.toString(), "Try a focused test.", true));
    }

    /**
     * One {@code show_feedback} call of Hephaestus's answer.
     *
     * @param succeeded whether its tool result is no error; {@code null} for a call without a result
     */
    private record Call(
            String id,
            String feedbackId,
            String text,
            @Nullable Boolean succeeded) {}

    /** Hephaestus's answer with its words and these calls, and the tool result of each call that has one. */
    private UUID answer(Call... calls) {
        UUID entry = UUID.randomUUID();
        writeLedger(developerId, () -> insertAnswer(entry, calls));
        return entry;
    }

    private void insertAnswer(UUID entry, Call... calls) {
        var content = new StringBuilder("{\"type\":\"text\",\"text\":\"Some context.\"}");
        for (Call call : calls) {
            content.append(",{\"type\":\"toolCall\",\"id\":\"%s\",\"name\":\"show_feedback\",".formatted(call.id()))
                    .append("\"arguments\":{\"feedbackId\":\"%s\",\"text\":\"%s\"}}"
                            .formatted(call.feedbackId(), call.text()));
        }
        jdbc.update(
                "INSERT INTO conversation_entry(id,workspace_id,conversation_id,account_id,seq,kind,surface,text,"
                        + "payload,turn_id,created_at) VALUES (?,?,?,(SELECT account_id FROM conversation WHERE id = ?),1,'ANSWER','APP','Some context.',?::jsonb,?,"
                        + "now())",
                entry,
                workspace.getId(),
                ledgerConversationId,
                ledgerConversationId,
                "{\"type\":\"message\",\"id\":\"a1\",\"message\":{\"role\":\"assistant\",\"content\":[" + content
                        + "]}}",
                turnId);
        int seq = 2;
        for (Call call : calls) {
            if (call.succeeded() == null) continue;
            jdbc.update(
                    "INSERT INTO conversation_entry(id,workspace_id,conversation_id,account_id,seq,kind,surface,text,"
                            + "payload,turn_id,created_at) VALUES (?,?,?,(SELECT account_id FROM conversation WHERE id = ?),?,'TOOL_RESULT','APP','',?::jsonb,?,now())",
                    UUID.randomUUID(),
                    workspace.getId(),
                    ledgerConversationId,
                    ledgerConversationId,
                    seq++,
                    ("{\"type\":\"message\",\"id\":\"r-%s\",\"message\":{\"role\":\"toolResult\","
                                    + "\"toolCallId\":\"%s\",\"toolName\":\"show_feedback\",\"isError\":%s,"
                                    + "\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}}")
                            .formatted(call.id(), call.id(), !call.succeeded()),
                    turnId);
        }
    }

    private void endTurn() {
        writeLedger(developerId, () -> jdbc.update(ConversationTurnRunRows.endDone(turnId)));
    }

    private Optional<CopyWriteFence> claim(int attempt) {
        return conversationClaims.claimConversationCopy(
                workspace.getId(), developerId, conversationId, turnId, attempt);
    }

    private CopyWriteFence claim() {
        return claim(1).orElseThrow();
    }

    private FeedbackCopy chatCopy() {
        return copies.findById(copyId).orElseThrow();
    }

    /** The words that the answer which the copy names shows for the feedback, read as its developer. */
    private @Nullable String shownWords() {
        var copy = chatCopy();
        return new TransactionTemplate(transactionManager).execute(status -> {
            owners.ofJob(developerId);
            return jdbc.queryForObject(
                    "SELECT conversation_receipt_words(?,?,?,?)",
                    String.class,
                    workspace.getId(),
                    conversationId,
                    copy.getConversationEntryId(),
                    copy.getConversationTurnId());
        });
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
        assertThat(fence.conversationTurnId()).isEqualTo(turnId);
        assertThat(chatCopy().getWriteStartedAt()).isEqualTo(fence.writeStartedAt());
        assertThat(chatCopy().getConversationTurnId()).isEqualTo(turnId);
        assertThat(chatCopy().getConversationEntryId()).isNull();
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
        assertThat(chatCopy().getBody()).isNull();
        assertThat(conversation().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(conversation().getDeliveredAt()).isNull();
        assertThat(conversationClaims.claimConversationCopy(
                        workspace.getId() + 1, developerId, conversationId, turnId, 1))
                .isEmpty();
    }

    @Test
    void shouldReturnTheHeldFenceToARepeatOfTheSameAttempt() {
        var fence = claim();
        assertThat(claim(1)).contains(fence);
        assertThat(conversationClaims.unsettledConversationClaims(
                        workspace.getId(), turnId, fence.writeStartedAt(), fence.writeStartedAt()))
                .as("the claim, for the person its feedback is for")
                .containsExactly(new ConversationClaims.Unsettled(fence, developerId));
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
    void shouldRefuseMissingAndForeignReceiptEntriesInTheSameWay() {
        var fence = claim();
        long other = Objects.requireNonNull(
                persistAccount("Another conversation owner").getId());
        UUID foreignConversation = conversation(other);
        UUID foreignTurn = runningTurn(foreignConversation, other);
        UUID foreignEntry = UUID.randomUUID();
        writeLedger(
                other,
                () -> jdbc.update("""
                INSERT INTO conversation_entry (id,workspace_id,conversation_id,account_id,turn_id,seq,kind,surface,text,
                    created_at)
                VALUES (?,?,?,?,?,1,'ANSWER','APP','An answer',now())
                """, foreignEntry, workspace.getId(), foreignConversation, other, foreignTurn));
        UUID question = UUID.randomUUID();
        writeLedger(
                developerId,
                () -> jdbc.update("""
                INSERT INTO conversation_entry (id,workspace_id,conversation_id,account_id,turn_id,seq,kind,surface,text,
                    created_at)
                VALUES (?,?,?,?,?,45,'USER','APP','A question',now())
                """, question, workspace.getId(), ledgerConversationId, developerId, turnId));
        Instant at = fence.writeStartedAt().plusSeconds(1);

        UUID ownEntry = answer();
        endTurn();

        for (UUID refused : List.of(foreignEntry, UUID.randomUUID(), question)) {
            assertUnavailableReceipt(fence, refused, at);
        }
        for (UUID named : List.of(foreignEntry, ownEntry)) {
            assertThatThrownBy(() -> conversationClaims.recordConversationReceipt(
                            workspace.getId(), fence, receipt(ConversationCopyOutcome.FAILED, named, at, null)))
                    .as("only a delivered receipt names an answer")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(chatCopy().getConversationEntryId()).isNull();
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
    }

    private void assertUnavailableReceipt(CopyWriteFence fence, UUID entryId, Instant at) {
        assertThatThrownBy(() -> conversationClaims.recordConversationReceipt(
                        workspace.getId(), fence, receipt(ConversationCopyOutcome.DELIVERED, entryId, at, null)))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                    assertThat(error.getStatusCode().value()).isEqualTo(409);
                    assertThat(error.getReason()).isEqualTo("The completed assistant words are unavailable.");
                });
    }

    @Test
    void shouldRefuseATurnOfAnotherDeveloperAndKeepTheCopyUnclaimed() {
        long other = Objects.requireNonNull(
                persistAccount("Another conversation owner").getId());
        UUID foreign = runningTurn(conversation(other), other);
        assertThat(conversationClaims.claimConversationCopy(workspace.getId(), other, conversationId, foreign, 1))
                .as("the turn's account is not the feedback's developer")
                .isEmpty();
        assertThat(conversationClaims.claimConversationCopy(workspace.getId(), developerId, conversationId, foreign, 1))
                .as("the developer named for a turn of another account")
                .isEmpty();
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.PLANNED);
        assertThat(chatCopy().getWriteStartedAt()).isNull();
        assertThat(conversation().getState()).isEqualTo(FeedbackState.READY);
    }

    /**
     * A claim takes the turn before the feedback. While the claim waits for the turn, the end that holds the turn also
     * takes the feedback: a claim that held the feedback first would deadlock with it.
     */
    @Test
    void shouldMakeAClaimWaitForAnEndThatHoldsTheTurnAndThenRefuseIt() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var ending = CompletableFuture.runAsync(() -> writeLedger(developerId, () -> {
            jdbc.queryForList("SELECT id FROM conversation_turn WHERE id=? FOR UPDATE", turnId);
            locked.countDown();
            try {
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            jdbc.queryForList("SELECT id FROM feedback WHERE id=? FOR UPDATE", conversationId);
            jdbc.update(ConversationTurnRunRows.endDone(turnId));
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
            Thread.sleep(50); // NOPMD - poll interval of a bounded wait
        }
        throw new AssertionError("The claim never waited for the turn lock");
    }

    @Test
    void shouldLeaveTheCopyClaimableWhenTheToolCallThatClaimedItRollsBack() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    claim();
                    throw new IllegalStateException("outer rollback");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.PLANNED);
        assertThat(chatCopy().getWriteStartedAt()).isNull();
        assertThat(conversation().getState()).isEqualTo(FeedbackState.READY);
        assertThat(claim(1)).as("the attempt claims the copy again").isPresent();
    }

    @Test
    void shouldBindTheCompletedAnswerWithoutKeepingItsWordsAndPreserveApprovalContent() {
        var approvedContent = digests.current(workspace.getId(), conversationId);
        var fence = claim();
        UUID entry = answer();
        endTurn();
        Instant at = fence.writeStartedAt().plusSeconds(1);
        conversationClaims.recordConversationReceipt(
                workspace.getId(), fence, receipt(ConversationCopyOutcome.DELIVERED, entry, at, null));
        assertThat(chatCopy().getBody())
                .as("the receipt keeps no words: the answer is their one home")
                .isNull();
        assertThat(chatCopy().getConversationEntryId()).isEqualTo(entry);
        assertThat(shownWords())
                .as("the bound answer shows the words of the call, never the answer's other text")
                .isEqualTo("Try a focused test.");
        assertThat(chatCopy().getPostedAt()).isEqualTo(at);
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.POSTED);
        assertThat(conversation().getState()).isEqualTo(FeedbackState.DELIVERED);
        assertThat(conversation().getDeliveredAt()).isEqualTo(at);
        assertThat(digests.current(workspace.getId(), conversationId)).isEqualTo(approvedContent);
        conversationClaims.recordConversationReceipt(
                workspace.getId(),
                fence,
                receipt(ConversationCopyOutcome.FAILED, null, at.plusSeconds(1), "Late failure"));
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

        conversationClaims.recordConversationReceipt(
                workspace.getId(), fence, receipt(ConversationCopyOutcome.DELIVERED, entry, at, null));

        assertThat(chatCopy().getBody()).isNull();
        assertThat(chatCopy().getConversationEntryId()).isEqualTo(entry);
        assertThat(shownWords()).isEqualTo("Try a focused test.");
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

        conversationClaims.recordConversationReceipt(
                workspace.getId(), fence, receipt(ConversationCopyOutcome.DELIVERED, entry, at, null));

        assertThat(chatCopy().getBody()).isNull();
        assertThat(shownWords()).isEqualTo("Try a focused test.");
        assertThat(conversation().getState()).isEqualTo(FeedbackState.DELIVERED);
    }

    @Test
    void shouldRefuseADeliveryWhenNoCallOfTheAnswerSucceeded() {
        var fence = claim();
        UUID entry = answer(new Call("call-1", conversationId.toString(), "Words of a refused call.", false));
        endTurn();
        Instant at = fence.writeStartedAt().plusSeconds(1);

        assertUnavailableReceipt(fence, entry, at);
        assertThat(chatCopy().getPostedAt()).isNull();
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
        assertThat(conversation().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
    }

    @Test
    void shouldRefuseADeliveryWhoseAnswerShowsOnlyBlankWords() {
        var fence = claim();
        UUID entry = answer(new Call("call-1", conversationId.toString(), "   ", true));
        endTurn();
        Instant at = fence.writeStartedAt().plusSeconds(1);

        assertUnavailableReceipt(fence, entry, at);
        assertThat(chatCopy().getPostedAt()).isNull();
        assertThat(conversation().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
    }

    @Test
    void shouldRefuseADeliveryWhoseAnswerWasRedacted() {
        var fence = claim();
        UUID entry = answer();
        endTurn();
        writeLedger(developerId, () -> redaction.redact(List.of(entry)));
        Instant at = fence.writeStartedAt().plusSeconds(1);

        assertUnavailableReceipt(fence, entry, at);
        assertThat(chatCopy().getPostedAt()).isNull();
        assertThat(chatCopy().getConversationEntryId()).isNull();
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
        assertThat(conversation().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(conversation().getDeliveredAt()).isNull();
    }

    @Test
    void shouldRefuseADeliveryWithoutTheCompletedAnswerOfTheClaimingTurn() {
        var fence = claim();
        UUID entry = answer();
        Instant at = fence.writeStartedAt().plusSeconds(1);
        assertThatThrownBy(() -> conversationClaims.recordConversationReceipt(
                        workspace.getId(), fence, receipt(ConversationCopyOutcome.DELIVERED, entry, at, null)))
                .as("the turn still runs")
                .isInstanceOf(ResponseStatusException.class);
        endTurn();
        assertThatThrownBy(() -> conversationClaims.recordConversationReceipt(
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
        conversationClaims.recordConversationReceipt(
                workspace.getId(),
                fence,
                receipt(
                        ConversationCopyOutcome.UNCONFIRMED,
                        null,
                        fence.writeStartedAt().plusSeconds(1),
                        "No transport confirmation"));
        assertThat(conversation().getState()).isEqualTo(FeedbackState.UNCONFIRMED);
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
        assertThat(conversationClaims.unsettledConversationClaims(
                        workspace.getId(), turnId, fence.writeStartedAt(), fence.writeStartedAt()))
                .as("a settled claim is not settled again")
                .isEmpty();
        assertThatThrownBy(() -> custody.requireAcknowledged(workspace.getId(), List.of(conversationId)))
                .isInstanceOf(ResponseStatusException.class);
        conversationClaims.recordConversationReceipt(
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
        assertThat(chatCopy().getConversationTurnId()).isEqualTo(turnId);
        assertThat(chatCopy().getBody()).isNull();
        assertThat(conversation().getDeliveredAt()).isNull();
        assertThat(claim(1)).isEmpty();
        assertThat(sends.findById(conversationId)).isEmpty();
        assertThatCode(() -> custody.requireAcknowledged(workspace.getId(), List.of(conversationId)))
                .doesNotThrowAnyException();
    }

    /** The person export of a data request copies the stored copy row, so a delivered receipt gives it no words. */
    @Test
    void shouldGiveThePersonExportNoWordsOfADeliveredReceipt() {
        var fence = claim();
        UUID entry = answer();
        endTurn();
        conversationClaims.recordConversationReceipt(
                workspace.getId(),
                fence,
                receipt(
                        ConversationCopyOutcome.DELIVERED,
                        entry,
                        fence.writeStartedAt().plusSeconds(1),
                        null));
        var person = new PersonScope(developerId, List.of(), List.of());
        PersonDataContributor copiesStep = practicesStores.contributors().stream()
                .filter(contributor -> contributor.store().equals("feedback_copy"))
                .findFirst()
                .orElseThrow();

        var exported = copiesStep.export(copiesStep.select(person), person);

        assertThat(exported.toString())
                .as("the delivered receipt is in the person export")
                .contains(copyId.toString())
                .as("the answer is the only home of the shown words")
                .doesNotContain("Try a focused test.");
    }

    /**
     * A person's erasure passes a conversation copy that a receipt settled unconfirmed: the step redacts the answers of
     * its turn, and the erasure's admission decides on a Slack reply (#5440). A claim that no receipt settled still
     * refuses the step, and retention and purge still refuse the unconfirmed copy.
     */
    @Test
    void shouldLetAPersonsErasurePassASettledUnconfirmedCopyButNotARunningClaim() {
        var person = new PersonScope(developerId, List.of(), List.of());
        PersonDataContributor copiesStep = practicesStores.contributors().stream()
                .filter(contributor -> contributor.store().equals("feedback_copy"))
                .findFirst()
                .orElseThrow();
        var fence = claim();
        var running = copiesStep.select(person);
        assertThat(running.rows()).hasSize(1);
        assertThatThrownBy(() -> copiesStep.prepareErasure(running, person))
                .as("a claim that no receipt settled")
                .isInstanceOf(ResponseStatusException.class);

        conversationClaims.recordConversationReceipt(
                workspace.getId(),
                fence,
                receipt(
                        ConversationCopyOutcome.UNCONFIRMED,
                        null,
                        fence.writeStartedAt().plusSeconds(1),
                        "The answer stopped after it showed the feedback"));

        var settled = copiesStep.select(person);
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.WRITE_STARTED);
        assertThatCode(() -> copiesStep.prepareErasure(settled, person)).doesNotThrowAnyException();
        assertThatThrownBy(() -> custody.requireAcknowledged(workspace.getId(), List.of(conversationId)))
                .as("retention and purge keep the custody")
                .isInstanceOf(ResponseStatusException.class);
    }

    /** A provider send's error never marks a running claim as settled, which a person's erasure would admit (#5440). */
    @Test
    void shouldRecordNoSendErrorOnAConversationCopy() {
        claim();

        assertThat(copies.recordError(
                        workspace.getId(), conversationId, copyId, null, FeedbackCopyReadback.UNKNOWN.name(), "x"))
                .isZero();
        assertThat(chatCopy().getLastError()).isNull();
    }

    /**
     * Once the person's erasure is requested, Hephaestus claims none of their feedback for a conversation. The erasure
     * admits a settled conversation copy, so a claim after the request must not reach it (#5440).
     */
    @Test
    void shouldRefuseAClaimAfterThePersonsErasureWasRequested() {
        long administrator =
                Objects.requireNonNull(persistInstanceAdmin("Administrator").getId());
        var preview = personData.preview(administrator, developerId, List.of());
        personData.requestErasure(preview.request().getId(), administrator, false);

        assertThat(claim(1)).isEmpty();
        assertThat(chatCopy().getState()).isEqualTo(FeedbackCopyState.PLANNED);
    }

    @Test
    void shouldRefuseForgedCopyTurnTimeOrWorkspaceBindings() {
        var fence = claim();
        var receipt = receipt(
                ConversationCopyOutcome.UNCONFIRMED,
                null,
                fence.writeStartedAt().plusSeconds(2),
                null);
        assertThatThrownBy(() -> conversationClaims.recordConversationReceipt(
                        workspace.getId(),
                        new CopyWriteFence(conversationId, UUID.randomUUID(), turnId, fence.writeStartedAt()),
                        receipt))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> conversationClaims.recordConversationReceipt(
                        workspace.getId(),
                        new CopyWriteFence(
                                conversationId,
                                copyId,
                                turnId,
                                fence.writeStartedAt().plusSeconds(1)),
                        receipt))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> conversationClaims.recordConversationReceipt(
                        workspace.getId(),
                        fence,
                        new ConversationCopyReceipt(
                                UUID.randomUUID(), null, ConversationCopyOutcome.UNCONFIRMED, receipt.at(), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> conversationClaims.recordConversationReceipt(workspace.getId() + 1, fence, receipt))
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
