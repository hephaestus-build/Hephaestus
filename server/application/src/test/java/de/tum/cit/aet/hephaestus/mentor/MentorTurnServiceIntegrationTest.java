package de.tum.cit.aet.hephaestus.mentor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.TurnRunsAdapter;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunReaper;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
    private AgentCapacityProperties capacity;

    @Autowired
    private TurnRunsAdapter turnRuns;

    @Autowired
    private AgentRunReaper reaper;

    @Autowired
    private MentorTurnClaims claims;

    @Autowired
    private EntityManager entityManager;

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
    private ApplicationEventPublisher events;

    @Autowired
    private ApplicationEvents published;

    private Workspace workspace;
    private MentorConversation main;
    private long account;

    @BeforeEach
    void setUpLedger() {
        User developer = persistUser("heph-turns-developer");
        workspace = createWorkspace("heph-turns", "Hephaestus", "heph-org", AccountType.ORG, developer);
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
        MentorTurn running = claims.claimNext().orElseThrow();
        requestErasure();

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new MentorTurnService.Ending(
                        MentorTurn.Status.DONE,
                        List.of(MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Words about the developer")),
                        null,
                        null));

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

        MentorTurn claimedFirst = claims.claimNext().orElseThrow();
        MentorTurn claimedSecond = claims.claimNext().orElseThrow();

        assertThat(claimedFirst.getId()).isEqualTo(first.turn().getId());
        assertThat(claimedSecond.getId()).isEqualTo(inThread.turn().getId());
        claims.claimNext();
        assertThat(stored(followUp.turn()).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(stored(claimedFirst).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
        assertThat(stored(claimedFirst).getAttempt()).isEqualTo(1);
        assertThat(stored(claimedFirst).getRunId()).isEqualTo(claimedFirst.getRunId());
    }

    @Test
    void shouldStartTheFollowUpWhenTheRunningTurnEnds() {
        ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        MentorTurn running = claims.claimNext().orElseThrow();

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new MentorTurnService.Ending(MentorTurn.Status.DONE, List.of(), null, null));

        assertThat(claims.claimNext())
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

        assertThat(claims.claimNext())
                .hasValueSatisfying(
                        next -> assertThat(next.getId()).isEqualTo(first.turn().getId()));
        assertThat(claims.claimNext())
                .as("the follow-up waits for the question before it")
                .isEmpty();
        assertThat(stored(followUp.turn()).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
    }

    /** The waiting reasons for a deployment with {@code capacity} Hephaestus slots. */
    private MentorTurnWaits waits(@Nullable Integer capacity) {
        return new MentorTurnWaits(turnRepository, events, capacity);
    }

    /** A question in a workspace of its own, asked by a developer of its own. */
    private MentorTurnService.Submission askElsewhere(String slug, String text) {
        User other = persistUser(slug + "-developer");
        Workspace elsewhere = createWorkspace(slug, "Other", slug + "-org", AccountType.ORG, other);
        MentorConversation otherMain = conversations.findOrCreateMain(elsewhere.getId(), accountId(other));
        return turns.submit(
                elsewhere.getId(),
                otherMain.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    @Test
    void shouldSayAQuestionWaitsForAFreeHephOnlyWhenEveryHephSlotIsTaken() {
        MentorTurnService.Submission first = ask(main, "First");
        MentorTurnService.Submission waiting = askElsewhere("heph-slots", "Elsewhere");
        MentorTurn shown = stored(waiting.turn());
        int slots = Objects.requireNonNull(capacity.turns());

        assertThat(waits(slots).waits(shown.getWorkspaceId(), List.of(shown)))
                .as("the slot is free, so the question may start at once")
                .isEmpty();

        claims.claim(first.turn());
        assertThat(waits(slots + 1).waits(shown.getWorkspaceId(), List.of(stored(waiting.turn()))))
                .as("an additional slot would let this question start")
                .isEmpty();
        assertThat(waits(slots).waits(shown.getWorkspaceId(), List.of(stored(waiting.turn()))))
                .as("every workspace shares the configured slot, which is taken")
                .containsExactly(entry(waiting.turn().getId(), MentorTurn.WaitingFor.FREE_HEPH));
        assertThat(waits(null).waits(shown.getWorkspaceId(), List.of(stored(waiting.turn()))))
                .as("without a capacity, the server does not know that every slot is taken")
                .isEmpty();
    }

    @Test
    void shouldGiveAFollowUpNoReasonWhileTheQuestionBeforeItWaitsOrRuns() {
        ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        assertThat(claims.claimNext()).isPresent();

        assertThat(waits(1).waits(workspace.getId(), List.of(stored(followUp.turn()))))
                .as("it waits for its own conversation, not for a free Hephaestus")
                .isEmpty();
    }

    /** The queued turns published since the last {@code published.clear()}. */
    private List<UUID> publishedQueued() {
        return published.stream(MentorLedgerEvents.TurnChanged.class)
                .filter(change -> change.status() == MentorTurn.Status.QUEUED)
                .map(MentorLedgerEvents.TurnChanged::turnId)
                .toList();
    }

    @Test
    void shouldPublishTheQuestionsOfOtherConversationsAgainWhenATurnStarts() {
        MentorTurnService.Submission first = ask(main, "First");
        MentorTurnService.Submission inThread =
                ask(conversations.openThread(workspace.getId(), first.question().getId()), "About this");
        published.clear();

        assertThat(claims.claimNext()).isPresent();

        assertThat(publishedQueued())
                .as("the start can have taken the last Hephaestus slot")
                .contains(inThread.turn().getId());
    }

    @Test
    void shouldPublishTheQuestionsOfOtherConversationsAgainWhenALostAttemptIsQueuedAgain() {
        MentorTurnService.Submission first = ask(main, "First");
        MentorTurnService.Submission inThread =
                ask(conversations.openThread(workspace.getId(), first.question().getId()), "About this");
        startedTurn(first);
        published.clear();

        assertThat(turns.requeueRunning(workspace.getId(), main.getId())).isPresent();

        assertThat(publishedQueued())
                .as("the requeue gave a Hephaestus slot back")
                .contains(inThread.turn().getId(), first.turn().getId());
    }

    @Test
    void shouldPublishOnlyTheFollowUpsOfItsConversationWhenAQueuedQuestionIsStopped() {
        MentorTurnService.Submission first = ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        MentorTurnService.Submission inThread =
                ask(conversations.openThread(workspace.getId(), first.question().getId()), "About this");
        published.clear();

        turns.stop(workspace.getId(), first.turn().getId());

        assertThat(publishedQueued())
                .as("a stop of a question that never started gives back no Hephaestus slot")
                .contains(followUp.turn().getId())
                .doesNotContain(inThread.turn().getId());
    }

    @Test
    void shouldPublishTheFollowUpAgainWhenTheTurnBeforeItEnds() {
        MentorTurnService.Submission first = ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        MentorTurn running = startedTurn(first);
        published.clear();

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new MentorTurnService.Ending(MentorTurn.Status.DONE, List.of(), null, null));

        assertThat(publishedQueued())
                .as("the follow-up is now the one its conversation starts next")
                .contains(followUp.turn().getId());
    }

    @Test
    void shouldPublishTheQuestionsOfOtherWorkspacesAgainWhenATurnEnds() {
        MentorTurnService.Submission first = ask(main, "First");
        MentorTurn running = startedTurn(first);
        MentorTurnService.Submission elsewhere = askElsewhere("heph-slots-end", "Elsewhere");
        published.clear();

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new MentorTurnService.Ending(MentorTurn.Status.DONE, List.of(), null, null));

        assertThat(publishedQueued())
                .as("every workspace shares the Hephaestus slot that the end gave back")
                .contains(elsewhere.turn().getId());
    }

    @Test
    void shouldPublishTheFollowUpAgainWhenTheQuestionBeforeItIsDeleted() {
        MentorTurnService.Submission first = ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        published.clear();

        entryService.deleteMessage(workspace.getId(), first.question().getId());

        assertThat(publishedQueued()).contains(followUp.turn().getId());
    }

    /** Claims turns until the submitted one starts; a turn of another test that starts first is left running. */
    private MentorTurn startedTurn(MentorTurnService.Submission submitted) {
        while (true) {
            MentorTurn claimed = claims.claimNext().orElseThrow();
            if (claimed.getId().equals(submitted.turn().getId())) {
                return claimed;
            }
        }
    }

    @Test
    void shouldRejectAWriteWhenTheAttemptNoLongerHoldsTheLease() {
        ask(main, "Question");
        MentorTurn running = claims.claimNext().orElseThrow();

        assertThatThrownBy(() -> turns.updatePartial(
                        running.getWorkspaceId(),
                        running.getId(),
                        running.getAttempt(),
                        UUID.randomUUID(),
                        MentorPartialUpdates.whole(JsonNodeFactory.instance.objectNode())))
                .isInstanceOf(MentorLeaseLostException.class);
        assertThatThrownBy(() -> turns.end(
                        running.getWorkspaceId(),
                        running.getId(),
                        running.getAttempt() + 1,
                        Objects.requireNonNull(running.getRunId()),
                        new MentorTurnService.Ending(MentorTurn.Status.DONE, List.of(), null, null)))
                .isInstanceOf(MentorLeaseLostException.class);
    }

    @Test
    void shouldRefuseEveryAttemptOperationAfterItsLeaseExpiresBeforeTheReaperRuns() {
        ask(main, "Question");
        MentorTurn expired = claims.claimNext().orElseThrow();
        claims.expireLease(expired);
        UUID leaseId = Objects.requireNonNull(expired.getRunId());
        long workspaceId = expired.getWorkspaceId();
        UUID turnId = expired.getId();
        int attempt = expired.getAttempt();
        JsonNode partial = JsonNodeFactory.instance.objectNode().put("activity", "writing");

        assertThat(turnRepository.hasLiveLease(turnId, workspaceId, attempt, leaseId))
                .isFalse();
        assertThat(turnRuns.renew(workspaceId, leaseId, attempt)).isFalse();
        assertThatThrownBy(() -> turns.admit(workspaceId, turnId, attempt, leaseId, partial))
                .isInstanceOf(MentorLeaseLostException.class);
        assertThatThrownBy(() ->
                        turns.updatePartial(workspaceId, turnId, attempt, leaseId, MentorPartialUpdates.whole(partial)))
                .isInstanceOf(MentorLeaseLostException.class);
        assertThatThrownBy(() -> turns.end(
                        workspaceId,
                        turnId,
                        attempt,
                        leaseId,
                        new MentorTurnService.Ending(MentorTurn.Status.DONE, List.of(), null, null)))
                .isInstanceOf(MentorLeaseLostException.class);
        assertThat(stored(expired).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
    }

    @Test
    void shouldRefuseAnotherWorkspaceEvenWithTheCorrectLeaseIdentity() {
        ask(main, "Question");
        MentorTurn running = claims.claimNext().orElseThrow();
        UUID leaseId = Objects.requireNonNull(running.getRunId());

        assertThat(turnRepository.hasLiveLease(
                        running.getId(), running.getWorkspaceId(), running.getAttempt(), leaseId))
                .isTrue();
        assertThat(turnRepository.hasLiveLease(
                        running.getId(), running.getWorkspaceId() + 1, running.getAttempt(), leaseId))
                .isFalse();
        assertThat(turnRuns.renew(running.getWorkspaceId() + 1, leaseId, running.getAttempt()))
                .isFalse();
        assertThatThrownBy(() -> turns.updatePartial(
                        running.getWorkspaceId() + 1,
                        running.getId(),
                        running.getAttempt(),
                        leaseId,
                        MentorPartialUpdates.whole(JsonNodeFactory.instance.objectNode())))
                .isInstanceOf(MentorLeaseLostException.class);
    }

    @Test
    void shouldRunTheNextAttemptUnderTheNextClaimOfTheTurnsRun() {
        ask(main, "Question");
        MentorTurn first = claims.claimNext().orElseThrow();
        claims.takeBack(first);
        MentorTurn next = claims.claimNext().orElseThrow();

        assertThat(next.getRunId()).isEqualTo(first.getRunId());
        assertThat(next.getAttempt()).isEqualTo(first.getAttempt() + 1);
        assertThat(turnRuns.renew(next.getWorkspaceId(), next.getRunId(), first.getAttempt()))
                .as("the lost claim renews nothing")
                .isFalse();
        assertThat(turnRuns.renew(next.getWorkspaceId(), next.getRunId(), next.getAttempt()))
                .isTrue();
    }

    @Test
    void shouldStoreThePiEntriesAndRecordTheQuestionsPiEntryWhenTheHostEndsTheTurn() {
        MentorTurnService.Submission submission = ask(main, "Question");
        MentorTurn running = claims.claimNext().orElseThrow();
        String userPiId = MentorLedgerFixtures.piId();

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new MentorTurnService.Ending(
                        MentorTurn.Status.DONE,
                        List.of(
                                MentorLedgerFixtures.thinkingLevel(MentorLedgerFixtures.piId()),
                                MentorLedgerFixtures.user(userPiId, "rendered prompt"),
                                MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Answer")),
                        null,
                        null));

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
        MentorTurn running = claims.claimNext().orElseThrow();

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new MentorTurnService.Ending(
                        MentorTurn.Status.FAILED,
                        List.of(
                                MentorLedgerFixtures.compaction(
                                        MentorLedgerFixtures.piId(),
                                        "The summary.",
                                        MentorLedgerFixtures.piIdOf(submission.question())),
                                MentorLedgerFixtures.user(MentorLedgerFixtures.piId(), "rendered prompt"),
                                MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "PARTIAL-ANSWER-NOT-STORED")),
                        null,
                        "its Hephaestus host reported ERROR"));

        List<MentorEntry> stored = MentorLedgerFixtures.turnEntries(entityManager, workspace.getId(), running.getId());
        assertThat(stored)
                .extracting(MentorEntry::getKind)
                .containsExactly(MentorEntry.Kind.USER, MentorEntry.Kind.SUMMARY);
        assertThat(stored).extracting(MentorEntry::getText).doesNotContain("PARTIAL-ANSWER-NOT-STORED");
    }

    @Test
    void shouldStoreNothingOfTheTurnWhenTheDeveloperDeletedTheQuestionWhileHephAnswered() {
        MentorTurnService.Submission submission = ask(main, "Question with a secret");
        MentorTurn running = claims.claimNext().orElseThrow();
        entryService.deleteMessage(workspace.getId(), submission.question().getId());

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new MentorTurnService.Ending(
                        MentorTurn.Status.STOPPED,
                        answerThatReadContext("Half an answer about the secret"),
                        null,
                        null));

        MentorEntry question =
                entryRepository.findById(submission.question().getId()).orElseThrow();
        assertThat(question.isRedacted()).isTrue();
        assertThat(question.getPayload()).isNull();
        assertThat(storedFor(running)).extracting(MentorEntry::getId).containsExactly(question.getId());
        assertThat(stored(running).getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(turns.isHeld(stored(running))).isFalse();
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
        MentorTurn running = claims.claimNext().orElseThrow();
        entryService.redactSlackEntries(workspace.getId());

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new MentorTurnService.Ending(
                        MentorTurn.Status.DONE, answerThatReadContext("An answer for Slack"), null, null));

        assertThat(storedFor(running))
                .extracting(MentorEntry::getId)
                .containsExactly(submission.question().getId());
        assertThat(stored(running).getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
    }

    @Test
    void shouldKeepNoFailureReasonWhenAStoppedTurnsLeaseExpires() {
        ask(main, "Question");
        MentorTurn running = claims.claimNext().orElseThrow();
        turns.stop(workspace.getId(), running.getId());

        claims.takeBack(running);

        MentorTurn stopped = stored(running);
        assertThat(stopped.getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(stopped.getError()).isNull();
    }

    @Test
    void shouldSendThePartialAnswerWithoutWritingTheTurnAndRefuseOneAfterTheDeveloperStoppedIt() {
        ask(main, "Question");
        MentorTurn running = claims.claimNext().orElseThrow();
        var snapshot = JsonNodeFactory.instance.objectNode().put("text", "Thinking");
        String version = rowVersion(running);

        turns.updatePartial(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                MentorPartialUpdates.whole(snapshot));

        assertThat(rowVersion(running)).as("a partial answer writes no row").isEqualTo(version);
        assertThat(published.stream(MentorLedgerEvents.ProgressChanged.class))
                .anySatisfy(frame -> assertThat(frame.turnId()).isEqualTo(running.getId()));
        turns.stop(workspace.getId(), running.getId());
        assertThatThrownBy(() -> turns.updatePartial(
                        running.getWorkspaceId(),
                        running.getId(),
                        running.getAttempt(),
                        Objects.requireNonNull(running.getRunId()),
                        MentorPartialUpdates.whole(snapshot)))
                .isInstanceOf(MentorLeaseLostException.class);
    }

    private String rowVersion(MentorTurn turn) {
        return (String) entityManager
                .createNativeQuery("SELECT xmin::text FROM mentor_turn WHERE workspace_id = :workspaceId AND id = :id")
                .setParameter("workspaceId", turn.getWorkspaceId())
                .setParameter("id", turn.getId())
                .getSingleResult();
    }

    @Test
    void shouldStopAQueuedTurnAtOnceAndKeepItsQuestionWhenTheDeveloperStopsIt() {
        MentorTurnService.Submission submission = ask(main, "Question");

        MentorTurn stopped = turns.stop(workspace.getId(), submission.turn().getId());

        assertThat(stopped.getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(turns.isHeld(stored(stopped))).isFalse();
        assertThat(entryRepository.findById(submission.question().getId())).isPresent();
        claims.claimNext();
        assertThat(stored(stopped).getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
    }

    @Test
    void shouldLetTheHostCommitTheAbortedAnswerBeforeTheNextTurnStartsWhenARunningTurnIsStopped() {
        ask(main, "First");
        MentorTurnService.Submission followUp = ask(main, "Follow-up");
        MentorTurn running = claims.claimNext().orElseThrow();

        turns.stop(workspace.getId(), running.getId());
        claims.claimNext();
        assertThat(stored(followUp.turn()).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new MentorTurnService.Ending(
                        MentorTurn.Status.DONE,
                        List.of(MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Partial answer")),
                        null,
                        null));

        MentorTurn ended = stored(running);
        assertThat(ended.getStatus()).isEqualTo(MentorTurn.Status.STOPPED);
        assertThat(turns.isHeld(ended)).isFalse();
        assertThat(turns.answer(workspace.getId(), running.getId()).answerEntryId())
                .isNotNull();
        assertThat(claims.claimNext())
                .hasValueSatisfying(next ->
                        assertThat(next.getId()).isEqualTo(followUp.turn().getId()));
    }

    @Test
    void shouldRetryOnceAndThenFailWhenTheLeaseExpiresTwice() {
        ask(main, "Question");
        MentorTurn first = claims.claimNext().orElseThrow();
        turns.updatePartial(
                first.getWorkspaceId(),
                first.getId(),
                first.getAttempt(),
                Objects.requireNonNull(first.getRunId()),
                MentorPartialUpdates.whole(JsonNodeFactory.instance.objectNode().put("activity", "writing")));

        MentorTurnService.LostAttempt retried = claims.takeBackUnbilled(first);
        assertThat(retried.attempt()).isEqualTo(1);
        assertThat(stored(first).getStatus()).isEqualTo(MentorTurn.Status.QUEUED);
        assertThat(turns.answer(workspace.getId(), first.getId()).answerEntryId())
                .as("a lost attempt stored nothing")
                .isNull();

        MentorTurn second = claims.claimNext().orElseThrow();
        assertThat(second.getAttempt()).isEqualTo(2);
        claims.takeBack(second);

        MentorTurn failed = stored(first);
        assertThat(failed.getStatus()).isEqualTo(MentorTurn.Status.FAILED);
        assertThat(failed.getError()).isNotBlank();
        assertThat(failed.getFailureCause())
                .as("the cause outlives the worker that lost the lease")
                .isEqualTo("its lease expired on its last attempt");
    }

    @Test
    void shouldKeepTheTurnFromTheReaperWhenTheHostRenewsItsLease() {
        ask(main, "Question");
        MentorTurn running = claims.claimNext().orElseThrow();

        assertThat(turnRuns.renew(running.getWorkspaceId(), running.getRunId(), running.getAttempt()))
                .isTrue();
        reaper.reap();

        assertThat(stored(running).getStatus()).isEqualTo(MentorTurn.Status.RUNNING);
    }

    @Test
    void shouldRewriteTheRunningTurnInPlaceWhenItsHostAdmits() {
        ask(main, "Question");

        long[] counts = transactionTemplate.execute(tx -> {
            MentorTurn running = claims.claimNext().orElseThrow();
            entityManager.flush();
            long[] before = turnUpdatesInThisTransaction();
            UUID leaseId = Objects.requireNonNull(running.getRunId());
            turns.admit(
                    running.getWorkspaceId(),
                    running.getId(),
                    running.getAttempt(),
                    leaseId,
                    JsonNodeFactory.instance.objectNode().put("model", "admitted"));
            entityManager.flush();
            long[] after = turnUpdatesInThisTransaction();
            return new long[] {after[0] - before[0], after[1] - before[1]};
        });

        assertThat(counts)
                .as("updates, and those that added no index entry (HOT)")
                .containsExactly(1, 1);
    }

    /** This transaction's updates of {@code mentor_turn}, and how many of them were HOT, so far. */
    private long[] turnUpdatesInThisTransaction() {
        Object[] row = (Object[]) entityManager
                .createNativeQuery("SELECT n_tup_upd, n_tup_hot_upd FROM pg_stat_xact_user_tables"
                        + " WHERE relid = 'mentor_turn'::regclass")
                .getSingleResult();
        return new long[] {((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    @Test
    void shouldNameTheLastAnswerAndOnlyTheFeedbackItShowedWhenTheTurnShowedSome() {
        ask(main, "Question");
        MentorTurn running = claims.claimNext().orElseThrow();
        UUID shown = UUID.randomUUID();
        UUID refused = UUID.randomUUID();
        Map<String, UUID> calls = new LinkedHashMap<>();
        calls.put("call-1", shown);
        calls.put("call-2", refused);
        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new MentorTurnService.Ending(
                        MentorTurn.Status.DONE,
                        List.of(
                                MentorLedgerFixtures.showFeedback(
                                        MentorLedgerFixtures.piId(), "Look at this", calls, "Name why"),
                                MentorLedgerFixtures.toolResult(
                                        MentorLedgerFixtures.piId(), "call-1", "show_feedback", "Shown", false),
                                MentorLedgerFixtures.toolResult(
                                        MentorLedgerFixtures.piId(), "call-2", "show_feedback", "Refused", true),
                                MentorLedgerFixtures.answer(MentorLedgerFixtures.piId(), "Done")),
                        null,
                        null));

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
