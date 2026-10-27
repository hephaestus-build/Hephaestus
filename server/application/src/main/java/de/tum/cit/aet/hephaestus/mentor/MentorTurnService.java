package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The turn lifecycle on the ledger: submit, claim, end, lease expiry and stop
 * ({@code docs/contributor/unified-pi-runtime.mdx} § Turn lifecycle). Billing and feedback delivery are
 * {@code agent.mentor.turn}'s part of ending a turn ({@code MentorTurnCompletion}).
 */
@Service
public class MentorTurnService {

    /** One automatic retry after a lost lease. */
    static final int MAX_ATTEMPTS = 2;

    /** Shown to the developer as the failed reply, so it is in the product voice. */
    private static final String LEASE_EXPIRED = "Heph could not finish this reply. Try again.";

    private static final String LEASE_EXPIRED_CAUSE = "its lease expired on its last attempt";

    private final MentorConversationRepository conversations;
    private final MentorEntryRepository entries;
    private final MentorTurnRepository turns;
    private final MentorEntryService entryService;
    private final MentorRedaction redaction;
    private final PersonDataCopyFence copies;
    private final PersonDataWriteFence writeFence;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int maxFollowUps;

    public MentorTurnService(
            MentorConversationRepository conversations,
            MentorEntryRepository entries,
            MentorTurnRepository turns,
            MentorEntryService entryService,
            MentorRedaction redaction,
            PersonDataCopyFence copies,
            PersonDataWriteFence writeFence,
            ApplicationEventPublisher events,
            Clock clock,
            @Value("${hephaestus.mentor.turn.max-follow-ups:3}") int maxFollowUps) {
        this.conversations = conversations;
        this.entries = entries;
        this.turns = turns;
        this.entryService = entryService;
        this.redaction = redaction;
        this.copies = copies;
        this.writeFence = writeFence;
        this.events = events;
        this.clock = clock;
        this.maxFollowUps = maxFollowUps;
    }

    public record Submission(MentorTurn turn, MentorEntry question, boolean created) {}

    /**
     * An ended attempt, read under the turn's lock after the proxy's fence closed.
     *
     * @param proxyUsage what the LLM proxy recorded for the attempt's calls
     * @param feedbackCalled the feedback the attempt called {@code show_feedback} for, in its entries or its last
     *     partial answer
     */
    public record EndedTurn(MentorTurn turn, long accountId, MentorTurnUsage proxyUsage, Set<UUID> feedbackCalled) {}

    /**
     * An attempt whose lease expired, and what became of its turn.
     *
     * @param startedAt when the lost attempt started, on the database clock
     * @param lostAt when its lease was taken back, on the database clock; the attempt claimed nothing after it
     */
    public record LostAttempt(
            MentorTurn turn,
            int attempt,
            @Nullable JsonNode admission,
            MentorTurnUsage proxyUsage,
            Instant startedAt,
            Instant lostAt) {}

    /**
     * Heph's answer to a turn and the feedback it showed, in the order Heph showed it.
     *
     * @param shownIn each shown feedback id, with the message whose successful {@code show_feedback} call showed it
     */
    public record Answer(
            MentorTurn turn, long accountId, @Nullable UUID answerEntryId, Map<UUID, UUID> shownIn) {}

    /**
     * A developer's question.
     *
     * @param requestId the submission's idempotency key
     * @param slackTs the Slack message's {@code ts}, for a question asked in Slack
     * @param pageContext the app page the question was asked from, as the app described it
     */
    public record Question(
            MentorTurn.Surface surface,
            String text,
            String requestId,
            @Nullable String slackTs,
            @Nullable String pageContext) {}

    @Transactional
    public Submission submit(long workspaceId, UUID conversationId, Question asked) {
        String requestId = asked.requestId();
        MentorConversationService.holdWrite(
                writeFence,
                conversations
                        .findAccountIdByIdAndWorkspaceId(conversationId, workspaceId)
                        .orElseThrow(
                                () -> new EntityNotFoundException("MentorConversation", conversationId.toString())));
        MentorConversation conversation = entryService.lock(workspaceId, conversationId);
        Optional<MentorTurn> earlier = turns.findByWorkspaceIdAndRequestId(workspaceId, requestId);
        if (earlier.isPresent()) {
            MentorTurn turn = earlier.get();
            if (!turn.getConversationId().equals(conversationId)) {
                throw new MentorRejectedException(MentorRejectedException.Reason.REQUEST_ID_REUSED);
            }
            return new Submission(turn, entries.findQuestion(turn.getWorkspaceId(), turn.getId()), false);
        }
        if (entries.findByWorkspaceIdAndRequestId(workspaceId, requestId).isPresent()) {
            throw new MentorRejectedException(MentorRejectedException.Reason.REQUEST_ID_REUSED);
        }
        if (turns.countQueuedByWorkspaceIdAndConversationId(workspaceId, conversationId) >= maxFollowUps) {
            throw new MentorRejectedException(MentorRejectedException.Reason.FOLLOW_UPS_FULL);
        }
        MentorTurn turn = turns.saveAndFlush(
                new MentorTurn(conversation, requestId, asked.surface(), asked.pageContext(), clock.instant()));
        MentorEntry question = entryService.insert(
                conversation,
                MentorEntryDraft.question(
                        asked.surface().entrySurface(), asked.text(), turn.getId(), requestId, asked.slackTs()));
        entryService.appended(conversation, List.of(question));
        changed(turn);
        return new Submission(turn, question, true);
    }

    /** Starts the oldest turn a worker may run, if there is one. */
    @Transactional
    public Optional<MentorTurn> claimNext(String workerId, Duration lease) {
        return turns.lockClaimable().map(turn -> {
            // The lease ends on the database clock, the clock the server's reaper judges it by (expireLease).
            Instant now = turns.databaseNow();
            turn.start(workerId, now, now.plus(lease));
            changed(turn);
            return turn;
        });
    }

    @Transactional
    public MentorTurn renewLease(UUID turnId, int attempt, String workerId, Duration lease) {
        MentorTurn turn = held(turnId, attempt, workerId);
        turn.renew(turns.databaseNow().plus(lease));
        return turn;
    }

    /** Freezes the model and rates the attempt runs with, so even a lost attempt is billed at them. */
    @Transactional
    public void admit(UUID turnId, int attempt, String workerId, JsonNode admission) {
        held(turnId, attempt, workerId).admit(admission);
    }

    /**
     * Replaces the running attempt's partial answer with the host's newest whole snapshot: one conditional write and
     * its notification. A stopped attempt takes no partial answer, because the stop cleared it.
     */
    @Transactional
    public void updatePartial(UUID turnId, int attempt, String workerId, JsonNode partial) {
        turns.commitWithoutFlushWait();
        MentorTurnRepository.RunningAttempt running = turns.writePartial(turnId, attempt, workerId, partial.toString())
                .orElseThrow(() -> new MentorLeaseLostException(turnId, attempt));
        events.publishEvent(new MentorLedgerEvents.TurnChanged(
                running.getWorkspaceId(),
                running.getConversationId(),
                turnId,
                MentorTurn.Status.RUNNING,
                MentorTurn.Surface.valueOf(running.getSurface()),
                attempt));
    }

    /**
     * Ends the attempt and stores the Pi entries it appended, in order; the Pi entry of the question updates its row.
     * This is the only write of an attempt's entries, so an attempt that never ends stores nothing, and its retry
     * starts from the same path. The end clears the lease, so a repeated end finds none and stores nothing twice. A
     * failed attempt stores only the summaries it wrote before its question, so the path keeps its question and the
     * next turn does not shorten the conversation again. An attempt whose question was erased while it ran stores
     * nothing: Heph does not answer words that the developer took back or that a Slack removal erased. An attempt that
     * ends after the developer's erasure was requested stores nothing either, because the erasure would not find it.
     */
    @Transactional
    public EndedTurn end(
            UUID turnId,
            int attempt,
            String workerId,
            MentorTurn.Status outcome,
            List<JsonNode> appended,
            @Nullable String error) {
        // Before any row lock: a person erasure holds this fence while it deletes the ledger.
        boolean writable = turns.findById(turnId)
                .flatMap(found -> turns.findAccountIdByIdAndWorkspaceId(turnId, found.getWorkspaceId()))
                .map(writeFence::holdForAccountWrite)
                .orElse(false);
        List<JsonNode> stored = !writable
                ? List.of()
                : outcome != MentorTurn.Status.FAILED
                        ? appended
                        : appended.stream()
                                .filter(piEntry -> MentorPiEntries.kind(piEntry) == MentorEntry.Kind.SUMMARY)
                                .toList();
        boolean copiesData = stored.stream().anyMatch(MentorPiEntries::copiesPersonData);
        if (copiesData) {
            // Before any row lock: a person erasure holds this fence while it locks ledger rows.
            copies.holdForCapture();
        }
        MentorTurn turn = held(turnId, attempt, workerId);
        Set<UUID> feedbackCalled = MentorPiEntries.feedbackCalled(appended, turn.getPartial());
        append(turn, stored, copiesData);
        Instant endedAt = clock.instant();
        turn.end(outcome, error, endedAt);
        turns.flush();
        events.publishEvent(new MentorLedgerEvents.AttemptEnded(turn.getWorkspaceId(), turnId, attempt, endedAt));
        changed(turn);
        return new EndedTurn(turn, accountId(turn), usage(turn), feedbackCalled);
    }

    /**
     * Records the cost an ended attempt was billed with; its usage is already on the row. The row holds one attempt's
     * usage, so a cost of an earlier attempt, billed late, never replaces the cost of the attempt the row holds now.
     */
    @Transactional
    public void recordCost(UUID turnId, int attempt, @Nullable BigDecimal costUsd) {
        MentorTurn turn =
                turns.lockById(turnId).orElseThrow(() -> new EntityNotFoundException("MentorTurn", turnId.toString()));
        if (turn.getAttempt() == attempt) {
            turn.recordCost(costUsd);
        }
    }

    /**
     * Records on a turn that the caller's transaction just ended {@code FAILED} why it failed, for the operator, so
     * the cause outlives the worker's log. A turn in any other state records nothing.
     *
     * @param cause why it failed, in closed kinds only, never a host's, a provider's or an exception's text
     * @param lastFailedCall the attempt's last model call that failed; {@code null} when none did
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordFailure(
            MentorTurn ended, @Nullable String cause, MentorTurn.@Nullable FailedCall lastFailedCall) {
        ended.recordFailure(cause, lastFailedCall);
    }

    /** Stops a turn. Stopping a turn that already ended changes nothing. */
    @Transactional
    public MentorTurn stop(long workspaceId, UUID turnId) {
        MentorTurn turn = turns.lockByIdAndWorkspaceId(turnId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorTurn", turnId.toString()));
        if (turn.stopIfActive(clock.instant())) {
            changed(turn);
        }
        return turn;
    }

    /** Stops the running turn of a conversation, if one runs. Queued follow-ups stay queued. */
    @Transactional
    public Optional<MentorTurn> stopRunning(long workspaceId, UUID conversationId) {
        return stopRunningIf(workspaceId, conversationId, running -> true);
    }

    /**
     * Stops the running turn of a conversation when {@code meant} accepts it. Queued follow-ups stay queued. The turns
     * are read as they are locked, so {@code meant} sees the attempt that runs while the stop is written, and a claim
     * starts none of them meanwhile.
     */
    @Transactional
    public Optional<MentorTurn> stopRunning(long workspaceId, UUID conversationId, Predicate<MentorTurn> meant) {
        return stopRunningIf(workspaceId, conversationId, meant);
    }

    /** The stop of both {@link #stopRunning} forms, in the caller's transaction. */
    private Optional<MentorTurn> stopRunningIf(long workspaceId, UUID conversationId, Predicate<MentorTurn> meant) {
        Optional<MentorTurn> running =
                turns.lockActiveByWorkspaceIdAndConversationId(workspaceId, conversationId).stream()
                        .filter(locked -> locked.getStatus() == MentorTurn.Status.RUNNING)
                        .findFirst()
                        .filter(locked -> meant.test(locked) && locked.stopIfActive(clock.instant()));
        running.ifPresent(this::changed);
        return running;
    }

    /** The turns whose lease expired, oldest first. */
    @Transactional(readOnly = true)
    public List<UUID> expiredLeases() {
        return turns.findExpiredLeases(turns.databaseNow());
    }

    /**
     * Takes an expired lease back. A running turn returns to the queue for its one retry, or fails after it; a stopped
     * turn stays stopped. The lost attempt stored nothing ({@link #end}).
     */
    @Transactional
    public Optional<LostAttempt> expireLease(UUID turnId) {
        Optional<MentorTurn> locked = turns.lockById(turnId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        MentorTurn turn = locked.get();
        Instant now = turns.databaseClock();
        Instant leasedUntil = turn.getLeasedUntil();
        if (!turn.isLeased() || leasedUntil == null || !leasedUntil.isBefore(now)) {
            return Optional.empty();
        }
        return Optional.of(
                takeBack(turn, turn.getStatus() == MentorTurn.Status.RUNNING && turn.getAttempt() < MAX_ATTEMPTS, now));
    }

    /**
     * Takes back the attempt that runs in a conversation and returns its turn to the queue, as an expired lease does
     * ({@link #expireLease}). The attempt's host loses its lease, so the ledger refuses its later writes. The question
     * stays, and the turn starts again from the conversation it is in then. Locks the conversation's queued and running
     * turns until the transaction ends, so no claim starts one in the meantime. Publishes
     * {@link MentorLedgerEvents.AttemptTakenBack}, so the attempt is billed and its claims are settled.
     */
    @Transactional
    public Optional<LostAttempt> requeueRunning(long workspaceId, UUID conversationId) {
        Optional<LostAttempt> lost =
                turns.lockActiveByWorkspaceIdAndConversationId(workspaceId, conversationId).stream()
                        .filter(turn -> turn.getStatus() == MentorTurn.Status.RUNNING)
                        .findFirst()
                        .map(turn -> takeBack(turn, true, clock.instant()));
        // Callers move the turn with SQL next, so the row must not stay RUNNING until the next flush.
        turns.flush();
        lost.ifPresent(taken -> events.publishEvent(new MentorLedgerEvents.AttemptTakenBack(
                workspaceId,
                taken.turn().getId(),
                taken.attempt(),
                taken.admission(),
                taken.proxyUsage(),
                taken.startedAt(),
                taken.lostAt())));
        return lost;
    }

    /**
     * Takes a leased attempt back under its turn's lock: requeues the turn for one more attempt, or fails it.
     *
     * @param retry whether the turn returns to the queue; a turn that does not, fails, and a stopped turn stays stopped
     */
    private LostAttempt takeBack(MentorTurn turn, boolean retry, Instant now) {
        int attempt = turn.getAttempt();
        JsonNode admission = turn.getAdmission();
        MentorTurnUsage proxyUsage = usage(turn);
        Instant startedAt = Objects.requireNonNull(turn.getStartedAt(), "A leased turn has started");
        // Under the turn's lock, which a claim of the attempt shares: every claim it made committed before this.
        Instant lostAt = turns.databaseClock();
        // Dates the attempt's spend in this transaction, before the requeue resets the turn.
        events.publishEvent(new MentorLedgerEvents.AttemptEnded(turn.getWorkspaceId(), turn.getId(), attempt, lostAt));
        if (retry) {
            turn.requeue();
        } else {
            turn.end(MentorTurn.Status.FAILED, LEASE_EXPIRED, now);
            turn.recordFailure(LEASE_EXPIRED_CAUSE, null);
        }
        changed(turn);
        return new LostAttempt(turn, attempt, admission, proxyUsage, startedAt, lostAt);
    }

    /** The database clock, which stamps attempt starts and the feedback claims of their turns. */
    @Transactional(readOnly = true)
    public Instant databaseNow() {
        return turns.databaseNow();
    }

    /** The developer's question that started the turn. */
    @Transactional(readOnly = true)
    public MentorEntry question(long workspaceId, UUID turnId) {
        return entries.findQuestion(workspaceId, turnId);
    }

    public MentorTurn require(long workspaceId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("MentorTurn", turnId.toString()));
    }

    /**
     * Heph's answer: the turn's last {@code HEPH} entry that is not erased. The feedback it showed is the
     * {@code show_feedback} calls of the turn whose tool result succeeded, in the order Heph showed it.
     */
    @Transactional(readOnly = true)
    public Answer answer(long workspaceId, UUID turnId) {
        MentorTurn turn = require(workspaceId, turnId);
        List<MentorEntry> messages =
                entries
                        .findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(workspaceId, turnId, MentorEntry.Kind.HEPH)
                        .stream()
                        .filter(entry -> !entry.isRedacted())
                        .toList();
        if (messages.isEmpty()) {
            return new Answer(turn, accountId(turn), null, Map.of());
        }
        Set<MentorPiEntries.ShownCall> succeeded = entries.shownLinkCalls(workspaceId, List.of(turnId));
        Map<UUID, UUID> shown = new LinkedHashMap<>();
        for (MentorEntry message : messages) {
            for (MentorPiEntries.Link link : MentorPiEntries.links(message.getPayload())) {
                if (link.kind() == MentorPiEntries.LinkKind.FEEDBACK && succeeded.contains(link.in(turnId))) {
                    shown.putIfAbsent(link.id(), message.getId());
                }
            }
        }
        return new Answer(turn, accountId(turn), messages.getLast().getId(), Collections.unmodifiableMap(shown));
    }

    /**
     * Stores the attempt's entries under the lock of its tree, then erases the copies that read data erased while it
     * ran ({@link MentorRedaction#eraseStaleCopies}).
     *
     * @param fenced whether the caller holds the person-data copy fence, which it takes when the attempt appended a
     *     result that copies person data
     */
    private void append(MentorTurn turn, List<JsonNode> appended, boolean fenced) {
        if (appended.isEmpty()) {
            return;
        }
        Instant attemptStart = Objects.requireNonNull(turn.getStartedAt(), "A held turn has started");
        MentorConversation conversation = entryService.lockWithTree(turn.getWorkspaceId(), turn.getConversationId());
        // A redaction locks this tree first, so the question cannot be erased after this read.
        MentorEntry question = entries.findQuestion(turn.getWorkspaceId(), turn.getId());
        if (question.isRedacted()) {
            return;
        }
        List<MentorEntry> inserted = new ArrayList<>(appended.size());
        Set<MentorPiEntries.ShownCall> succeeded = MentorPiEntries.succeededLinkCalls(turn.getId(), appended);
        for (JsonNode piEntry : appended) {
            MentorEntry.Kind kind = MentorPiEntries.kind(piEntry);
            if (kind == MentorEntry.Kind.USER) {
                entries.recordPiEntry(turn.getWorkspaceId(), question.getId(), piEntry.toString());
                continue;
            }
            MentorEntry entry = entryService.insert(
                    conversation,
                    new MentorEntryDraft(
                            kind,
                            turn.getSurface().entrySurface(),
                            MentorPiEntries.text(piEntry),
                            kind.isMessage() ? MentorPiEntries.searchText(piEntry, turn.getId(), succeeded) : "",
                            piEntry,
                            turn.getId(),
                            null,
                            null));
            inserted.add(entry);
        }
        entryService.appended(conversation, inserted);
        redaction.eraseStaleCopies(
                inserted,
                attemptStart,
                fenced && copies.erasedSince(MentorEntryRepository.COPIES_PERSON_DATA_STORE, attemptStart));
    }

    /** The turn, locked, if this attempt of this worker still holds it. */
    private MentorTurn held(UUID turnId, int attempt, String workerId) {
        return turns.lockById(turnId)
                .filter(turn -> turn.isLeased() && turn.getAttempt() == attempt && workerId.equals(turn.getWorkerId()))
                .orElseThrow(() -> new MentorLeaseLostException(turnId, attempt));
    }

    /** The account the turn answers: its conversation's owner. */
    public long accountId(MentorTurn turn) {
        return conversations
                .findAccountIdByIdAndWorkspaceId(turn.getConversationId(), turn.getWorkspaceId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "MentorConversation", turn.getConversationId().toString()));
    }

    private static MentorTurnUsage usage(MentorTurn turn) {
        return new MentorTurnUsage(
                turn.getInputTokens(),
                turn.getOutputTokens(),
                turn.getCachedTokens(),
                turn.getCacheWriteTokens(),
                turn.getReasoningTokens(),
                turn.getLlmCalls());
    }

    /**
     * Why each queued turn among {@code shown} has not started, by the rule a claim follows
     * ({@link MentorTurnRepository#CLAIMABLE}). A turn that a worker may start waits for a free Heph only while a host
     * holds another turn. Otherwise it starts at once or no worker runs, and the reason is not known: such a turn, and
     * a turn that does not wait, has no entry.
     */
    @Transactional(readOnly = true)
    public Map<UUID, MentorTurn.WaitingFor> waits(long workspaceId, Collection<MentorTurn> shown) {
        List<UUID> queued = shown.stream()
                .filter(turn -> turn.getStatus() == MentorTurn.Status.QUEUED)
                .map(MentorTurn::getId)
                .toList();
        if (queued.isEmpty()) {
            return Map.of();
        }
        Set<UUID> claimable = Set.copyOf(turns.findClaimableIds(workspaceId, queued));
        boolean hephBusy = !claimable.isEmpty() && turns.existsHeld();
        Map<UUID, MentorTurn.WaitingFor> waits = new HashMap<>();
        for (UUID id : queued) {
            if (!claimable.contains(id)) {
                waits.put(id, MentorTurn.WaitingFor.EARLIER_QUESTION);
            } else if (hephBusy) {
                waits.put(id, MentorTurn.WaitingFor.FREE_HEPH);
            }
        }
        return waits;
    }

    /** Why one turn has not started ({@link #waits}); {@code null} when it does not wait or the reason is not known. */
    @Transactional(readOnly = true)
    public MentorTurn.@Nullable WaitingFor waitingFor(MentorTurn turn) {
        return waits(turn.getWorkspaceId(), List.of(turn)).get(turn.getId());
    }

    /**
     * Publishes the turn's change. An ended turn no longer holds back the questions queued after it in its
     * conversation, so their changes are published too: each now waits for a free Heph or starts.
     */
    private void changed(MentorTurn turn) {
        events.publishEvent(MentorLedgerEvents.TurnChanged.of(turn));
        if (turn.getStatus().isTerminal()) {
            turns.findByWorkspaceIdAndConversationIdAndStatus(
                            turn.getWorkspaceId(), turn.getConversationId(), MentorTurn.Status.QUEUED)
                    .forEach(next -> events.publishEvent(MentorLedgerEvents.TurnChanged.of(next)));
        }
    }
}
