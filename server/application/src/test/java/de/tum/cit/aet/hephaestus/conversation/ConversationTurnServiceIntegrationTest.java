package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.conversation.turn.TurnRunsAdapter;
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
class ConversationTurnServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private AgentCapacityProperties capacity;

    @Autowired
    private TurnRunsAdapter turnRuns;

    @Autowired
    private AgentRunReaper reaper;

    @Autowired
    private ConversationTurnClaims claims;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationTurnRepository turnRepository;

    @Autowired
    private ConversationEntryRepository entryRepository;

    @Autowired
    private ConversationEntryService entryService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private ApplicationEvents published;

    private Workspace workspace;
    private Conversation main;
    private long account;

    @BeforeEach
    void setUpLedger() {
        User developer = persistUser("ask-turns-developer");
        workspace = createWorkspace("ask-turns", "Hephaestus", "ask-org", AccountType.ORG, developer);
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
        ConversationTurn running = claims.claimNext().orElseThrow();
        requestErasure();

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(ConversationLedgerFixtures.answer(
                                ConversationLedgerFixtures.piId(), "Words about the developer")),
                        null,
                        null));

        assertThat(ConversationLedgerFixtures.turnEntries(entityManager, workspace.getId(), running.getId()))
                .extracting(ConversationEntry::getKind)
                .as("only the question stays; the erasure deletes it")
                .containsExactly(ConversationEntry.Kind.USER);
        assertThat(turnRepository.findById(running.getId()).orElseThrow().getStatus())
                .isEqualTo(ConversationTurn.Status.DONE);
        assertThatThrownBy(() -> ask(main, "Another question"))
                .isInstanceOfSatisfying(
                        ConversationRejectedException.class,
                        rejected -> assertThat(rejected.reason())
                                .isEqualTo(ConversationRejectedException.Reason.PERSON_ERASED));
    }

    private ConversationTurnService.Submission ask(Conversation conversation, String text) {
        return turns.submit(
                workspace.getId(),
                conversation.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    /** What a host sends at the end of a turn that read a context document and answered. */
    private static List<JsonNode> answerThatReadContext(String answer) {
        return List.of(
                ConversationLedgerFixtures.user(ConversationLedgerFixtures.piId(), "rendered prompt"),
                ConversationLedgerFixtures.toolCall(
                        ConversationLedgerFixtures.piId(),
                        "fetch-1",
                        "fetch_context",
                        JsonNodeFactory.instance.objectNode().put("path", "team.json")),
                ConversationLedgerFixtures.toolResult(
                        ConversationLedgerFixtures.piId(), "fetch-1", "fetch_context", "Alex reviews the API.", false),
                ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), answer));
    }

    private List<ConversationEntry> storedFor(ConversationTurn turn) {
        return ConversationLedgerFixtures.turnEntries(entityManager, workspace.getId(), turn.getId());
    }

    private ConversationTurn stored(ConversationTurn turn) {
        return turnRepository.findById(turn.getId()).orElseThrow();
    }

    @Test
    void shouldStoreTheQuestionAndAQueuedTurnWhenTheDeveloperAsks() {
        ConversationTurnService.Submission submission = ask(main, "How did my last review go?");

        ConversationEntry question =
                entryRepository.findById(submission.question().getId()).orElseThrow();
        assertThat(submission.created()).isTrue();
        assertThat(question.getKind()).isEqualTo(ConversationEntry.Kind.USER);
        assertThat(question.getTurnId()).isEqualTo(submission.turn().getId());
        assertThat(question.getRequestId()).isEqualTo(submission.turn().getRequestId());
        assertThat(stored(submission.turn()).getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
    }

    @Test
    void shouldRefuseAFourthFollowUpWhenThreeAlreadyWait() {
        ask(main, "One");
        ask(main, "Two");
        ask(main, "Three");

        assertThatThrownBy(() -> ask(main, "Four"))
                .isInstanceOfSatisfying(
                        ConversationRejectedException.class,
                        rejected -> assertThat(rejected.reason())
                                .isEqualTo(ConversationRejectedException.Reason.FOLLOW_UPS_FULL));
    }

    @Test
    void shouldRunOneTurnPerConversationAndThreadsInParallelWhenWorkersClaim() {
        ConversationTurnService.Submission first = ask(main, "First");
        ConversationTurnService.Submission followUp = ask(main, "Follow-up");
        Conversation thread =
                conversations.openThread(workspace.getId(), first.question().getId());
        ConversationTurnService.Submission inThread = ask(thread, "About this");

        ConversationTurn claimedFirst = claims.claimNext().orElseThrow();
        ConversationTurn claimedSecond = claims.claimNext().orElseThrow();

        assertThat(claimedFirst.getId()).isEqualTo(first.turn().getId());
        assertThat(claimedSecond.getId()).isEqualTo(inThread.turn().getId());
        claims.claimNext();
        assertThat(stored(followUp.turn()).getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
        assertThat(stored(claimedFirst).getStatus()).isEqualTo(ConversationTurn.Status.RUNNING);
        assertThat(stored(claimedFirst).getAttempt()).isEqualTo(1);
        assertThat(stored(claimedFirst).getRunId()).isEqualTo(claimedFirst.getRunId());
    }

    @Test
    void shouldStartTheFollowUpWhenTheRunningTurnEnds() {
        ask(main, "First");
        ConversationTurnService.Submission followUp = ask(main, "Follow-up");
        ConversationTurn running = claims.claimNext().orElseThrow();

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(ConversationTurn.Status.DONE, List.of(), null, null));

        assertThat(claims.claimNext())
                .hasValueSatisfying(next ->
                        assertThat(next.getId()).isEqualTo(followUp.turn().getId()));
    }

    @Test
    void shouldStartTheEarlierQuestionFirstWhenAnotherServersClockStampedTheFollowUpEarlier() {
        ConversationTurnService.Submission first = ask(main, "First");
        ConversationTurnService.Submission followUp = ask(main, "Follow-up");
        // A second server whose clock runs behind took the follow-up.
        transactionTemplate.executeWithoutResult(tx -> entityManager
                .createNativeQuery("""
                        UPDATE conversation_turn SET created_at = (SELECT created_at FROM conversation_turn
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
        assertThat(stored(followUp.turn()).getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
    }

    /** The waiting reasons for a deployment with {@code capacity} Hephaestus slots. */
    private ConversationTurnWaits waits(@Nullable Integer capacity) {
        return new ConversationTurnWaits(turnRepository, events, capacity);
    }

    /** A question in a workspace of its own, asked by a developer of its own. */
    private ConversationTurnService.Submission askElsewhere(String slug, String text) {
        User other = persistUser(slug + "-developer");
        Workspace elsewhere = createWorkspace(slug, "Other", slug + "-org", AccountType.ORG, other);
        Conversation otherMain = conversations.findOrCreateMain(elsewhere.getId(), accountId(other));
        return turns.submit(
                elsewhere.getId(),
                otherMain.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    @Test
    void shouldSayAQuestionWaitsForAFreeHephaestusOnlyWhenEveryHephaestusSlotIsTaken() {
        ConversationTurnService.Submission first = ask(main, "First");
        ConversationTurnService.Submission waiting = askElsewhere("ask-slots", "Elsewhere");
        ConversationTurn shown = stored(waiting.turn());
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
                .containsExactly(entry(waiting.turn().getId(), ConversationTurn.WaitingFor.CAPACITY));
        assertThat(waits(null).waits(shown.getWorkspaceId(), List.of(stored(waiting.turn()))))
                .as("without a capacity, the server does not know that every slot is taken")
                .isEmpty();
    }

    @Test
    void shouldGiveAFollowUpNoReasonWhileTheQuestionBeforeItWaitsOrRuns() {
        ask(main, "First");
        ConversationTurnService.Submission followUp = ask(main, "Follow-up");
        assertThat(claims.claimNext()).isPresent();

        assertThat(waits(1).waits(workspace.getId(), List.of(stored(followUp.turn()))))
                .as("it waits for its own conversation, not for a free Hephaestus")
                .isEmpty();
    }

    /** The queued turns published since the last {@code published.clear()}. */
    private List<UUID> publishedQueued() {
        return published.stream(ConversationLedgerEvents.TurnChanged.class)
                .filter(change -> change.status() == ConversationTurn.Status.QUEUED)
                .map(ConversationLedgerEvents.TurnChanged::turnId)
                .toList();
    }

    @Test
    void shouldPublishTheQuestionsOfOtherConversationsAgainWhenATurnStarts() {
        ConversationTurnService.Submission first = ask(main, "First");
        ConversationTurnService.Submission inThread =
                ask(conversations.openThread(workspace.getId(), first.question().getId()), "About this");
        published.clear();

        assertThat(claims.claimNext()).isPresent();

        assertThat(publishedQueued())
                .as("the start can have taken the last Hephaestus slot")
                .contains(inThread.turn().getId());
    }

    @Test
    void shouldPublishTheQuestionsOfOtherConversationsAgainWhenALostAttemptIsQueuedAgain() {
        ConversationTurnService.Submission first = ask(main, "First");
        ConversationTurnService.Submission inThread =
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
        ConversationTurnService.Submission first = ask(main, "First");
        ConversationTurnService.Submission followUp = ask(main, "Follow-up");
        ConversationTurnService.Submission inThread =
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
        ConversationTurnService.Submission first = ask(main, "First");
        ConversationTurnService.Submission followUp = ask(main, "Follow-up");
        ConversationTurn running = startedTurn(first);
        published.clear();

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(ConversationTurn.Status.DONE, List.of(), null, null));

        assertThat(publishedQueued())
                .as("the follow-up is now the one its conversation starts next")
                .contains(followUp.turn().getId());
    }

    @Test
    void shouldPublishTheQuestionsOfOtherWorkspacesAgainWhenATurnEnds() {
        ConversationTurnService.Submission first = ask(main, "First");
        ConversationTurn running = startedTurn(first);
        ConversationTurnService.Submission elsewhere = askElsewhere("ask-slots-end", "Elsewhere");
        published.clear();

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(ConversationTurn.Status.DONE, List.of(), null, null));

        assertThat(publishedQueued())
                .as("every workspace shares the Hephaestus slot that the end gave back")
                .contains(elsewhere.turn().getId());
    }

    @Test
    void shouldPublishTheFollowUpAgainWhenTheQuestionBeforeItIsDeleted() {
        ConversationTurnService.Submission first = ask(main, "First");
        ConversationTurnService.Submission followUp = ask(main, "Follow-up");
        published.clear();

        entryService.deleteMessage(workspace.getId(), first.question().getId());

        assertThat(publishedQueued()).contains(followUp.turn().getId());
    }

    /** Claims turns until the submitted one starts; a turn of another test that starts first is left running. */
    private ConversationTurn startedTurn(ConversationTurnService.Submission submitted) {
        while (true) {
            ConversationTurn claimed = claims.claimNext().orElseThrow();
            if (claimed.getId().equals(submitted.turn().getId())) {
                return claimed;
            }
        }
    }

    @Test
    void shouldRejectAWriteWhenTheAttemptNoLongerHoldsTheLease() {
        ask(main, "Question");
        ConversationTurn running = claims.claimNext().orElseThrow();

        assertThatThrownBy(() -> turns.updatePartial(
                        running.getWorkspaceId(),
                        running.getId(),
                        running.getAttempt(),
                        UUID.randomUUID(),
                        ConversationPartialUpdates.whole(JsonNodeFactory.instance.objectNode())))
                .isInstanceOf(ConversationLeaseLostException.class);
        assertThatThrownBy(() -> turns.end(
                        running.getWorkspaceId(),
                        running.getId(),
                        running.getAttempt() + 1,
                        Objects.requireNonNull(running.getRunId()),
                        new ConversationTurnService.Ending(ConversationTurn.Status.DONE, List.of(), null, null)))
                .isInstanceOf(ConversationLeaseLostException.class);
    }

    @Test
    void shouldRefuseEveryAttemptOperationAfterItsLeaseExpiresBeforeTheReaperRuns() {
        ask(main, "Question");
        ConversationTurn expired = claims.claimNext().orElseThrow();
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
                .isInstanceOf(ConversationLeaseLostException.class);
        assertThatThrownBy(() -> turns.updatePartial(
                        workspaceId, turnId, attempt, leaseId, ConversationPartialUpdates.whole(partial)))
                .isInstanceOf(ConversationLeaseLostException.class);
        assertThatThrownBy(() -> turns.end(
                        workspaceId,
                        turnId,
                        attempt,
                        leaseId,
                        new ConversationTurnService.Ending(ConversationTurn.Status.DONE, List.of(), null, null)))
                .isInstanceOf(ConversationLeaseLostException.class);
        assertThat(stored(expired).getStatus()).isEqualTo(ConversationTurn.Status.RUNNING);
    }

    @Test
    void shouldRefuseAnotherWorkspaceEvenWithTheCorrectLeaseIdentity() {
        ask(main, "Question");
        ConversationTurn running = claims.claimNext().orElseThrow();
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
                        ConversationPartialUpdates.whole(JsonNodeFactory.instance.objectNode())))
                .isInstanceOf(ConversationLeaseLostException.class);
    }

    @Test
    void shouldRunTheNextAttemptUnderTheNextClaimOfTheTurnsRun() {
        ask(main, "Question");
        ConversationTurn first = claims.claimNext().orElseThrow();
        claims.takeBack(first);
        ConversationTurn next = claims.claimNext().orElseThrow();

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
        ConversationTurnService.Submission submission = ask(main, "Question");
        ConversationTurn running = claims.claimNext().orElseThrow();
        String userPiId = ConversationLedgerFixtures.piId();

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(
                                ConversationLedgerFixtures.thinkingLevel(ConversationLedgerFixtures.piId()),
                                ConversationLedgerFixtures.user(userPiId, "rendered prompt"),
                                ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "Answer")),
                        null,
                        null));

        List<ConversationEntry> stored =
                ConversationLedgerFixtures.turnEntries(entityManager, workspace.getId(), running.getId());
        // The question was stored when it was asked; Pi's entry for it updates that row.
        assertThat(stored)
                .extracting(ConversationEntry::getKind)
                .containsExactly(
                        ConversationEntry.Kind.USER, ConversationEntry.Kind.SYSTEM, ConversationEntry.Kind.ANSWER);
        ConversationEntry question =
                entryRepository.findById(submission.question().getId()).orElseThrow();
        assertThat(ConversationLedgerFixtures.piIdOf(question)).isEqualTo(userPiId);
        assertThat(question.getText()).isEqualTo("Question");
        ConversationEntry answer =
                entryRepository.findById(stored.getLast().getId()).orElseThrow();
        assertThat(answer.getText()).isEqualTo("Answer");
        assertThat(answer.getTurnId()).isEqualTo(running.getId());
        assertThat(answer.getSurface()).isEqualTo(ConversationEntry.Surface.APP);
    }

    @Test
    void shouldKeepOnlyTheSummaryOfAFailedTurnSoTheNextTurnStartsFromIt() {
        ConversationTurnService.Submission submission = ask(main, "Question");
        ConversationTurn running = claims.claimNext().orElseThrow();

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.FAILED,
                        List.of(
                                ConversationLedgerFixtures.compaction(
                                        ConversationLedgerFixtures.piId(),
                                        "The summary.",
                                        ConversationLedgerFixtures.piIdOf(submission.question())),
                                ConversationLedgerFixtures.user(ConversationLedgerFixtures.piId(), "rendered prompt"),
                                ConversationLedgerFixtures.answer(
                                        ConversationLedgerFixtures.piId(), "PARTIAL-ANSWER-NOT-STORED")),
                        null,
                        "its Hephaestus host reported ERROR"));

        List<ConversationEntry> stored =
                ConversationLedgerFixtures.turnEntries(entityManager, workspace.getId(), running.getId());
        assertThat(stored)
                .extracting(ConversationEntry::getKind)
                .containsExactly(ConversationEntry.Kind.USER, ConversationEntry.Kind.SUMMARY);
        assertThat(stored).extracting(ConversationEntry::getText).doesNotContain("PARTIAL-ANSWER-NOT-STORED");
    }

    @Test
    void shouldStoreNothingOfTheTurnWhenTheDeveloperDeletedTheQuestionWhileHephaestusAnswered() {
        ConversationTurnService.Submission submission = ask(main, "Question with a secret");
        ConversationTurn running = claims.claimNext().orElseThrow();
        entryService.deleteMessage(workspace.getId(), submission.question().getId());

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.STOPPED,
                        answerThatReadContext("Half an answer about the secret"),
                        null,
                        null));

        ConversationEntry question =
                entryRepository.findById(submission.question().getId()).orElseThrow();
        assertThat(question.isRedacted()).isTrue();
        assertThat(question.getPayload()).isNull();
        assertThat(storedFor(running)).extracting(ConversationEntry::getId).containsExactly(question.getId());
        assertThat(stored(running).getStatus()).isEqualTo(ConversationTurn.Status.STOPPED);
        assertThat(turns.isHeld(stored(running))).isFalse();
    }

    @Test
    void shouldStoreNothingOfASlackTurnWhenItsSlackContentWasErasedWhileHephaestusAnswered() {
        ConversationTurnService.Submission submission = turns.submit(
                workspace.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.SLACK,
                        "Question in Slack",
                        UUID.randomUUID().toString(),
                        "17.01",
                        null));
        ConversationTurn running = claims.claimNext().orElseThrow();
        entryService.redactSlackEntries(workspace.getId());

        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.DONE, answerThatReadContext("An answer for Slack"), null, null));

        assertThat(storedFor(running))
                .extracting(ConversationEntry::getId)
                .containsExactly(submission.question().getId());
        assertThat(stored(running).getStatus()).isEqualTo(ConversationTurn.Status.STOPPED);
    }

    @Test
    void shouldKeepNoFailureReasonWhenAStoppedTurnsLeaseExpires() {
        ask(main, "Question");
        ConversationTurn running = claims.claimNext().orElseThrow();
        turns.stop(workspace.getId(), running.getId());

        claims.takeBack(running);

        ConversationTurn stopped = stored(running);
        assertThat(stopped.getStatus()).isEqualTo(ConversationTurn.Status.STOPPED);
        assertThat(stopped.getError()).isNull();
    }

    @Test
    void shouldSendThePartialAnswerWithoutWritingTheTurnAndRefuseOneAfterTheDeveloperStoppedIt() {
        ask(main, "Question");
        ConversationTurn running = claims.claimNext().orElseThrow();
        var snapshot = JsonNodeFactory.instance.objectNode().put("text", "Thinking");
        String version = rowVersion(running);

        turns.updatePartial(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                ConversationPartialUpdates.whole(snapshot));

        assertThat(rowVersion(running)).as("a partial answer writes no row").isEqualTo(version);
        assertThat(published.stream(ConversationLedgerEvents.ProgressChanged.class))
                .anySatisfy(frame -> assertThat(frame.turnId()).isEqualTo(running.getId()));
        turns.stop(workspace.getId(), running.getId());
        assertThatThrownBy(() -> turns.updatePartial(
                        running.getWorkspaceId(),
                        running.getId(),
                        running.getAttempt(),
                        Objects.requireNonNull(running.getRunId()),
                        ConversationPartialUpdates.whole(snapshot)))
                .isInstanceOf(ConversationLeaseLostException.class);
    }

    private String rowVersion(ConversationTurn turn) {
        return (String) entityManager
                .createNativeQuery(
                        "SELECT xmin::text FROM conversation_turn WHERE workspace_id = :workspaceId AND id = :id")
                .setParameter("workspaceId", turn.getWorkspaceId())
                .setParameter("id", turn.getId())
                .getSingleResult();
    }

    @Test
    void shouldStopAQueuedTurnAtOnceAndKeepItsQuestionWhenTheDeveloperStopsIt() {
        ConversationTurnService.Submission submission = ask(main, "Question");

        ConversationTurn stopped =
                turns.stop(workspace.getId(), submission.turn().getId());

        assertThat(stopped.getStatus()).isEqualTo(ConversationTurn.Status.STOPPED);
        assertThat(turns.isHeld(stored(stopped))).isFalse();
        assertThat(entryRepository.findById(submission.question().getId())).isPresent();
        claims.claimNext();
        assertThat(stored(stopped).getStatus()).isEqualTo(ConversationTurn.Status.STOPPED);
    }

    @Test
    void shouldLetTheHostCommitTheAbortedAnswerBeforeTheNextTurnStartsWhenARunningTurnIsStopped() {
        ask(main, "First");
        ConversationTurnService.Submission followUp = ask(main, "Follow-up");
        ConversationTurn running = claims.claimNext().orElseThrow();

        turns.stop(workspace.getId(), running.getId());
        claims.claimNext();
        assertThat(stored(followUp.turn()).getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
        turns.end(
                running.getWorkspaceId(),
                running.getId(),
                running.getAttempt(),
                Objects.requireNonNull(running.getRunId()),
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "Partial answer")),
                        null,
                        null));

        ConversationTurn ended = stored(running);
        assertThat(ended.getStatus()).isEqualTo(ConversationTurn.Status.STOPPED);
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
        ConversationTurn first = claims.claimNext().orElseThrow();
        turns.updatePartial(
                first.getWorkspaceId(),
                first.getId(),
                first.getAttempt(),
                Objects.requireNonNull(first.getRunId()),
                ConversationPartialUpdates.whole(
                        JsonNodeFactory.instance.objectNode().put("activity", "writing")));

        ConversationTurnService.LostAttempt retried = claims.takeBackUnbilled(first);
        assertThat(retried.attempt()).isEqualTo(1);
        assertThat(stored(first).getStatus()).isEqualTo(ConversationTurn.Status.QUEUED);
        assertThat(turns.answer(workspace.getId(), first.getId()).answerEntryId())
                .as("a lost attempt stored nothing")
                .isNull();

        ConversationTurn second = claims.claimNext().orElseThrow();
        assertThat(second.getAttempt()).isEqualTo(2);
        claims.takeBack(second);

        ConversationTurn failed = stored(first);
        assertThat(failed.getStatus()).isEqualTo(ConversationTurn.Status.FAILED);
        assertThat(failed.getError()).isNotBlank();
        assertThat(failed.getFailureCause())
                .as("the cause outlives the worker that lost the lease")
                .isEqualTo("its lease expired on its last attempt");
    }

    @Test
    void shouldKeepTheTurnFromTheReaperWhenTheHostRenewsItsLease() {
        ask(main, "Question");
        ConversationTurn running = claims.claimNext().orElseThrow();

        assertThat(turnRuns.renew(running.getWorkspaceId(), running.getRunId(), running.getAttempt()))
                .isTrue();
        reaper.reap();

        assertThat(stored(running).getStatus()).isEqualTo(ConversationTurn.Status.RUNNING);
    }

    @Test
    void shouldRewriteTheRunningTurnInPlaceWhenItsHostAdmits() {
        ask(main, "Question");

        long[] counts = transactionTemplate.execute(tx -> {
            ConversationTurn running = claims.claimNext().orElseThrow();
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

    /** This transaction's updates of {@code conversation_turn}, and how many of them were HOT, so far. */
    private long[] turnUpdatesInThisTransaction() {
        Object[] row = (Object[]) entityManager
                .createNativeQuery("SELECT n_tup_upd, n_tup_hot_upd FROM pg_stat_xact_user_tables"
                        + " WHERE relid = 'conversation_turn'::regclass")
                .getSingleResult();
        return new long[] {((Number) row[0]).longValue(), ((Number) row[1]).longValue()};
    }

    @Test
    void shouldNameTheLastAnswerAndOnlyTheFeedbackItShowedWhenTheTurnShowedSome() {
        ask(main, "Question");
        ConversationTurn running = claims.claimNext().orElseThrow();
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
                new ConversationTurnService.Ending(
                        ConversationTurn.Status.DONE,
                        List.of(
                                ConversationLedgerFixtures.showFeedback(
                                        ConversationLedgerFixtures.piId(), "Look at this", calls, "Name why"),
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(), "call-1", "show_feedback", "Shown", false),
                                ConversationLedgerFixtures.toolResult(
                                        ConversationLedgerFixtures.piId(), "call-2", "show_feedback", "Refused", true),
                                ConversationLedgerFixtures.answer(ConversationLedgerFixtures.piId(), "Done")),
                        null,
                        null));

        ConversationTurnService.Answer answer = turns.answer(workspace.getId(), running.getId());

        List<ConversationEntry> written =
                ConversationLedgerFixtures.turnEntries(entityManager, workspace.getId(), running.getId());
        assertThat(answer.answerEntryId()).isEqualTo(written.getLast().getId());
        UUID showing = written.stream()
                .filter(entry -> entry.getKind() == ConversationEntry.Kind.ANSWER)
                .findFirst()
                .orElseThrow()
                .getId();
        assertThat(answer.shownIn()).containsExactly(entry(shown, showing));
    }
}
