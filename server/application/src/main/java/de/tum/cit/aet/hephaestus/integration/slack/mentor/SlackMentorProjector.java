package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import com.slack.api.model.block.LayoutBlock;
import de.tum.cit.aet.hephaestus.agent.mentor.turn.MentorTurnCompletion;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackTs;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import de.tum.cit.aet.hephaestus.integration.slack.metrics.SlackMetrics;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerEvents;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackDelivery;
import de.tum.cit.aet.hephaestus.mentor.MentorSlackDelivery.SlackTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Projects the Heph turns asked in Slack into the developer's Slack DM, on the server that claims the question
 * ({@link MentorSlackDelivery#claim}). {@code docs/contributor/unified-pi-runtime.mdx} § Slack replies tells what each
 * reply shows and how each case ends.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
@WorkspaceAgnostic("Projects ledger notifications of every workspace; each turn is read with its own workspace")
public class SlackMentorProjector implements MentorLedgerNotifications.Listener {

    private static final Logger log = LoggerFactory.getLogger(SlackMentorProjector.class);

    /**
     * Slack animates the text between appends, so more frequent appends add API calls and no smoothness. A refused
     * append also waits this long before the stream is tried again.
     */
    static final Duration APPEND_INTERVAL = Duration.ofSeconds(1);

    /**
     * {@code chat.appendStream} is Tier 4: Slack allows 100 calls a minute for each workspace. On one server, the
     * appends of running turns' streams in one workspace are at least this far apart. Server replicas do not share
     * this spacing, and the rest of an answer at the end of its turn is not spaced; Slack's 429 limits those, and a
     * rate-limited append is tried again.
     */
    static final Duration WORKSPACE_APPEND_SPACING = Duration.ofMillis(600);

    /**
     * How far Slack's clock may differ from this server's when the stream that a refused start may have opened is
     * looked for; both keep time by NTP.
     */
    private static final Duration START_SKEW = Duration.ofSeconds(2);

    /** Stays below the limit of one {@code markdown_text} with room for multi-byte characters. */
    private static final int MAX_APPEND_CHARS = 8_000;

    /**
     * Refused appends in a row after which the stream takes no more text; the end of the turn handles it as a stream
     * whose text is unknown ({@link StreamEnd#REPLACE}).
     */
    private static final int MAX_STREAM_FAILURES = 8;

    /** The Slack error of a stream that a user stopped in Slack. */
    private static final String STOPPED_BY_DEVELOPER = "stopped_by_user";

    /**
     * The Slack error of a stream that is not open. Slack ends a stream on its own after a timeout that it does not
     * document, for example while Heph waits for a tool. A developer stop also makes the turn {@code STOPPED}.
     */
    private static final String NOT_STREAMING = "message_not_in_streaming_state";

    /**
     * Slack errors after which a stream takes no more text: its message, thread or channel is gone, Slack refuses to
     * change it, or Silent Mode or a lost connection keeps the text from Slack. The end of the turn tries to post the
     * reply as a new message.
     */
    private static final Set<String> STREAM_GONE = Set.of(
            "message_not_found",
            "channel_not_found",
            "thread_not_found",
            "is_archived",
            "cant_update_message",
            "message_not_owned_by_app",
            SlackMentorPoster.SILENT_MODE,
            "no_active_slack_connection");

    private final MentorLedgerNotifications notifications;
    private final MentorSlackDelivery delivery;
    private final MentorTurnCompletion completion;
    private final SlackMessageService slack;
    private final SlackMentorPoster poster;
    private final Clock clock;
    private final MeterRegistry meters;

    private final Map<UUID, Projection> projections = new ConcurrentHashMap<>();

    /** When each workspace may take its next append ({@link #WORKSPACE_APPEND_SPACING}). Guarded by {@code this}. */
    private final Map<Long, Instant> appendSlots = new HashMap<>();

    private final ExecutorService workers;
    private final ScheduledExecutorService timer;

    @Autowired
    public SlackMentorProjector(
            MentorLedgerNotifications notifications,
            MentorSlackDelivery delivery,
            MentorTurnCompletion completion,
            SlackMessageService slack,
            SlackMentorPoster poster,
            Clock clock,
            MeterRegistry meters) {
        this(
                notifications,
                delivery,
                completion,
                slack,
                poster,
                clock,
                meters,
                Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
                        .name("slack-heph-timer")
                        .daemon(true)
                        .factory()));
    }

    /**
     * @param workers runs each turn's projection off the notification thread, because Slack calls can wait
     * @param timer wakes a turn's projection when its stream may take more text
     */
    SlackMentorProjector(
            MentorLedgerNotifications notifications,
            MentorSlackDelivery delivery,
            MentorTurnCompletion completion,
            SlackMessageService slack,
            SlackMentorPoster poster,
            Clock clock,
            MeterRegistry meters,
            ExecutorService workers,
            ScheduledExecutorService timer) {
        this.notifications = notifications;
        this.delivery = delivery;
        this.completion = completion;
        this.slack = slack;
        this.poster = poster;
        this.clock = clock;
        this.meters = meters;
        this.workers = workers;
        this.timer = timer;
    }

    @EventListener(ApplicationReadyEvent.class)
    void listen() {
        notifications.subscribe(this);
    }

    @Override
    public void turnChanged(MentorLedgerEvents.TurnChanged change) {
        if (change.surface() != MentorTurn.Surface.SLACK) {
            return;
        }
        Projection projection =
                projections.computeIfAbsent(change.turnId(), id -> new Projection(change.workspaceId(), id));
        projection.status = change.status();
        if (projection.owner == Owner.ELSEWHERE) {
            if (change.status().isTerminal()) {
                projections.remove(change.turnId());
            }
            return;
        }
        if (projection.owner == Owner.HERE
                && projection.stream == null
                && change.status() == MentorTurn.Status.RUNNING) {
            return; // A partial answer changes nothing in a top-level reply, which is posted when the turn ends.
        }
        signal(projection);
    }

    /** Finishes the reply of an ended turn that the reconciler took: its server is gone, or left it uncertain. */
    void reconcile(long workspaceId, UUID turnId) {
        Optional<SlackTurn> found = delivery.slackTurn(workspaceId, turnId);
        if (found.isEmpty() || !slack.isConnected(workspaceId)) {
            delivery.giveUp(workspaceId, turnId);
            return;
        }
        Projection projection = projections.computeIfAbsent(turnId, id -> new Projection(workspaceId, id));
        projection.lock.lock();
        try {
            if (!projection.finished) {
                finish(found.get(), null, true);
                finished(projection);
            }
        } finally {
            projection.lock.unlock();
        }
    }

    private void signal(Projection projection) {
        if (projection.dirty.compareAndSet(false, true)) {
            workers.execute(() -> drain(projection));
        }
    }

    private void drain(Projection projection) {
        projection.lock.lock();
        try {
            while (projection.dirty.getAndSet(false)) {
                try {
                    step(projection);
                } catch (RuntimeException e) {
                    log.warn("Slack Heph projection failed: turnId={}", projection.turnId, e);
                }
            }
        } finally {
            projection.lock.unlock();
        }
    }

    /**
     * One look at the turn: take it, stream it, or finish it. After an append, a running turn is not read again
     * before the append interval passes. Runs under the projection's lock.
     */
    private void step(Projection projection) {
        if (projection.owner == Owner.ELSEWHERE || projection.finished) {
            return;
        }
        ReplyStream stream = projection.stream;
        Instant now = clock.instant();
        if (stream != null && projection.status == MentorTurn.Status.RUNNING && now.isBefore(stream.next)) {
            later(projection, Duration.between(now, stream.next));
            return;
        }
        Optional<MentorTurn> found = delivery.turn(projection.workspaceId, projection.turnId);
        if (found.isEmpty()) {
            projections.remove(projection.turnId);
            return;
        }
        MentorTurn turn = found.get();
        MentorTurn.Status status = turn.getStatus();
        if (status == MentorTurn.Status.QUEUED) {
            if (stream != null) {
                restart(projection, stream);
            }
            return;
        }
        if (status != MentorTurn.Status.RUNNING && turn.isLeased()) {
            return; // Stopped; its host still commits what Heph wrote.
        }
        if (projection.owner == Owner.UNKNOWN && !take(projection, status)) {
            return;
        }
        if (status == MentorTurn.Status.RUNNING) {
            stream = projection.stream;
            if (stream != null) {
                stream(projection, stream, turn);
            }
            return;
        }
        Optional<SlackTurn> ended = delivery.slackTurn(projection.workspaceId, projection.turnId);
        if (ended.isPresent()) {
            finish(ended.get(), stream, false);
        } else {
            delivery.giveUp(projection.workspaceId, projection.turnId);
        }
        finished(projection);
    }

    /** The turn's reply is settled: no wake-up still pending looks at it again. Runs under the lock. */
    private void finished(Projection projection) {
        projection.finished = true;
        ScheduledFuture<?> pending = projection.wakeUp;
        if (pending != null) {
            pending.cancel(false);
        }
        projections.remove(projection.turnId, projection);
    }

    /** Claims the question, and for a running turn shows that Heph works on it. {@code false} when it is not ours. */
    private boolean take(Projection projection, MentorTurn.Status status) {
        long workspaceId = projection.workspaceId;
        Optional<SlackTurn> found = delivery.slackTurn(workspaceId, projection.turnId);
        if (found.isEmpty()
                || !slack.isConnected(workspaceId)
                || !delivery.claim(workspaceId, found.get().question().getId())) {
            projection.owner = Owner.ELSEWHERE; // Another server, or a reply that cannot reach Slack.
            if (status.isTerminal()) {
                projections.remove(projection.turnId);
            }
            return false;
        }
        projection.owner = Owner.HERE;
        SlackTurn turn = found.get();
        String threadTs = turn.threadTs();
        if (status != MentorTurn.Status.RUNNING) {
            return true;
        }
        if (threadTs != null) {
            projection.stream = new ReplyStream(
                    turn.channelId(),
                    threadTs,
                    turn.question().getId(),
                    turn.turn().getAttempt());
        }
        try {
            if (turn.question().getSlackTs() != null) {
                slack.setReaction(
                        workspaceId,
                        turn.channelId(),
                        turn.question().getSlackTs(),
                        SlackMentorPosts.WORKING_EMOJI,
                        true);
            }
        } catch (SlackSendException e) {
            log.warn(
                    "Slack could not show that Heph works: workspaceId={}, turnId={}, error={}",
                    workspaceId,
                    projection.turnId,
                    e.slackError());
        }
        return true;
    }

    /** Sends the part of Heph's partial answer that Slack does not show yet. */
    private void stream(Projection projection, ReplyStream stream, MentorTurn turn) {
        if (turn.getAttempt() != stream.attempt) {
            restart(projection, stream);
            stream.attempt = turn.getAttempt();
        }
        String text = MentorSlackDelivery.writing(turn);
        if (stream.end != StreamEnd.SEND_REST) {
            return;
        }
        if (!text.startsWith(stream.sent)) {
            // Heph's answer no longer starts with what Slack shows, for example after a retried model call.
            stream.end = StreamEnd.REPLACE;
            log.info("Slack stream of Heph turn {} shows text the answer no longer has", projection.turnId);
            return;
        }
        if (text.substring(stream.sent.length()).isBlank()) {
            return;
        }
        Instant now = clock.instant();
        Instant slotOpens = stream.ts == null ? null : takeAppendSlot(projection.workspaceId, now);
        if (slotOpens != null) {
            later(projection, Duration.between(now, slotOpens));
            return;
        }
        try {
            write(projection.workspaceId, stream, text.substring(stream.sent.length()));
            stream.failures = 0;
        } catch (SlackSendException e) {
            stream.end = afterRefusal(e, ++stream.failures);
            if (stream.end == StreamEnd.SEND_REST) {
                later(projection, APPEND_INTERVAL);
            } else {
                log.info("Slack stream of Heph turn {} takes no more text: {}", projection.turnId, e.slackError());
            }
        }
        stream.next = clock.instant().plus(APPEND_INTERVAL);
    }

    /**
     * What a refused append means for the stream. An append whose effect is unknown is not sent again, because Slack
     * may show it already. A rate limit means that Slack took nothing, so the append is tried again.
     */
    private static StreamEnd afterRefusal(SlackSendException e, int failures) {
        String error = e.slackError();
        if (STOPPED_BY_DEVELOPER.equals(error)) {
            return StreamEnd.KEEP;
        }
        if (STREAM_GONE.contains(error)) {
            return StreamEnd.POST;
        }
        boolean effectUnknown = !e.isRateLimited() && SlackMentorPoster.leavesEffectUnknown(e);
        if (NOT_STREAMING.equals(error) || effectUnknown || failures >= MAX_STREAM_FAILURES) {
            return StreamEnd.REPLACE;
        }
        return StreamEnd.SEND_REST;
    }

    /** {@code null} when this call took the workspace's next append, else when that append may happen. */
    private synchronized @Nullable Instant takeAppendSlot(long workspaceId, Instant now) {
        Instant opens = appendSlots.get(workspaceId);
        if (opens != null && now.isBefore(opens)) {
            return opens;
        }
        appendSlots.put(workspaceId, now.plus(WORKSPACE_APPEND_SPACING));
        return null;
    }

    /** Opens the stream with the first text, then appends, in pieces Slack accepts. Each piece Slack takes is shown. */
    private void write(long workspaceId, ReplyStream stream, String text) {
        for (int at = 0; at < text.length(); at += MAX_APPEND_CHARS) {
            String piece = text.substring(at, Math.min(text.length(), at + MAX_APPEND_CHARS));
            String ts = stream.ts;
            if (ts != null) {
                slack.appendStream(workspaceId, stream.channelId, ts, piece);
                stream.sent += piece;
            } else {
                Instant tried = clock.instant();
                try {
                    ts = slack.startStream(workspaceId, stream.channelId, stream.threadTs, piece);
                } catch (SlackSendException e) {
                    stream.failedStart = new FailedStart(tried, clock.instant());
                    throw e;
                }
                stream.ts = ts;
                stream.sent += piece;
                delivery.streaming(workspaceId, stream.questionId, ts);
            }
        }
    }

    /**
     * A retried attempt starts its answer again. A stream of the lost attempt that Slack still streams ends with a note
     * that points below it.
     */
    private void restart(Projection projection, ReplyStream stream) {
        String ts = stream.ts;
        if (ts != null) {
            if (stream.end == StreamEnd.SEND_REST || stream.end == StreamEnd.REPLACE) {
                try {
                    slack.stopStream(
                            projection.workspaceId,
                            stream.channelId,
                            ts,
                            new SlackMessageService.StreamStop(SlackMentorPosts.RESTART_NOTE, List.of(), null));
                } catch (SlackSendException e) {
                    log.debug("Slack stream of a lost attempt did not end: {}", e.slackError());
                }
            }
            delivery.streaming(projection.workspaceId, stream.questionId, null);
        }
        stream.ts = null;
        stream.sent = "";
        stream.end = StreamEnd.SEND_REST;
        stream.failures = 0;
        stream.failedStart = null;
    }

    /** Looks at the turn again after {@code delay}, once however often it is asked. Runs under the lock. */
    private void later(Projection projection, Duration delay) {
        if (projection.finished) {
            return;
        }
        ScheduledFuture<?> pending = projection.wakeUp;
        if (pending == null || pending.isDone()) {
            projection.wakeUp =
                    timer.schedule(() -> signal(projection), Math.max(1, delay.toMillis()), TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Sends the reply of a turn that ended, settles its question, and ends Slack's working state. The reply is
     * Heph's answer; or the reason the turn failed; or nothing when the developer stopped the turn before Heph wrote
     * anything or deleted the question.
     *
     * @param stream the reply's stream on this server; {@code null} for a top-level reply or a reconciled one
     * @param reconciling whether an earlier try may have posted already, so a post is looked for first, and a stream
     *     that a server opened is ended or replaced as {@link #endStream} tells
     */
    private void finish(SlackTurn turn, @Nullable ReplyStream stream, boolean reconciling) {
        MentorTurn.Status status = turn.turn().getStatus();
        SlackMentorPosts.Reply reply = SlackMentorPosts.reply(turn.messages());
        boolean redacted = turn.question().isRedacted();
        UUID answerId =
                redacted || status == MentorTurn.Status.FAILED || reply.text().isBlank() ? null : turn.answerId();
        String failure = answerId == null && !redacted && status != MentorTurn.Status.STOPPED
                ? SlackMentorPosts.failedReply(turn.turn().getError())
                : null;
        FailedStart failedStart = stream == null ? null : stream.failedStart;
        if (stream != null && stream.end == StreamEnd.REPLACE && stream.ts == null && failedStart != null) {
            stream.ts = unknownStream(turn, failedStart);
            if (stream.ts != null) {
                delivery.streaming(turn.turn().getWorkspaceId(), turn.question().getId(), stream.ts);
            }
        }
        String streamTs = reconciling ? turn.question().getSlackStreamTs() : stream == null ? null : stream.ts;
        if (stream != null && stream.end == StreamEnd.POST && streamTs != null) {
            // A reconciler then looks for the post, not for the stream.
            delivery.streaming(turn.turn().getWorkspaceId(), turn.question().getId(), null);
            streamTs = null;
        }
        Replied replied;
        if (streamTs != null) {
            replied = endStream(turn, stream, streamTs, answerId, reply, failure);
        } else if (answerId != null || failure != null) {
            replied = post(turn, stream, answerId, reply, failure != null ? failure : reply.text(), reconciling);
        } else {
            delivery.settle(turn.turn().getWorkspaceId(), turn.question().getId(), MentorEntry.SlackDelivery.FAILED);
            replied = Replied.NOTHING;
        }
        endWorking(turn);
        if (replied != Replied.NOTHING) {
            meters.counter(SlackMetrics.MENTOR_SLACK_REPLY, "state", replied.state)
                    .increment();
        }
    }

    /**
     * The stream that a refused {@code chat.startStream} may still have opened, so that the reply replaces it and does
     * not appear under it. Slack's start takes no metadata or idempotency key, so the stream is found by what only it
     * can be: a message of this app's bot ({@code bot_id}) in the thread, without metadata
     * ({@link SlackMentorPosts#noticeMetadata}), posted while the refused start ran. While the turn ran, no other turn
     * of the thread ran. {@code null} when the history shows none, more than one, or cannot be read: the reply is then
     * posted and overwrites nothing.
     */
    private @Nullable String unknownStream(SlackTurn turn, FailedStart start) {
        long workspaceId = turn.turn().getWorkspaceId();
        String botId = slack.resolveBot(workspaceId)
                .map(SlackMessageService.BotIdentity::botId)
                .orElse(null);
        if (botId == null) {
            return null;
        }
        String from = SlackTs.ofInstant(start.from().minus(START_SKEW));
        String until = SlackTs.ofInstant(start.until().plus(START_SKEW));
        try {
            List<String> found = slack.findWorkspaceMessages(
                    workspaceId,
                    turn.channelId(),
                    turn.threadTs(),
                    from,
                    message -> botId.equals(message.getBotId())
                            && message.getMetadata() == null
                            && SlackTs.compare(message.getTs(), from) >= 0
                            && SlackTs.compare(message.getTs(), until) <= 0);
            return found.size() == 1 ? found.getFirst() : null;
        } catch (SlackSendException e) {
            log.debug("Slack history did not show an unknown stream: {}", e.slackError());
            return null;
        }
    }

    /** Posts the answer or the failure reason as one message, and delivers the feedback that its text shows. */
    private Replied post(
            SlackTurn turn,
            @Nullable ReplyStream stream,
            @Nullable UUID answerId,
            SlackMentorPosts.Reply reply,
            String text,
            boolean reconciling) {
        long workspaceId = turn.turn().getWorkspaceId();
        UUID questionId = turn.question().getId();
        SlackMentorPoster.Post post = new SlackMentorPoster.Post(
                turn.channelId(),
                turn.threadTs(),
                blocks(turn, text, answerId),
                SlackMentorPosts.notification(text),
                answerId);
        SlackMentorPoster.Result result = reconciling
                ? poster.reconcile(workspaceId, questionId, post, oldest(turn))
                : poster.send(workspaceId, questionId, post);
        return switch (result.outcome()) {
            case DELIVERED -> {
                if (answerId != null) {
                    completion.recordDelivery(
                            workspaceId,
                            turn.turn().getId(),
                            reply.shownIn(text.substring(0, SlackMentorPosts.shownLength(text))));
                }
                yield Replied.DELIVERED;
            }
            case SILENCED -> silenced(turn, stream, answerId, reply);
            case FAILED -> Replied.FAILED;
            case UNKNOWN -> Replied.UNCERTAIN;
        };
    }

    /** The reply's blocks: its Markdown, and in a Slack thread the answer's feedback buttons under it. */
    private static List<LayoutBlock> blocks(SlackTurn turn, String text, @Nullable UUID answerId) {
        List<LayoutBlock> blocks = SlackMentorPosts.markdown(text);
        if (answerId == null || !turn.inThread()) {
            return blocks;
        }
        return Stream.concat(blocks.stream(), SlackMentorPosts.feedbackButtons(answerId).stream())
                .toList();
    }

    /**
     * Ends the reply's stream under the answer's feedback buttons; the reason a turn failed is its last text. On this
     * server, the rest of the answer is sent first. A stream that the developer stopped, or the stream of a stopped
     * turn, ends where it is. A stream that a server left behind ends with a note that the full answer is in
     * Hephaestus and delivers no feedback. A stream whose text is not known to start the reply, or that Slack already
     * ended, is replaced with the whole reply ({@link #replaceStream}); for a stopped turn, with its stored answer.
     */
    private Replied endStream(
            SlackTurn turn,
            @Nullable ReplyStream stream,
            String ts,
            @Nullable UUID answerId,
            SlackMentorPosts.Reply reply,
            @Nullable String failure) {
        long workspaceId = turn.turn().getWorkspaceId();
        UUID questionId = turn.question().getId();
        MentorTurn.Status status = turn.turn().getStatus();
        StreamEnd end = stream == null ? StreamEnd.SEND_REST : stream.end;
        if (stream != null
                && end == StreamEnd.SEND_REST
                && answerId != null
                && !reply.text().startsWith(stream.sent)) {
            end = StreamEnd.REPLACE; // The answer no longer starts with what the stream shows.
        }
        // What replaces a stream whose text is not the reply's start: the reason a turn failed, a whole answer, or
        // the stored answer of a stopped turn.
        boolean answered =
                status == MentorTurn.Status.DONE || (status == MentorTurn.Status.STOPPED && end == StreamEnd.REPLACE);
        String whole = failure != null ? failure : answerId != null && answered ? reply.text() : null;
        boolean replace = end == StreamEnd.REPLACE && whole != null;
        if (end != StreamEnd.KEEP) {
            try {
                if (stream != null
                        && end == StreamEnd.SEND_REST
                        && answerId != null
                        && status != MentorTurn.Status.STOPPED) {
                    String rest = reply.text().substring(stream.sent.length());
                    if (!rest.isBlank()) {
                        write(workspaceId, stream, rest);
                    }
                }
                String last = replace
                        ? null
                        : failure != null
                                ? "\n\n" + failure
                                : stream == null && whole != null ? SlackMentorPosts.CUT_NOTE : null;
                slack.stopStream(
                        workspaceId,
                        turn.channelId(),
                        ts,
                        new SlackMessageService.StreamStop(
                                last,
                                answerId == null || replace ? List.of() : SlackMentorPosts.feedbackButtons(answerId),
                                SlackMentorPosts.metadata(questionId)));
            } catch (SlackSendException e) {
                if (NOT_STREAMING.equals(e.slackError())) {
                    replace = whole != null;
                } else if (!STOPPED_BY_DEVELOPER.equals(e.slackError())) {
                    return notEnded(turn, stream, answerId, reply, e);
                }
            }
        }
        if (replace && whole != null) {
            return replaceStream(turn, stream, ts, answerId, reply, whole);
        }
        delivery.delivered(workspaceId, questionId, answerId, ts);
        if (stream != null && answerId != null) {
            completion.recordDelivery(workspaceId, turn.turn().getId(), reply.shownIn(stream.sent));
        }
        return Replied.DELIVERED;
    }

    /**
     * Replaces the stream's message with {@code text}: the whole answer under its feedback buttons, or the reason the
     * turn failed. The message then shows a known text, so the feedback that text shows is delivered.
     */
    private Replied replaceStream(
            SlackTurn turn,
            @Nullable ReplyStream stream,
            String ts,
            @Nullable UUID answerId,
            SlackMentorPosts.Reply reply,
            String text) {
        long workspaceId = turn.turn().getWorkspaceId();
        UUID questionId = turn.question().getId();
        try {
            slack.updateMessage(
                    workspaceId,
                    turn.channelId(),
                    ts,
                    blocks(turn, text, answerId),
                    SlackMentorPosts.notification(text),
                    SlackMentorPosts.metadata(questionId));
        } catch (SlackSendException e) {
            return notEnded(turn, stream, answerId, reply, e);
        }
        delivery.delivered(workspaceId, questionId, answerId, ts);
        if (answerId != null) {
            completion.recordDelivery(
                    workspaceId,
                    turn.turn().getId(),
                    reply.shownIn(text.substring(0, SlackMentorPosts.shownLength(text))));
        }
        return Replied.DELIVERED;
    }

    /** Settles a reply whose stream Slack did not let end: uncertain when Slack may have acted, else failed. */
    private Replied notEnded(
            SlackTurn turn,
            @Nullable ReplyStream stream,
            @Nullable UUID answerId,
            SlackMentorPosts.Reply reply,
            SlackSendException e) {
        long workspaceId = turn.turn().getWorkspaceId();
        UUID questionId = turn.question().getId();
        log.warn(
                "Slack did not end or replace the stream of Heph's reply: workspaceId={}, turnId={}, error={}",
                workspaceId,
                turn.turn().getId(),
                e.slackError());
        if (SlackMentorPoster.leavesEffectUnknown(e)) {
            delivery.markUncertain(workspaceId, questionId);
            return Replied.UNCERTAIN;
        }
        delivery.settle(workspaceId, questionId, MentorEntry.SlackDelivery.FAILED);
        return SlackMentorPoster.SILENT_MODE.equals(e.slackError())
                ? silenced(turn, stream, answerId, reply)
                : Replied.FAILED;
    }

    /**
     * Silent Mode kept the reply from Slack; see {@code docs/contributor/unified-pi-runtime.mdx} § Delivery of shown
     * feedback.
     */
    private Replied silenced(
            SlackTurn turn, @Nullable ReplyStream stream, @Nullable UUID answerId, SlackMentorPosts.Reply reply) {
        if (answerId != null) {
            long workspaceId = turn.turn().getWorkspaceId();
            if (stream != null) {
                completion.recordDelivery(workspaceId, turn.turn().getId(), reply.shownIn(stream.sent));
            }
            completion.recordSilenced(workspaceId, turn.turn().getId());
        }
        return Replied.FAILED;
    }

    /** Removes 👀 from the question. */
    private void endWorking(SlackTurn turn) {
        String questionTs = turn.question().getSlackTs();
        if (questionTs == null || turn.question().isRedacted()) {
            return;
        }
        try {
            slack.setReaction(
                    turn.turn().getWorkspaceId(), turn.channelId(), questionTs, SlackMentorPosts.WORKING_EMOJI, false);
        } catch (SlackSendException e) {
            log.debug(
                    "Slack working state of Heph turn {} did not end: {}",
                    turn.turn().getId(),
                    e.slackError());
        }
    }

    /** History before the question cannot hold its reply. */
    private static String oldest(SlackTurn turn) {
        String questionTs = turn.question().getSlackTs();
        if (questionTs != null) {
            return questionTs;
        }
        return SlackTs.ofInstant(turn.turn().getCreatedAt());
    }

    /** Notifications were missed: turns of other servers are forgotten, and this server's turns are read again. */
    @Override
    public void resync() {
        projections.values().removeIf(projection -> projection.owner != Owner.HERE);
        projections.values().forEach(this::signal);
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
        workers.shutdown();
        try {
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private enum Owner {
        UNKNOWN,
        /** This server claimed the question. */
        HERE,
        /** Another server, or nobody: the reply cannot reach Slack. */
        ELSEWHERE,
    }

    /** What became of a reply, as {@code mentor_slack_reply_total} counts it; {@code NOTHING} is not counted. */
    private enum Replied {
        DELIVERED("delivered"),
        FAILED("failed"),
        UNCERTAIN("uncertain"),
        NOTHING("");

        final String state;

        Replied(String state) {
            this.state = state;
        }
    }

    /** What the end of the turn does with a stream that this server opened. */
    private enum StreamEnd {
        /** Sends the rest of the reply, then ends the stream under the answer's feedback buttons. */
        SEND_REST,
        /** The developer stopped the stream: it ends where it is. */
        KEEP,
        /** Slack ended the stream, or refused appends leave its text unknown: the whole reply replaces it. */
        REPLACE,
        /** The stream's message is gone or Slack refuses to change it: the reply is posted as a new message. */
        POST,
    }

    /** When a refused {@code chat.startStream} ran, by this server's clock. */
    private record FailedStart(Instant from, Instant until) {}

    /** One turn on this server. The volatile fields are shared with the notification thread. */
    private static final class Projection {
        final long workspaceId;
        final UUID turnId;
        final ReentrantLock lock = new ReentrantLock();
        final AtomicBoolean dirty = new AtomicBoolean();

        @Nullable
        ScheduledFuture<?> wakeUp;

        /** The reply is settled; nothing looks at the turn again. */
        boolean finished;

        volatile Owner owner = Owner.UNKNOWN;
        volatile MentorTurn.@Nullable Status status;
        volatile @Nullable ReplyStream stream;

        Projection(long workspaceId, UUID turnId) {
            this.workspaceId = workspaceId;
            this.turnId = turnId;
        }
    }

    /** The stream of a reply in a Slack thread that this server projects. Changes under its projection's lock. */
    private static final class ReplyStream {
        final String channelId;
        final String threadTs;
        final UUID questionId;
        int attempt;

        @Nullable
        String ts;

        /** The text of Heph's reply that the stream shows; after an append with an unknown effect, it may show more. */
        String sent = "";

        StreamEnd end = StreamEnd.SEND_REST;

        /** The last {@code chat.startStream} that Slack refused; it may have opened a stream anyway. */
        @Nullable
        FailedStart failedStart;

        int failures;
        Instant next = Instant.EPOCH;

        ReplyStream(String channelId, String threadTs, UUID questionId, int attempt) {
            this.channelId = channelId;
            this.threadTs = threadTs;
            this.questionId = questionId;
            this.attempt = attempt;
        }
    }
}
