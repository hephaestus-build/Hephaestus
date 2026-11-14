package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.conversation.spi.TurnRuns;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCopyFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
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
 * The turn lifecycle on the ledger: submit, start, end, take-back and stop
 * ({@code docs/contributor/unified-pi-runtime.mdx} § Turn lifecycle). Each turn's run leases its work ({@link TurnRuns});
 * a write of a turn and of its run commits together, and the turn is always locked first. Billing and feedback
 * delivery are {@code agent.conversation.turn}'s part of ending a turn ({@code ConversationTurnCompletion}).
 */
@Service
public class ConversationTurnService {

    /** Shown to the developer as the failed reply, so it is in the product voice. */
    private static final String LEASE_EXPIRED = "Hephaestus could not finish this reply. Try again.";

    private static final String LEASE_EXPIRED_CAUSE = "its lease expired on its last attempt";

    private final ConversationRepository conversations;
    private final ConversationEntryRepository entries;
    private final ConversationTurnRepository turns;
    private final ConversationEntryService entryService;
    private final ConversationRedaction redaction;
    private final PersonDataCopyFence copies;
    private final PersonDataWriteFence writeFence;
    private final ConversationTurnProgress progress;
    private final TurnRuns runs;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int maxFollowUps;

    public ConversationTurnService(
            ConversationRepository conversations,
            ConversationEntryRepository entries,
            ConversationTurnRepository turns,
            ConversationEntryService entryService,
            ConversationRedaction redaction,
            PersonDataCopyFence copies,
            PersonDataWriteFence writeFence,
            ConversationTurnProgress progress,
            TurnRuns runs,
            ApplicationEventPublisher events,
            Clock clock,
            @Value("${hephaestus.conversation.turn.max-follow-ups:3}") int maxFollowUps) {
        this.conversations = conversations;
        this.entries = entries;
        this.turns = turns;
        this.entryService = entryService;
        this.redaction = redaction;
        this.copies = copies;
        this.writeFence = writeFence;
        this.progress = progress;
        this.runs = runs;
        this.events = events;
        this.clock = clock;
        this.maxFollowUps = maxFollowUps;
    }

    public record Submission(ConversationTurn turn, ConversationEntry question, boolean created) {}

    /**
     * An ended attempt, read under the turn's lock after the proxy's fence closed.
     *
     * @param feedbackCalled the feedback the attempt called {@code show_feedback} for, in its entries or its last
     *     partial answer
     */
    public record EndedTurn(ConversationTurn turn, long accountId, Set<UUID> feedbackCalled) {}

    /**
     * How an attempt ends.
     *
     * @param outcome {@code DONE}, {@code FAILED} or {@code STOPPED}
     * @param appended every Pi entry the attempt appended, in order; none when the worker ends it
     * @param lastPartial the partial answer the attempt sent last, which readers may have seen; {@code null} before the
     *     first
     * @param error what the developer reads about a failed turn
     */
    public record Ending(
            ConversationTurn.Status outcome,
            List<JsonNode> appended,
            @Nullable JsonNode lastPartial,
            @Nullable String error) {}

    /**
     * An attempt whose lease expired, and what became of its turn.
     *
     * @param startedAt when the lost attempt started, on the database clock
     * @param lostAt when its lease was taken back, on the database clock; the attempt claimed nothing after it
     */
    public record LostAttempt(
            ConversationTurn turn, int attempt, @Nullable JsonNode admission, Instant startedAt, Instant lostAt) {}

    /**
     * Hephaestus's answer to a turn and the feedback it showed, in the order Hephaestus showed it.
     *
     * @param shownIn each shown feedback id, with the message whose successful {@code show_feedback} call showed it
     */
    public record Answer(
            ConversationTurn turn, long accountId, @Nullable UUID answerEntryId, Map<UUID, UUID> shownIn) {}

    /**
     * A developer's question.
     *
     * @param requestId the submission's idempotency key
     * @param slackTs the Slack message's {@code ts}, for a question asked in Slack
     * @param pageContext the app page the question was asked from, as the app described it
     */
    public record Question(
            ConversationTurn.Surface surface,
            String text,
            String requestId,
            @Nullable String slackTs,
            @Nullable String pageContext) {}

    @Transactional
    public Submission submit(long workspaceId, UUID conversationId, Question asked) {
        String requestId = asked.requestId();
        ConversationService.holdWrite(
                writeFence,
                conversations
                        .findAccountIdByIdAndWorkspaceId(conversationId, workspaceId)
                        .orElseThrow(() -> new EntityNotFoundException("Conversation", conversationId.toString())));
        Conversation conversation = entryService.lock(workspaceId, conversationId);
        Optional<ConversationTurn> earlier = turns.findByWorkspaceIdAndRequestId(workspaceId, requestId);
        if (earlier.isPresent()) {
            ConversationTurn turn = earlier.get();
            if (!turn.getConversationId().equals(conversationId)) {
                throw new ConversationRejectedException(ConversationRejectedException.Reason.REQUEST_ID_REUSED);
            }
            return new Submission(turn, entries.findStimulus(turn.getWorkspaceId(), turn.getId()), false);
        }
        if (entries.findByWorkspaceIdAndRequestId(workspaceId, requestId).isPresent()) {
            throw new ConversationRejectedException(ConversationRejectedException.Reason.REQUEST_ID_REUSED);
        }
        if (turns.countQueuedByWorkspaceIdAndConversationId(workspaceId, conversationId) >= maxFollowUps) {
            throw new ConversationRejectedException(ConversationRejectedException.Reason.FOLLOW_UPS_FULL);
        }
        UUID runId = runs.enqueue(workspaceId, conversationId);
        ConversationTurn turn = turns.saveAndFlush(new ConversationTurn(
                conversation, requestId, asked.surface(), asked.pageContext(), runId, clock.instant()));
        ConversationEntry question = entryService.insert(
                conversation,
                ConversationEntryDraft.question(
                        asked.surface().entrySurface(), asked.text(), turn.getId(), requestId, asked.slackTs()));
        entryService.appended(conversation, List.of(question));
        // A new question is the last of its conversation, so no other turn's reason to wait changes.
        events.publishEvent(ConversationLedgerEvents.TurnChanged.of(turn));
        return new Submission(turn, question, true);
    }

    public record DigestRequest(String requestId, String text, JsonNode facts, Duration startWithin) {}

    /** A digest is a system stimulus on the same conversation ledger, with no invented user question. */
    @Transactional
    public boolean submitDigest(long workspaceId, UUID conversationId, DigestRequest request) {
        ConversationService.holdWrite(
                writeFence,
                conversations
                        .findAccountIdByIdAndWorkspaceId(conversationId, workspaceId)
                        .orElseThrow(() -> new EntityNotFoundException("Conversation", conversationId.toString())));
        Conversation conversation = entryService.lock(workspaceId, conversationId);
        if (turns.findByWorkspaceIdAndRequestId(workspaceId, request.requestId())
                        .isPresent()
                || entries.existsByWorkspaceIdAndRequestId(workspaceId, request.requestId())) return false;
        UUID runId = runs.enqueueDigest(workspaceId, conversationId, request.startWithin());
        ConversationTurn turn = turns.saveAndFlush(new ConversationTurn(
                conversation, request.requestId(), ConversationTurn.Surface.SYSTEM, null, runId, clock.instant()));
        ConversationEntry stimulus = entryService.insert(
                conversation,
                ConversationEntryDraft.digest(request.text(), request.facts(), request.requestId(), turn.getId()));
        entryService.appended(conversation, List.of(stimulus));
        events.publishEvent(ConversationLedgerEvents.TurnChanged.of(turn));
        return true;
    }

    /**
     * Starts the turn of a run that the caller's claim just leased under {@code epoch}. Empty when a stop holds the
     * turn: the caller then rolls its claim back, and the stop ends the run.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ConversationTurn> start(long workspaceId, UUID runId, int epoch) {
        return turns.lockClaimedByRun(runId, workspaceId).map(turn -> {
            if (turn.getStatus() != ConversationTurn.Status.QUEUED)
                throw new IllegalStateException("The turn of a queued run waits in the queue");
            turn.start(turns.databaseNow(), epoch);
            changed(turn, true);
            return turn;
        });
    }

    /** Freezes the model and rates the attempt runs with, so even a lost attempt is billed at them. */
    @Transactional
    public void admit(long workspaceId, UUID turnId, int epoch, UUID runId, JsonNode admission) {
        held(workspaceId, turnId, epoch, runId).admit(admission);
    }

    /**
     * Sends the running attempt's newest partial answer to every reader, as frames of one notification each. It
     * writes no row: the attempt's end stores the answer once ({@link #end}), and memory alone keeps the partial
     * answer meanwhile ({@link ConversationTurnProgress}). A stopped attempt takes no partial answer.
     *
     * @throws ConversationLeaseLostException when the attempt no longer holds its run's claim
     */
    @Transactional
    public boolean updatePartial(
            long workspaceId, UUID turnId, int epoch, UUID runId, ConversationTurnProgress.Update update) {
        int attempt = epoch;
        turns.commitWithoutFlushWait();
        if (!copies.tryHoldForCapture()) return false;
        ConversationTurnRepository.RunningAttempt running = turns.findRunningAttempt(workspaceId, turnId, epoch, runId)
                .orElseThrow(() -> new ConversationLeaseLostException(turnId, attempt));
        if (redaction.personErasedSince(running.getStartedAt()))
            throw new ConversationLeaseLostException(turnId, attempt);
        if (ConversationTurn.Surface.SYSTEM.name().equals(running.getSurface())) return false;
        var frames = progress.frames(
                workspaceId,
                running.getConversationId(),
                turnId,
                ConversationTurn.Surface.valueOf(running.getSurface()),
                attempt,
                update);
        frames.forEach(events::publishEvent);
        return !frames.isEmpty();
    }

    /**
     * Ends the attempt and stores the Pi entries it appended, in order; the Pi entry of the question updates its row.
     * This is the only write of an attempt's entries, so an attempt that never ends stores nothing, and its retry
     * starts from the same path. The end clears the lease, so a repeated end finds none and stores nothing twice. A
     * failed attempt stores only the summaries it wrote before its question, so the path keeps its question and the
     * next turn does not shorten the conversation again. An attempt whose question was erased while it ran stores
     * nothing: Hephaestus does not answer words that the developer took back or that a Slack removal erased. An attempt that
     * ends after the developer's erasure was requested stores nothing either, because the erasure would not find it.
     * The turn's run ends with it, so a repeated end finds the run ended and stores nothing twice.
     */
    @Transactional
    public EndedTurn end(long workspaceId, UUID turnId, int epoch, UUID runId, Ending ending) {
        ConversationTurn.Status outcome = ending.outcome();
        List<JsonNode> appended = ending.appended();
        // Before any row lock: a person erasure holds this fence while it deletes the ledger.
        boolean writable = turns.findAccountIdByIdAndWorkspaceId(turnId, workspaceId)
                .map(writeFence::holdForAccountWrite)
                .orElse(false);
        List<JsonNode> stored = !writable
                ? List.of()
                : outcome != ConversationTurn.Status.FAILED
                        ? appended
                        : appended.stream()
                                .filter(piEntry ->
                                        ConversationPiEntries.kind(piEntry) == ConversationEntry.Kind.SUMMARY)
                                .toList();
        // Answers can copy earlier context without a new tool call in this attempt.
        boolean hasAppendedContext = !stored.isEmpty();
        if (hasAppendedContext) {
            // Before any row lock: a person erasure holds this fence while it locks ledger rows.
            copies.holdForCapture();
        }
        ConversationTurn turn = held(workspaceId, turnId, epoch, runId);
        Set<UUID> feedbackCalled = ConversationPiEntries.feedbackCalled(appended, ending.lastPartial());
        boolean contextErased =
                hasAppendedContext && redaction.personErasedSince(Objects.requireNonNull(turn.getStartedAt()));
        append(
                turn,
                turn.getSurface() == ConversationTurn.Surface.SYSTEM && outcome != ConversationTurn.Status.DONE
                        ? List.of()
                        : stored,
                contextErased);
        Instant endedAt = clock.instant();
        turn.end(
                contextErased ? ConversationTurn.Status.FAILED : outcome,
                contextErased ? LEASE_EXPIRED : ending.error(),
                endedAt);
        if (contextErased) turn.recordFailure("context_changed", null);
        turns.flush();
        runs.ended(workspaceId, runId, epoch, turn.getStatus());
        changed(turn, true);
        return new EndedTurn(turn, accountId(turn), contextErased ? Set.of() : feedbackCalled);
    }

    /**
     * Ends the attempt for the server, after its run's holder did not: a stopped turn whose holder did not end it in its
     * grace, or a turn past its deadline. It stores no entries, because only the holder has them; the partial answer
     * readers saw last settles the feedback it called. The caller locked the turn and ends its run.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EndedTurn endWithoutHolder(
            ConversationTurn locked,
            ConversationTurn.Status outcome,
            @Nullable String error,
            @Nullable JsonNode lastPartial) {
        Instant endedAt = clock.instant();
        locked.end(outcome, error, endedAt);
        turns.flush();
        changed(locked, true);
        return new EndedTurn(locked, accountId(locked), ConversationPiEntries.feedbackCalled(List.of(), lastPartial));
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
            ConversationTurn ended, @Nullable String cause, ConversationTurn.@Nullable FailedCall lastFailedCall) {
        ended.recordFailure(cause, lastFailedCall);
    }

    /**
     * Stops a turn. A queued turn ends with its run. A running turn's holder may still end it with what it wrote, for
     * the run's grace ({@link TurnRuns#stopped}). Stopping a turn that already ended changes nothing.
     */
    @Transactional
    public ConversationTurn stop(long workspaceId, UUID turnId) {
        ConversationTurn turn = turns.lockByIdAndWorkspaceId(turnId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("ConversationTurn", turnId.toString()));
        boolean running = turn.getStatus() == ConversationTurn.Status.RUNNING;
        if (turn.stopIfActive(clock.instant())) {
            runs.stopped(workspaceId, turn.getRunId(), running);
            changed(turn, false);
        }
        return turn;
    }

    /**
     * Stops the running turn of a conversation when {@code meant} accepts it. Queued follow-ups stay queued. The turns
     * are read as they are locked, so {@code meant} sees the attempt that runs while the stop is written, and a claim
     * starts none of them meanwhile.
     */
    @Transactional
    public Optional<ConversationTurn> stopRunning(
            long workspaceId, UUID conversationId, Predicate<ConversationTurn> meant) {
        Optional<ConversationTurn> running =
                turns.lockActiveByWorkspaceIdAndConversationId(workspaceId, conversationId).stream()
                        .filter(locked -> locked.getStatus() == ConversationTurn.Status.RUNNING)
                        .findFirst()
                        .filter(locked -> meant.test(locked) && locked.stopIfActive(clock.instant()));
        running.ifPresent(stopped -> {
            runs.stopped(workspaceId, stopped.getRunId(), true);
            changed(stopped, false);
        });
        return running;
    }

    /** Whether a host still holds the turn: it runs, or it stopped and its host still commits what it wrote. */
    @Transactional(readOnly = true)
    public boolean isHeld(ConversationTurn turn) {
        return turns.isHeld(turn.getId());
    }

    /** Locks the turn of a run, as the run's handler does before it moves the run. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ConversationTurn> lockByRun(long workspaceId, UUID runId) {
        return turns.lockByRun(runId, workspaceId);
    }

    /**
     * Takes back the attempt of a turn whose run lost its holder: a running turn returns to the queue for its retry, or
     * fails without one; a stopped turn stays stopped. The lost attempt stored nothing ({@link #end}). The caller locked
     * the turn and moves its run.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LostAttempt takeBack(ConversationTurn locked, boolean retry) {
        return takeBack(
                locked,
                retry && locked.getStatus() == ConversationTurn.Status.RUNNING,
                clock.instant(),
                LEASE_EXPIRED,
                LEASE_EXPIRED_CAUSE);
    }

    /**
     * Ends a queued turn that waited for a free Hephaestus longer than its start wait. The caller locked the turn and
     * ends its run.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void expireQueued(ConversationTurn locked, String error) {
        if (locked.getStatus() != ConversationTurn.Status.QUEUED)
            throw new IllegalStateException("Only a queued turn waits to start");
        locked.end(ConversationTurn.Status.FAILED, error, clock.instant());
        locked.recordFailure("start_wait_expired", null);
        changed(locked, true);
    }

    /**
     * Takes back the attempt held in a conversation. A running turn returns to the queue, as a lost holder does
     * ({@link #takeBack(ConversationTurn, boolean)}), without spending its retry. The attempt's host loses its run's claim, so
     * the ledger refuses its later writes. The question
     * stays, and the turn starts again from the conversation it is in then. A stopped turn stays stopped and its run ends.
     * Locks the conversation's queued and held
     * turns until the transaction ends, so no claim starts one in the meantime. Publishes
     * {@link ConversationLedgerEvents.AttemptTakenBack}, so the attempt is billed and its claims are settled.
     */
    @Transactional
    public Optional<LostAttempt> requeueRunning(long workspaceId, UUID conversationId) {
        Optional<LostAttempt> lost =
                turns.lockActiveByWorkspaceIdAndConversationId(workspaceId, conversationId).stream()
                        .filter(turn -> turn.getStatus() == ConversationTurn.Status.RUNNING
                                || turn.getStatus() == ConversationTurn.Status.STOPPED)
                        .findFirst()
                        .map(turn -> {
                            int epoch = turn.getAttempt();
                            boolean stopped = turn.getStatus() == ConversationTurn.Status.STOPPED;
                            LostAttempt taken =
                                    takeBack(turn, !stopped, clock.instant(), LEASE_EXPIRED, LEASE_EXPIRED_CAUSE);
                            if (stopped) runs.ended(workspaceId, turn.getRunId(), epoch, turn.getStatus());
                            else runs.requeue(workspaceId, turn.getId(), turn.getRunId(), epoch);
                            return taken;
                        });
        // Callers move the turn with SQL next, so the row must not stay RUNNING until the next flush.
        turns.flush();
        lost.ifPresent(taken -> events.publishEvent(new ConversationLedgerEvents.AttemptTakenBack(
                workspaceId,
                taken.turn().getId(),
                taken.attempt(),
                taken.admission(),
                taken.startedAt(),
                taken.lostAt())));
        return lost;
    }

    /**
     * Takes a leased attempt back under its turn's lock: requeues the turn for one more attempt, or fails it.
     *
     * @param retry whether the turn returns to the queue; a turn that does not, fails, and a stopped turn stays stopped
     * @param reply what the developer reads for a turn that fails
     * @param cause why it failed, for the operator
     */
    private LostAttempt takeBack(ConversationTurn turn, boolean retry, Instant now, String reply, String cause) {
        int attempt = turn.getAttempt();
        JsonNode admission = turn.getAdmission();
        Instant startedAt = Objects.requireNonNull(turn.getStartedAt(), "A leased turn has started");
        // Under the turn's lock, which a claim of the attempt shares: every claim it made committed before this.
        Instant lostAt = turns.databaseClock();
        if (retry) {
            turn.requeue(now);
        } else {
            turn.end(ConversationTurn.Status.FAILED, reply, now);
            turn.recordFailure(cause, null);
        }
        changed(turn, true);
        return new LostAttempt(turn, attempt, admission, startedAt, lostAt);
    }

    /** The database clock, which stamps attempt starts and the feedback claims of their turns. */
    @Transactional(readOnly = true)
    public Instant databaseNow() {
        return turns.databaseNow();
    }

    /** The real question or proactive stimulus that started the turn. */
    @Transactional(readOnly = true)
    public ConversationEntry question(long workspaceId, UUID turnId) {
        return entries.findStimulus(workspaceId, turnId);
    }

    public ConversationTurn require(long workspaceId, UUID turnId) {
        return turns.findByIdAndWorkspaceId(turnId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("ConversationTurn", turnId.toString()));
    }

    /**
     * Hephaestus's answer: the turn's last {@code ANSWER} entry that is not erased. The feedback it showed is the
     * {@code show_feedback} calls of the turn whose tool result succeeded, in the order Hephaestus showed it.
     */
    @Transactional(readOnly = true)
    public Answer answer(long workspaceId, UUID turnId) {
        ConversationTurn turn = require(workspaceId, turnId);
        List<ConversationEntry> messages = entries
                .findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(workspaceId, turnId, ConversationEntry.Kind.ANSWER)
                .stream()
                .filter(entry -> !entry.isRedacted())
                .toList();
        if (messages.isEmpty()) {
            return new Answer(turn, accountId(turn), null, Map.of());
        }
        Set<ConversationPiEntries.ShownCall> succeeded = entries.shownLinkCalls(workspaceId, List.of(turnId));
        Map<UUID, UUID> shown = new LinkedHashMap<>();
        for (ConversationEntry message : messages) {
            for (ConversationPiEntries.Link link : ConversationPiEntries.links(message.getPayload())) {
                if (link.kind() == ConversationPiEntries.LinkKind.FEEDBACK && succeeded.contains(link.in(turnId))) {
                    shown.putIfAbsent(link.id(), message.getId());
                }
            }
        }
        return new Answer(turn, accountId(turn), messages.getLast().getId(), Collections.unmodifiableMap(shown));
    }

    /**
     * Stores the attempt's entries under the lock of its tree, then erases the copies that read data erased while it
     * ran ({@link ConversationRedaction#eraseStaleCopies}).
     *
     * @param personErased whether an erasure ran since the attempt started, read under the copy fence
     */
    private void append(ConversationTurn turn, List<JsonNode> appended, boolean personErased) {
        if (appended.isEmpty()) {
            return;
        }
        Instant attemptStart = Objects.requireNonNull(turn.getStartedAt(), "A held turn has started");
        Conversation conversation = entryService.lockWithTree(turn.getWorkspaceId(), turn.getConversationId());
        // A redaction locks this tree first, so the question cannot be erased after this read.
        ConversationEntry question = entries.findStimulus(turn.getWorkspaceId(), turn.getId());
        if (question.isRedacted()) {
            return;
        }
        List<ConversationEntry> inserted = new ArrayList<>(appended.size());
        Set<ConversationPiEntries.ShownCall> succeeded =
                ConversationPiEntries.succeededLinkCalls(turn.getId(), appended);
        for (JsonNode piEntry : appended) {
            ConversationEntry.Kind kind = ConversationPiEntries.kind(piEntry);
            if (kind == ConversationEntry.Kind.USER) {
                entries.recordPiEntry(turn.getWorkspaceId(), question.getId(), piEntry.toString());
                continue;
            }
            ConversationEntry entry = entryService.insert(
                    conversation,
                    new ConversationEntryDraft(
                            kind,
                            turn.getSurface().entrySurface(),
                            ConversationPiEntries.text(piEntry),
                            kind.isMessage() ? ConversationPiEntries.searchText(piEntry, turn.getId(), succeeded) : "",
                            piEntry,
                            turn.getId(),
                            null,
                            null));
            inserted.add(entry);
        }
        entryService.appended(conversation, inserted);
        redaction.eraseStaleCopies(inserted, attemptStart, personErased);
    }

    /**
     * Locks the turn of the attempt that holds its run under {@code epoch}, then holds that claim until the transaction
     * ends, so no take-back or stop moves the run meanwhile.
     *
     * @throws ConversationLeaseLostException when the attempt no longer holds the run
     */
    private ConversationTurn held(long workspaceId, UUID turnId, int epoch, UUID runId) {
        ConversationTurn turn = turns.lockByIdAndWorkspaceId(turnId, workspaceId)
                .orElseThrow(() -> new ConversationLeaseLostException(turnId, epoch));
        if ((turn.getStatus() != ConversationTurn.Status.RUNNING && turn.getStatus() != ConversationTurn.Status.STOPPED)
                || turn.getAttempt() != epoch
                || !runId.equals(turn.getRunId())) {
            throw new ConversationLeaseLostException(turnId, epoch);
        }
        runs.requireCurrent(workspaceId, turnId, runId, epoch);
        return turn;
    }

    /** The account the turn answers: its conversation's owner. */
    public long accountId(ConversationTurn turn) {
        return conversations
                .findAccountIdByIdAndWorkspaceId(turn.getConversationId(), turn.getWorkspaceId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Conversation", turn.getConversationId().toString()));
    }

    private void changed(ConversationTurn turn, boolean slotsChanged) {
        entryService.turnChanged(turn, slotsChanged);
    }
}
