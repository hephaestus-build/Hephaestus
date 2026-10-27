package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

@RecordApplicationEvents
class MentorTurnServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    private static final String WORKER = "worker-turns";
    private static final Duration LEASE = Duration.ofMinutes(5);
    /** A lease that is already over when it is granted, so the reaper may take it back at once. */
    private static final Duration EXPIRED = Duration.ofSeconds(-1);

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorTurnRepository turnRepository;

    @Autowired
    private MentorEntryRepository entryRepository;

    @Autowired
    private MentorEntryService entryService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MentorConversationRepository conversationRepository;

    @Autowired
    private MentorRedaction redaction;

    @Autowired
    private PersonDataCopyFence copies;

    @Autowired
    private PersonDataWriteFence writeFence;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private ApplicationEvents published;

    @Autowired
    private MentorTurnWaits waits;

    private Workspace workspace;
    private MentorConversation main;
    private long account;

    @BeforeEach
    void setUpLedger() {
        User developer = persistUser("heph-turns-developer");
        workspace = createWorkspace("heph-turns", "Heph", "heph-org", AccountType.ORG, developer);
        account = accountId(developer);
        main = conversations.findOrCreateMain(workspace.getId(), account);
    }

    /** The developer asked to delete their account; their personal data is erased after the cooldown. */
    private void requestErasure() {
        transactionTemplate.executeWithoutResult(tx -> entityManager
                .createNativeQuery("UPDATE account SET status = 'DELETING' WHERE id = :id")
                .setParameter("id", account)
                .executeUpdate());
    }

    @Test
    void shouldStoreNothingAndRefuseNewQuestionsWhenTheDevelopersErasureWasRequestedWhileTheAttemptRan() {
        ask(main, "Question");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        requestErasure();

        turns.end(
                running.getId(),
                running.getAttempt(),
                WORKER,
                MentorTurn.Status.DONE,
                List.of(MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Words about the developer")),
                null);

        assertThat(MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), running.getId()))
                .extracting(MentorEntry::getKind)
                .as("only the question stays; the erasure deletes it")
                .containsExactly(MentorEntry.Kind.USER);
        assertThat(turnRepository.findById(running.getId()).orElseThrow().getStatus())
                .isEqualTo(MentorTurn.Status.DONE);
        assertThatThrownBy(() -> ask(main, "Another question"))
                .isInstanceOfSatisfying(
                        MentorRejectedException.class,
                        rejected ->
                                assertThat(rejected.reason()).isEqualTo(MentorRejectedException.Reason.PERSON_ERASED));
    }

    private MentorTurnService.Submission ask(MentorConversation conversation, String text) {
        return turns.submit(
                workspace.getId(),
                conversation.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    /** What a host sends at the end of a turn that read a context document and answered. */
    private static List<JsonNode> answerThatReadContext(String answer) {
        return List.of(
                MentorLedgerFixtures.user(MentorLedgerFixtures.piId(), "rendered prompt"),
                MentorLedgerFixtures.toolCall(
                        MentorLedgerFixtures.piId(),
                        "fetch-1",
                        "fetch_context",
                        JsonNodeFactory.instance.objectNode().put("path", "team.json")),
                MentorLedgerFixtures.toolResult(
                        MentorLedgerFixtures.piId(), "fetch-1", "fetch_context", "Alex reviews the API.", false),
                MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), answer));
    }

    private List<MentorEntry> storedFor(MentorTurn turn) {
        return MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId());
    }

    private MentorTurn stored(MentorTurn turn) {
        return turnRepository.findById(turn.getId()).orElseThrow();
    }

    @Test
    void shouldStoreTheQuestionAndAQueuedTurnWhenTheDeveloperAsks() {
        MentorTurnService.Submission submission = ask(main, "How did my last review go?");

        MentorEntry question =
                entryRepository.findById(submission.question().getId()).orElseThrow();
        assertThat(submission.created()).isTrue();
        assertThat(question.getKind()).isEqualTo(MentorEntry.Kind.USER);
        assertThat(question.getTurnId()).isEqualTo(submission.turn().getId());
        assertThat(question.getRequestId()).isEqualTo(submission.turn().getRequestId());
        assertThat(stored(submission.turn()).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
    }

    @Test
    void shouldRefuseAFourthFollowUpWhenThreeAlreadyWait() {
        ask(main, "One");
        ask(main, "Two");
        ask(main, "Three");

        assertThatThrownBy(() -> ask(main, "Four"))
                .isInstanceOfSatisfying(
                        MentorRejectedException.class,
                        rejected -> assertThat(rejected.reason())
                                .isEqualTo(MentorRejectedException.Reason.FOLLOW_UPS_FULL));
    }

    @Test
    void shouldRunOneTurnPerConversationAndThreadsInParallelWhenWorkersClaim() {
        MentorTurnService.Submission first = ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        MentorConversation thread =
                conversations.openThread(workspace.getId(), first.question().getId());
        MentorTurnService.Submission inThread = ask(thread, "About this");

        MentorTurn claimedFirst = turns.claimNext(WORKER, LEASE).orElseThrow();
        MentorTurn claimedSecond = turns.claimNext("worker-two", LEASE).orElseThrow();

        assertThat(claimedFirst.getId()).isEqualTo(first.turn().getId());
        assertThat(claimedSecond.getId()).isEqualTo(inThread.turn().getId());
        turns.claimNext("worker-three", LEASE);
        assertThat(stored(followUp.turn()).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(stored(claimedFirst).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(stored(claimedFirst).getAttempt()).isEqualTo(1);
        assertThat(stored(claimedFirst).getWorkerId()).isEqualTo(WORKER);
    }

    @Test
    void shouldStartTheFollowUpWhenTheRunningTurnEnds() {
        ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();

        turns.end(running.getId(), running.getAttempt(), WORKER, MentorTurn.Status.DONE, List.of(), null);

        assertThat(turns.claimNext(WORKER, LEASE))
                .hasValueSatisfying(next ->
                        assertThat(next.getId()).isEqualTo(followUp.turn().getId()));
    }

    @Test
    void shouldStartTheEarlierQuestionFirstWhenAnotherServersClockStampedTheFollowUpEarlier() {
        MentorTurnService.Submission first = ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        // A second server whose clock runs behind took the follow-up.
        transactionTemplate.executeWithoutResult(tx -> entityManager
                .createNativeQuery("""
                        UPDATE mentor_turn SET created_at = (SELECT created_at FROM mentor_turn
                                WHERE id = :first AND workspace_id = :workspaceId) - INTERVAL '1 minute'
                        WHERE id = :followUp AND workspace_id = :workspaceId
                        """)
                .setParameter("workspaceId", first.turn().getWorkspaceId())
                .setParameter("first", first.turn().getId())
                .setParameter("followUp", followUp.turn().getId())
                .executeUpdate());

        assertThat(turns.claimNext(WORKER, LEASE))
                .hasValueSatisfying(
                        next -> assertThat(next.getId()).isEqualTo(first.turn().getId()));
        assertThat(turns.claimNext("worker-two", LEASE))
                .as("the follow-up waits for the question before it")
                .isEmpty();
        assertThat(stored(followUp.turn()).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
    }

    @Test
    void shouldSayAQuestionWaitsForAFreeHephOnlyWhileHephAnswersAnotherConversation() {
        MentorTurnService.Submission first = ask(main, "First");

        assertThat(waits.waits(workspace.getId(), List.of(stored(first.turn()))))
                .as("nothing holds Heph, so the question starts at once or no worker runs")
                .isEmpty();

        assertThat(turns.claimNext(WORKER, LEASE)).isPresent();
        MentorConversation thread =
                conversations.openThread(workspace.getId(), first.question().getId());
        MentorTurnService.Submission inThread = ask(thread, "About this");

        assertThat(waits.waits(workspace.getId(), List.of(stored(inThread.turn()))))
                .containsExactly(entry(inThread.turn().getId(), MentorTurn.WaitingFor.FREE_HEPH));
    }

    @Test
    void shouldSayAQuestionWaitsForAFreeHephWhileHephAnswersAnotherWorkspace() {
        User other = persistUser("heph-turns-other-developer");
        Workspace otherWorkspace = createWorkspace("heph-turns-other", "Other", "other-org", AccountType.ORG, other);
        MentorConversation otherMain = conversations.findOrCreateMain(otherWorkspace.getId(), accountId(other));
        turns.submit(
                otherWorkspace.getId(),
                otherMain.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, "Elsewhere", UUID.randomUUID().toString(), null, null));
        assertThat(turns.claimNext(WORKER, LEASE)).isPresent();

        MentorTurnService.Submission here = ask(main, "Here");

        assertThat(waits.waits(workspace.getId(), List.of(stored(here.turn()))))
                .as("every workspace shares the workers' Heph capacity")
                .containsExactly(entry(here.turn().getId(), MentorTurn.WaitingFor.FREE_HEPH));
    }

    @Test
    void shouldGiveAFollowUpNoReasonWhileTheQuestionBeforeItWaitsOrRuns() {
        ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        turns.claimNext(WORKER, LEASE).orElseThrow();

        assertThat(waits.waits(workspace.getId(), List.of(stored(followUp.turn()))))
                .as("it waits for its own conversation, not for a free Heph")
                .isEmpty();
    }

    @Test
    void shouldPublishTheFollowUpAgainWhenTheTurnBeforeItEnds() {
        ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        published.clear();

        turns.end(running.getId(), running.getAttempt(), WORKER, MentorTurn.Status.DONE, List.of(), null);

        assertThat(published.stream(MentorLedgerEvents.TurnChanged.class))
                .extracting(MentorLedgerEvents.TurnChanged::turnId, MentorLedgerEvents.TurnChanged::status)
                .contains(tuple(followUp.turn().getId(), MentorTurn.Status.QUEUED));
    }

    @Test
    void shouldStopSayingHephIsBusyAndPublishTheQuestionWhenTheTurnThatHeldHephEnds() {
        MentorTurnService.Submission first = ask(main, "First");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        MentorConversation thread =
                conversations.openThread(workspace.getId(), first.question().getId());
        MentorTurnService.Submission inThread = ask(thread, "About this");
        published.clear();

        turns.end(running.getId(), running.getAttempt(), WORKER, MentorTurn.Status.DONE, List.of(), null);

        assertThat(published.stream(MentorLedgerEvents.TurnChanged.class))
                .extracting(MentorLedgerEvents.TurnChanged::turnId, MentorLedgerEvents.TurnChanged::status)
                .as("the question in another conversation no longer waits for a free Heph")
                .contains(tuple(inThread.turn().getId(), MentorTurn.Status.QUEUED));
        assertThat(waits.waits(workspace.getId(), List.of(stored(inThread.turn()))))
                .isEmpty();
    }

    @Test
    void shouldPublishTheFollowUpAgainWhenTheQuestionBeforeItIsDeleted() {
        MentorTurnService.Submission first = ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        published.clear();

        entryService.deleteMessage(workspace.getId(), first.question().getId());

        assertThat(published.stream(MentorLedgerEvents.TurnChanged.class))
                .extracting(MentorLedgerEvents.TurnChanged::turnId, MentorLedgerEvents.TurnChanged::status)
                .contains(tuple(followUp.turn().getId(), MentorTurn.Status.QUEUED));
    }

    @Test
    void shouldRejectAWriteWhenTheAttemptNoLongerHoldsTheLease() {
        ask(main, "Question");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();

        assertThatThrownBy(() -> turns.updatePartial(
                        running.getId(), running.getAttempt(), "another-worker", JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(MentorLeaseLostException.class);
        assertThatThrownBy(() -> turns.end(
                        running.getId(), running.getAttempt() + 1, WORKER, MentorTurn.Status.DONE, List.of(), null))
                .isInstanceOf(MentorLeaseLostException.class);
    }

    @Test
    void shouldStoreThePiEntriesAndRecordTheQuestionsPiEntryWhenTheHostEndsTheTurn() {
        MentorTurnService.Submission submission = ask(main, "Question");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        String userPiId = MentorLedgerFixtures.piId();

        turns.end(
                running.getId(),
                running.getAttempt(),
                WORKER,
                MentorTurn.Status.DONE,
                List.of(
                        MentorLedgerFixtures.thinkingLevel(MentorLedgerFixtures.piId()),
                        MentorLedgerFixtures.user(userPiId, "rendered prompt"),
                        MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Answer")),
                null);

        List<MentorEntry> stored = MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), running.getId());
        // The question was stored when it was asked; Pi's entry for it updates that row.
        assertThat(stored)
                .extracting(MentorEntry::getKind)
                .containsExactly(MentorEntry.Kind.USER, MentorEntry.Kind.SYSTEM, MentorEntry.Kind.HEPH);
        MentorEntry question =
                entryRepository.findById(submission.question().getId()).orElseThrow();
        assertThat(MentorLedgerFixtures.piIdOf(question)).isEqualTo(userPiId);
        assertThat(question.getText()).isEqualTo("Question");
        MentorEntry answer = entryRepository.findById(stored.getLast().getId()).orElseThrow();
        assertThat(answer.getText()).isEqualTo("Answer");
        assertThat(answer.getTurnId()).isEqualTo(running.getId());
        assertThat(answer.getSurface()).isEqualTo(MentorEntry.Surface.APP);
    }

    @Test
    void shouldKeepOnlyTheSummaryOfAFailedTurnSoTheNextTurnStartsFromIt() {
        MentorTurnService.Submission submission = ask(main, "Question");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();

        turns.end(
                running.getId(),
                running.getAttempt(),
                WORKER,
                MentorTurn.Status.FAILED,
                List.of(
                        MentorLedgerFixtures.compaction(
                                MentorLedgerFixtures.piId(),
                                "The summary.",
                                MentorLedgerFixtures.piIdOf(submission.question())),
                        MentorLedgerFixtures.user(MentorLedgerFixtures.piId(), "rendered prompt"),
                        MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "PARTIAL-ANSWER-NOT-STORED")),
                "its Heph host reported ERROR");

        List<MentorEntry> stored = MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), running.getId());
        assertThat(stored)
                .extracting(MentorEntry::getKind)
                .containsExactly(MentorEntry.Kind.USER, MentorEntry.Kind.SUMMARY);
        assertThat(stored).extracting(MentorEntry::getText).doesNotContain("PARTIAL-ANSWER-NOT-STORED");
    }

    @Test
    void shouldStoreNothingOfTheTurnWhenTheDeveloperDeletedTheQuestionWhileHephAnswered() {
        MentorTurnService.Submission submission = ask(main, "Question with a secret");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        entryService.deleteMessage(workspace.getId(), submission.question().getId());

        turns.end(
                running.getId(),
                running.getAttempt(),
                WORKER,
                MentorTurn.Status.STOPPED,
                answerThatReadContext("Half an answer about the secret"),
                null);

        MentorEntry question =
                entryRepository.findById(submission.question().getId()).orElseThrow();
        assertThat(question.isRedacted()).isTrue();
        assertThat(question.getPayload()).isNull();
        assertThat(storedFor(running)).extracting(MentorEntry::getId).containsExactly(question.getId());
        assertThat(stored(running).getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(stored(running).isLeased()).isFalse();
    }

    @Test
    void shouldStoreNothingOfASlackTurnWhenItsSlackContentWasErasedWhileHephAnswered() {
        MentorTurnService.Submission submission = turns.submit(
                workspace.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.SLACK,
                        "Question in Slack",
                        UUID.randomUUID().toString(),
                        "17.01",
                        null));
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        entryService.redactSlackEntries(workspace.getId());

        turns.end(
                running.getId(),
                running.getAttempt(),
                WORKER,
                MentorTurn.Status.DONE,
                answerThatReadContext("An answer for Slack"),
                null);

        assertThat(storedFor(running))
                .extracting(MentorEntry::getId)
                .containsExactly(submission.question().getId());
        assertThat(stored(running).getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
    }

    @Test
    void shouldKeepNoFailureReasonWhenAStoppedTurnsLeaseExpires() {
        ask(main, "Question");
        MentorTurn running = turns.claimNext(WORKER, EXPIRED).orElseThrow();
        turns.stop(workspace.getId(), running.getId());

        assertThat(turns.expireLease(running.getId())).isPresent();

        MentorTurn stopped = stored(running);
        assertThat(stopped.getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(stopped.getError()).isNull();
    }

    @Test
    void shouldStoreThePartialAnswerOfARunningAttemptAndRefuseOneAfterTheDeveloperStoppedIt() {
        ask(main, "Question");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        var snapshot = JsonNodeFactory.instance.objectNode().put("text", "Thinking");

        turns.updatePartial(running.getId(), running.getAttempt(), WORKER, snapshot);
        assertThat(stored(running).getPartial()).isEqualTo(snapshot);

        turns.stop(workspace.getId(), running.getId());
        assertThatThrownBy(() -> turns.updatePartial(running.getId(), running.getAttempt(), WORKER, snapshot))
                .isInstanceOf(MentorLeaseLostException.class);
        assertThat(stored(running).getPartial()).isNull();
    }

    @Test
    void shouldStopAQueuedTurnAtOnceAndKeepItsQuestionWhenTheDeveloperStopsIt() {
        MentorTurnService.Submission submission = ask(main, "Question");

        MentorTurn stopped = turns.stop(workspace.getId(), submission.turn().getId());

        assertThat(stopped.getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(stored(stopped).isLeased()).isFalse();
        assertThat(entryRepository.findById(submission.question().getId())).isPresent();
        turns.claimNext(WORKER, LEASE);
        assertThat(stored(stopped).getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
    }

    @Test
    void shouldLetTheHostCommitTheAbortedAnswerBeforeTheNextTurnStartsWhenARunningTurnIsStopped() {
        ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();

        turns.stop(workspace.getId(), running.getId());
        turns.claimNext(WORKER, LEASE);
        assertThat(stored(followUp.turn()).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        turns.end(
                running.getId(),
                running.getAttempt(),
                WORKER,
                MentorTurn.Status.DONE,
                List.of(MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Partial answer")),
                null);

        MentorTurn ended = stored(running);
        assertThat(ended.getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(ended.isLeased()).isFalse();
        assertThat(turns.answer(workspace.getId(), running.getId()).answerEntryId())
                .isNotNull();
        assertThat(turns.claimNext(WORKER, LEASE))
                .hasValueSatisfying(next ->
                        assertThat(next.getId()).isEqualTo(followUp.turn().getId()));
    }

    @Test
    void shouldRetryOnceAndThenFailWhenTheLeaseExpiresTwice() {
        ask(main, "Question");
        MentorTurn first = turns.claimNext(WORKER, EXPIRED).orElseThrow();
        turns.updatePartial(
                first.getId(),
                first.getAttempt(),
                WORKER,
                JsonNodeFactory.instance.objectNode().put("activity", "writing"));

        assertThat(turns.expiredLeases()).contains(first.getId());
        MentorTurnService.LostAttempt retried = turns.expireLease(first.getId()).orElseThrow();
        assertThat(retried.attempt()).isEqualTo(1);
        assertThat(stored(first).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(turns.answer(workspace.getId(), first.getId()).answerEntryId())
                .as("a lost attempt stored nothing")
                .isNull();
        assertThat(stored(first).getPartial()).isNull();

        MentorTurn second = turns.claimNext(WORKER, EXPIRED).orElseThrow();
        assertThat(second.getAttempt()).isEqualTo(2);
        assertThat(turns.expireLease(second.getId())).isPresent();

        MentorTurn failed = stored(first);
        assertThat(failed.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(failed.getError()).isNotBlank();
        assertThat(failed.getFailureCause())
                .as("the cause outlives the worker that lost the lease")
                .isEqualTo("its lease expired on its last attempt");
        assertThat(turns.expireLease(first.getId())).isEmpty();
    }

    @Test
    void shouldKeepALiveLeaseFromTheReaperWhenTheWorkersClockRunsBehindTheServers() {
        ask(main, "Question");
        // The worker's clock runs ten minutes behind the server whose reaper judges the lease.
        var behind = new MentorTurnService(
                conversationRepository,
                entryRepository,
                turnRepository,
                entryService,
                redaction,
                copies,
                writeFence,
                events,
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-10)),
                3);
        MentorTurn running = Objects.requireNonNull(transactionTemplate.execute(
                tx -> behind.claimNext(WORKER, Duration.ofMinutes(1)).orElseThrow()));
        transactionTemplate.executeWithoutResult(
                tx -> behind.renewLease(running.getId(), running.getAttempt(), WORKER, Duration.ofMinutes(1)));

        assertThat(turns.expiredLeases()).doesNotContain(running.getId());
        assertThat(turns.expireLease(running.getId())).isEmpty();
        assertThat(stored(running).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
    }

    @Test
    void shouldKeepTheTurnFromTheReaperWhenTheHostRenewsItsLease() {
        ask(main, "Question");
        MentorTurn running = turns.claimNext(WORKER, EXPIRED).orElseThrow();

        turns.renewLease(running.getId(), running.getAttempt(), WORKER, LEASE);

        assertThat(turns.expiredLeases()).doesNotContain(running.getId());
        assertThat(turns.expireLease(running.getId())).isEmpty();
    }

    @Test
    void shouldKeepTheProxysCountsWhenAStaleTurnSnapshotIsSavedAfterThem() {
        ask(main, "Question");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();

        transactionTemplate.executeWithoutResult(tx -> {
            MentorTurn stale = turnRepository.findById(running.getId()).orElseThrow();
            turnRepository.accumulateUsage(
                    running.getId(), running.getAttempt(), new MentorTurnUsage(4_242, 99, 0, 0, 0, 1));
            stale.progress(JsonNodeFactory.instance.objectNode().put("text", "stale"));
            turnRepository.saveAndFlush(stale);
        });

        MentorTurn row = stored(running);
        assertThat(row.getInputTokens()).isEqualTo(4_242);
        assertThat(row.getOutputTokens()).isEqualTo(99);
        assertThat(row.getLlmCalls()).isEqualTo(1);
    }

    @Test
    void shouldNameTheLastAnswerAndOnlyTheFeedbackItShowedWhenTheTurnShowedSome() {
        ask(main, "Question");
        MentorTurn running = turns.claimNext(WORKER, LEASE).orElseThrow();
        UUID shown = UUID.randomUUID();
        UUID refused = UUID.randomUUID();
        Map<String, UUID> calls = new LinkedHashMap<>();
        calls.put("call-1", shown);
        calls.put("call-2", refused);
        turns.end(
                running.getId(),
                running.getAttempt(),
                WORKER,
                MentorTurn.Status.DONE,
                List.of(
                        MentorLedgerFixtures.showFeedback(
                                MentorLedgerFixtures.piId(), "Look at this", calls, "Name why"),
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(), "call-1", "show_feedback", "Shown", false),
                        MentorLedgerFixtures.toolResult(
                                MentorLedgerFixtures.piId(), "call-2", "show_feedback", "Refused", true),
                        MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Done")),
                null);

        MentorTurnService.Answer answer = turns.answer(workspace.getId(), running.getId());

        List<MentorEntry> written = MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), running.getId());
        assertThat(answer.answerEntryId()).isEqualTo(written.getLast().getId());
        UUID showing = written.stream()
                .filter(entry -> entry.getKind() == MentorEntry.Kind.HEPH)
                .findFirst()
                .orElseThrow()
                .getId();
        assertThat(answer.shownIn()).containsExactly(entry(shown, showing));
    }
}
