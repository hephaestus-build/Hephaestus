package de.tum.cit.aet.hephaestus.integration.slack.mentor;

import de.tum.cit.aet.hephaestus.agent.mentor.chat.MentorChannel;
import de.tum.cit.aet.hephaestus.agent.mentor.chat.wire.UIMessageChunk;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Streams mentor output to Slack with bounded buffering and retry. */
public class SlackStreamingMentorChannel implements MentorChannel {

    private static final Logger log = LoggerFactory.getLogger(SlackStreamingMentorChannel.class);

    /** Flush cadence. Slack animates between appends; one append per second avoids noisy API churn. */
    private static final long FLUSH_INTERVAL_MS = 1000;
    /** First flush shortly after the first delta, for a snappy first paint (well under the 2-minute status timeout). */
    private static final long INITIAL_DELAY_MS = 350;
    /** Cap a single append below Slack's 12k {@code markdown_text} limit; also bounds how long one giant token is held. */
    private static final int MAX_APPEND_CHARS = 8000;
    /** Give up (and abort) after this many consecutive transient failures so a Slack outage cannot wedge a turn. */
    private static final int MAX_CONSECUTIVE_FAILURES = 8;
    /** Cap a single honored {@code Retry-After} wait so a hostile/absurd header cannot wedge the flush thread. */
    private static final long MAX_RATE_LIMIT_WAIT_MS = 20_000;
    /**
     * A sane ceiling on back-to-back throttles: a rate-limit is deliberately NOT counted against
     * {@value #MAX_CONSECUTIVE_FAILURES}, but an unbounded 429 storm must still terminate the turn eventually.
     */
    private static final int MAX_CONSECUTIVE_RATE_LIMITS = 20;

    private static final Duration GRACEFUL_SHUTDOWN_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration FORCED_SHUTDOWN_TIMEOUT = Duration.ofSeconds(10);

    private static final Set<String> TERMINAL_ERRORS = Set.of(
            "message_not_found",
            "channel_not_found",
            "thread_not_found",
            "cant_update_message",
            "message_deleted",
            "is_archived",
            "stream_not_found",
            "cant_stream",
            "silent_mode_engaged");

    private final SlackMessageService slack;
    private final long workspaceId;
    private final String channel;
    private final @Nullable String threadTs;
    private final Duration gracefulShutdownTimeout;
    private final Duration forcedShutdownTimeout;
    private final long initialFlushDelayMillis;
    private final long flushIntervalMillis;
    private final SlackMentorTextFilter textFilter = new SlackMentorTextFilter();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "slack-mentor-stream");
        t.setDaemon(true);
        return t;
    });

    private final StringBuilder pending = new StringBuilder();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean done = new AtomicBoolean(false);
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    /** The whole reply reached Slack and its stream was stopped; a prefix does not count. */
    private final AtomicBoolean replyDelivered = new AtomicBoolean(false);

    private final AtomicBoolean silentModeSuppressed = new AtomicBoolean(false);

    private volatile @Nullable Runnable disconnectHook;
    /** The streaming message ts: {@code null} until the first successful open, set exactly once ({@link #openOrAppend}). */
    private final AtomicReference<String> streamTs = new AtomicReference<>();
    /** Serializes the open-or-append decision so the stream is opened exactly once across the flush + runner threads. */
    private final ReentrantLock streamLock = new ReentrantLock();

    private volatile @Nullable ScheduledFuture<?> flushTask;
    private int consecutiveFailures; // flush-thread only
    private int consecutiveRateLimits; // flush-thread only

    public SlackStreamingMentorChannel(
            SlackMessageService slack, long workspaceId, String channel, @Nullable String threadTs) {
        this(
                slack,
                workspaceId,
                channel,
                threadTs,
                GRACEFUL_SHUTDOWN_TIMEOUT,
                FORCED_SHUTDOWN_TIMEOUT,
                INITIAL_DELAY_MS,
                FLUSH_INTERVAL_MS);
    }

    SlackStreamingMentorChannel(
            SlackMessageService slack,
            long workspaceId,
            String channel,
            @Nullable String threadTs,
            Duration gracefulShutdownTimeout,
            Duration forcedShutdownTimeout,
            long initialFlushDelayMillis,
            long flushIntervalMillis) {
        this.slack = slack;
        this.workspaceId = workspaceId;
        this.channel = channel;
        this.threadTs = threadTs;
        this.gracefulShutdownTimeout = gracefulShutdownTimeout;
        this.forcedShutdownTimeout = forcedShutdownTimeout;
        this.initialFlushDelayMillis = initialFlushDelayMillis;
        this.flushIntervalMillis = flushIntervalMillis;
    }

    @Override
    public void onDisconnect(Runnable hook) {
        this.disconnectHook = hook;
        if (terminated.get()) {
            hook.run();
        }
    }

    @Override
    public boolean isClientGone() {
        return terminated.get();
    }

    @Override
    public void startKeepAlive() {
        // Liveness while the sandbox warms up. Best-effort (assistant threads only); superseded by the first stream
        // write.
        slack.setStatus(workspaceId, channel, threadTs, "Thinking...");
        ensureFlushing();
    }

    @Override
    public void send(UIMessageChunk chunk) {
        if (done.get() || terminated.get()) {
            return;
        }
        if (chunk instanceof UIMessageChunk.TextDelta delta) {
            append(textFilter.onDelta(delta.delta()));
            ensureFlushing();
        } else if (chunk instanceof UIMessageChunk.Error error) {
            // Surface mid-turn errors in the visible stream rather than dropping them.
            append(textFilter.finish() + "\n\n⚠️ " + SlackMentorTextFilter.normalize(safeError(error.errorText())));
            ensureFlushing();
        } else if (chunk instanceof UIMessageChunk.ToolInputStart) {
            slack.setStatus(workspaceId, channel, threadTs, "Reviewing your practice history...");
        }
        // Start/Reasoning/tool-output/Finish chunks are not part of the visible Slack stream; the orchestrator
        // drives terminals through completeWith*.
    }

    @Override
    public DeliveryOutcome completeWithDone() {
        finish(null);
        return deliveryOutcome();
    }

    @Override
    public void completeWithError(String errorText) {
        finish("\n\n⚠️ " + SlackMentorTextFilter.normalize(safeError(errorText)));
    }

    @Override
    public void completeWithConflict() {
        if (!done.compareAndSet(false, true)) {
            return;
        }
        stopFlusher();
        slack.setStatus(workspaceId, channel, threadTs, "Still working on the previous message...");
    }

    @Override
    public void close() {
        // Ends the stream if no terminal did.
        finish(null);
    }

    private DeliveryOutcome deliveryOutcome() {
        if (replyDelivered.get()) {
            return DeliveryOutcome.DELIVERED;
        }
        return silentModeSuppressed.get() ? DeliveryOutcome.INSTANCE_SILENCED : DeliveryOutcome.NOT_DELIVERED;
    }

    private void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            pending.append(text);
        } finally {
            lock.unlock();
        }
    }

    /** Start the periodic flush loop once (idempotent); driven off {@link #startKeepAlive}/{@link #send}. */
    private void ensureFlushing() {
        if (done.get() || terminated.get() || !flushing.compareAndSet(false, true)) {
            return;
        }
        flushTask = scheduler.scheduleWithFixedDelay(
                this::tick, initialFlushDelayMillis, flushIntervalMillis, TimeUnit.MILLISECONDS);
    }

    /** One flush tick: drain a whitespace-aligned prefix and write it to Slack. Runs single-threaded. */
    private void tick() {
        if (done.get() || terminated.get()) {
            return;
        }
        String toSend = drain(false);
        if (toSend != null) {
            write(toSend);
        }
    }

    /**
     * Remove and return the largest safe prefix of {@code pending}: everything up to the last whitespace (so a
     * word is never split), capped at {@value #MAX_APPEND_CHARS}. Returns {@code null} when nothing is safe to
     * send yet. {@code force} (terminal) drains everything.
     */
    private @Nullable String drain(boolean force) {
        lock.lock();
        try {
            int len = pending.length();
            if (len == 0) {
                return null;
            }
            int cut = force ? len : safeCut(pending);
            if (cut <= 0) {
                return null;
            }
            if (cut > MAX_APPEND_CHARS) {
                cut = MAX_APPEND_CHARS;
            }
            String out = pending.substring(0, cut);
            pending.delete(0, cut);
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** Last whitespace boundary (so a word is never split), or the whole buffer once a lone token grows past the cap. */
    private static int safeCut(CharSequence buf) {
        for (int i = buf.length() - 1; i >= 0; i--) {
            char c = buf.charAt(i);
            if (c == '\n' || c == ' ' || c == '\t') {
                return i + 1;
            }
        }
        return buf.length() >= MAX_APPEND_CHARS ? buf.length() : 0;
    }

    /** Open the stream on the first write, then append. Transient failures re-buffer and retry; "gone" aborts. */
    private void write(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            openOrAppend(text);
            consecutiveFailures = 0;
            consecutiveRateLimits = 0;
        } catch (SlackSendException e) {
            if (isTerminal(e)) {
                terminate();
                return;
            }
            // Put the text back at the front so no delta is lost, whatever the failure kind.
            lock.lock();
            try {
                pending.insert(0, text);
            } finally {
                lock.unlock();
            }
            if (e.isRateLimited()) {
                // A throttle is NOT a stream death: honor Slack's Retry-After (bounded, jittered) on this flush
                // thread and DO NOT count it against the transient-failure abort budget. A separate high ceiling
                // still terminates an unbounded 429 storm.
                honorRetryAfter(e.retryAfterMillis());
                if (++consecutiveRateLimits >= MAX_CONSECUTIVE_RATE_LIMITS) {
                    log.warn(
                            "Slack stream giving up after {} consecutive rate-limits (channel={})",
                            consecutiveRateLimits,
                            channel);
                    terminate();
                }
                return;
            }
            // Transient (5xx / transport): retry next tick, counting toward the abort budget.
            if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                log.warn(
                        "Slack stream giving up after {} transient failures (channel={}): {}",
                        consecutiveFailures,
                        channel,
                        e.slackError());
                terminate();
            } else {
                log.debug(
                        "Slack stream append retry {} (channel={}): {}", consecutiveFailures, channel, e.slackError());
            }
        }
    }

    /**
     * Open the stream on the first call, append on every later one — serialized on {@link #streamLock} so a late
     * flush tick and the terminal write can never both call {@code startStream} (exactly one open). {@code streamTs}
     * is only set on a successful open, so a failed open leaves the stream un-opened and the next call retries it.
     * Throws the {@link SlackSendException} unchanged so callers keep their transient/gone handling.
     */
    private void openOrAppend(String text) {
        streamLock.lock();
        try {
            String ts = streamTs.get();
            if (ts == null) {
                streamTs.set(slack.startStream(workspaceId, channel, threadTs, text));
            } else {
                slack.appendStream(workspaceId, channel, ts, text);
            }
        } finally {
            streamLock.unlock();
        }
    }

    /**
     * Sleep for Slack's requested {@code Retry-After} (capped at {@value #MAX_RATE_LIMIT_WAIT_MS} ms) plus a small
     * jitter, on the flush thread. Interruption (from {@code stopFlusher}'s {@code shutdownNow}) just returns so a
     * terminating turn is never blocked.
     */
    private static void honorRetryAfter(long retryAfterMs) {
        long base = Math.min(Math.max(retryAfterMs, 0L), MAX_RATE_LIMIT_WAIT_MS);
        long sleepMs = base + ThreadLocalRandom.current().nextLong(0L, 250L);
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void finish(@Nullable String suffix) {
        if (!done.compareAndSet(false, true)) {
            return; // idempotent terminal
        }
        // A tick still in flight may yet fail and requeue text no one will send, so the reply cannot be vouched for.
        boolean flusherStopped = stopFlusher();

        String remainder;
        lock.lock();
        try {
            pending.append(textFilter.finish());
            if (suffix != null) {
                pending.append(suffix);
            }
            remainder = pending.toString();
            pending.setLength(0);
        } finally {
            lock.unlock();
        }

        if (terminated.get()) {
            return; // target is gone; nothing to finalize
        }
        // A turn that finishes before the first flush tick (or leaves a tail) writes here. No further tick will
        // run, so retry the terminal write inline rather than dropping it on a transient blip.
        boolean unopened = streamTs.get() == null;
        String body = (unopened && remainder.isBlank()) ? "_(the mentor produced no response)_" : remainder;
        boolean written = (body.isBlank() && !unopened) || terminalWrite(body);
        try {
            String ts = streamTs.get();
            if (ts != null && !terminated.get()) {
                slack.stopStream(workspaceId, channel, ts, List.of());
                replyDelivered.set(written && flusherStopped);
            }
        } catch (Exception e) {
            // Terminals never throw (contract). A gone recipient just means the stream is already finalized.
            log.debug("Slack stream finalize skipped (channel={}): {}", channel, e.getMessage());
        }
    }

    /**
     * Terminal content write (open or append) with a few transient retries — the flush loop is already stopped.
     *
     * @return whether the text reached Slack
     */
    private boolean terminalWrite(String text) {
        int attemptsLeft = 3;
        while (attemptsLeft > 0) {
            try {
                openOrAppend(text);
                return true;
            } catch (SlackSendException e) {
                if (isTerminal(e)) {
                    terminate();
                    return false;
                }
                attemptsLeft--;
                if (attemptsLeft == 0) {
                    log.debug("Slack terminal write gave up (channel={}): {}", channel, e.slackError());
                    return false;
                }
                // Honor a throttle's Retry-After (bounded); otherwise a short fixed transient backoff.
                honorRetryAfter(e.isRateLimited() ? e.retryAfterMillis() : 200L);
                if (Thread.currentThread().isInterrupted()) {
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * Stop the flush loop and wait, boundedly, for any in-flight tick before the terminal finalize. Called only on
     * the runner thread ({@link #finish}, {@link #completeWithConflict}) — never the flush thread, which must not
     * await itself.
     *
     * <p>OkHttp calls do not respond to {@code interrupt}, so a tick stuck inside {@code startStream}/
     * {@code appendStream} keeps running past {@code shutdownNow()}. We therefore await a second, longer bound so
     * the terminal write does not interleave with a live tick. {@link #streamLock} is the hard guarantee against a
     * double open even if that second wait also times out; this bounded wait just avoids overlapping writes.
     *
     * @return {@code false} when a tick outlived both waits and may still requeue text after the terminal drain
     */
    private boolean stopFlusher() {
        ScheduledFuture<?> task = flushTask;
        if (task != null) {
            task.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (scheduler.awaitTermination(gracefulShutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            scheduler.shutdownNow();
            if (scheduler.awaitTermination(forcedShutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            log.warn("Slack stream flush tick still in-flight at finalize (channel={})", channel);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        return false;
    }

    private void terminate() {
        ScheduledFuture<?> task = flushTask;
        if (task != null) {
            task.cancel(false);
        }
        if (terminated.compareAndSet(false, true)) {
            Runnable hook = disconnectHook;
            if (hook != null) {
                hook.run();
            }
        }
    }

    private boolean isTerminal(SlackSendException e) {
        String code = e.slackError();
        if ("silent_mode_engaged".equals(code)) {
            silentModeSuppressed.set(true);
        }
        return code != null && TERMINAL_ERRORS.contains(code);
    }

    private static String safeError(String text) {
        return (text == null || text.isBlank()) ? "The mentor hit an error." : text;
    }
}
