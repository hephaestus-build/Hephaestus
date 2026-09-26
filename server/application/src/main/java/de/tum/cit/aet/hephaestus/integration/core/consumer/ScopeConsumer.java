package de.tum.cit.aet.hephaestus.integration.core.consumer;

import io.nats.client.ConsumerContext;
import io.nats.client.JetStreamApiException;
import io.nats.client.Message;
import io.nats.client.MessageConsumer;
import io.nats.client.StreamContext;
import io.nats.client.api.ConsumerConfiguration;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One JetStream consumer per logical scope, with single-threaded dispatch.
 *
 * <p>Vendor-neutral successor to the pre-unification per-scope consumer wrapper.
 * The shape is identical because the invariants are identical:
 *
 * <ul>
 *   <li>One JetStream consumer (durable or ephemeral) per scope, subscribed to a
 *       wildcard subject filter built from {@link ConsumerSubjectMath}.</li>
 *   <li>Messages flow into an in-process {@link LinkedBlockingQueue}; a dedicated
 *       virtual thread drains it serially. This guarantees per-scope ordering
 *       without imposing global serialization — sibling scopes drain in parallel
 *       on their own virtual threads.</li>
 *   <li>Lifecycle methods are {@code synchronized} to keep start / stop /
 *       updateSubjects transitions atomic with respect to each other.</li>
 *   <li>On stop, the queue is drained and pending messages are NAKed so the
 *       JetStream redelivery loop picks them up after the configured ack-wait —
 *       graceful shutdown without dropping in-flight work.</li>
 * </ul>
 *
 * <p><b>Subject updates.</b> When a scope's repository set changes the consumer's
 * filter subjects must be rewritten. We update the durable consumer's configuration,
 * close the in-flight subscription, and re-subscribe. The queue is preserved so
 * already-enqueued messages still get processed. Atomic at JetStream's level: the
 * server-side rebuild happens before the new subscription starts.
 *
 * <p><b>Thread safety.</b> {@code running} is an {@link AtomicBoolean} read on every
 * enqueue; {@code subscription} and {@code currentSubjects} are {@code volatile} for
 * safe publication; the dispatch loop terminates on interrupt or {@code running=false}.
 */
public final class ScopeConsumer {

    private static final Logger log = LoggerFactory.getLogger(ScopeConsumer.class);
    private static final long STOP_TIMEOUT_MILLIS = 10_000;

    /** Null for the installation-wide consumer; never null for scope consumers. */
    private final @Nullable Long scopeId;

    private final String consumerName;
    private final String streamName;
    private final ConsumerContext context;
    private final StreamContext streamContext;
    private final Consumer<Message> messageHandler;

    private volatile String[] currentSubjects;
    private volatile @Nullable MessageConsumer subscription;
    private volatile @Nullable Thread processorThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Sequential dispatch queue. Unbounded by design — the JetStream
     * {@code max-ack-pending} knob already caps in-flight work upstream.
     */
    private final BlockingQueue<Message> messageQueue = new LinkedBlockingQueue<>();

    public ScopeConsumer(
            @Nullable Long scopeId,
            String consumerName,
            @Nullable String streamName,
            ConsumerContext context,
            StreamContext streamContext,
            String[] subjects,
            @Nullable Consumer<Message> messageHandler) {
        if (consumerName == null || consumerName.isBlank()) {
            throw new IllegalArgumentException("consumerName must not be blank");
        }
        if (streamName == null || streamName.isBlank()) {
            throw new IllegalArgumentException("streamName must not be blank");
        }
        if (context == null) {
            throw new IllegalArgumentException("ConsumerContext must not be null");
        }
        if (streamContext == null) {
            throw new IllegalArgumentException("StreamContext must not be null");
        }
        if (subjects == null) {
            throw new IllegalArgumentException("subjects must not be null");
        }
        if (messageHandler == null) {
            throw new IllegalArgumentException("messageHandler must not be null");
        }
        this.scopeId = scopeId;
        this.consumerName = consumerName;
        this.streamName = streamName;
        this.context = context;
        this.streamContext = streamContext;
        this.currentSubjects = subjects.clone();
        this.messageHandler = messageHandler;
    }

    public String consumerName() {
        return consumerName;
    }

    public String streamName() {
        return streamName;
    }

    public @Nullable Long scopeId() {
        return scopeId;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Starts the consumer: spawns the dispatch virtual thread, then attaches the
     * JetStream subscription. Idempotent. A failed attach stops the dispatch thread again,
     * because a consumer that never started is never tracked and so never stopped.
     */
    public synchronized void start() throws IOException, JetStreamApiException {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        String threadName = "integration-consumer-" + (scopeId != null ? "scope-" + scopeId : "installation");
        processorThread = Thread.ofVirtual().name(threadName).start(this::processMessagesSequentially);
        try {
            subscription = context.consume(this::enqueueMessage);
        } catch (IOException | JetStreamApiException | RuntimeException e) {
            try {
                stop();
            } catch (RuntimeException stopFailure) {
                e.addSuppressed(stopFailure);
            }
            throw e;
        }
        log.debug(
                "Started ScopeConsumer: consumerName={}, scopeId={}, subjectCount={}",
                consumerName,
                scopeId,
                currentSubjects.length);
    }

    /** Stops the subscription and waits until its dispatch thread can no longer write. */
    public synchronized void stop() {
        boolean wasRunning = running.getAndSet(false);
        Thread thread = processorThread;
        if (!wasRunning && thread == null) {
            return;
        }
        if (wasRunning) {
            closeSubscriptionQuietly();
        }
        if (thread != null) {
            thread.interrupt();
            if (thread != Thread.currentThread()) {
                try {
                    thread.join(STOP_TIMEOUT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while stopping consumer " + consumerName, e);
                }
                if (thread.isAlive()) {
                    throw new IllegalStateException("Timed out stopping consumer " + consumerName);
                }
            }
        }
        processorThread = null;
        drainAndNakQueue();
        log.debug("Stopped ScopeConsumer: consumerName={}, scopeId={}", consumerName, scopeId);
    }

    /**
     * Rebuilds the consumer's filter subjects in-place. The server-side durable
     * configuration is updated first; once that succeeds, the local subscription is
     * recycled. Identical subject sets are a no-op only while the subscription is attached,
     * so a subscription lost to a failed recycle is reattached by the next call.
     */
    public synchronized void updateSubjects(String @Nullable [] newSubjects) throws IOException, JetStreamApiException {
        if (newSubjects == null) {
            throw new IllegalArgumentException("newSubjects must not be null");
        }
        boolean subjectsChanged = !Arrays.equals(currentSubjects, newSubjects);
        if (!subjectsChanged && isAttached()) {
            return;
        }
        if (subjectsChanged) {
            ConsumerConfiguration existing = context.getConsumerInfo().getConsumerConfiguration();
            streamContext.createOrUpdateConsumer(ConsumerConfiguration.builder(existing)
                    .filterSubjects(newSubjects)
                    .build());
            currentSubjects = newSubjects.clone();
        }
        closeSubscriptionQuietly();
        subscription = context.consume(this::enqueueMessage);
        log.debug(
                "Updated ScopeConsumer subjects: consumerName={}, scopeId={}, subjectCount={}",
                consumerName,
                scopeId,
                newSubjects.length);
    }

    /** Whether a live JetStream subscription feeds this running consumer. */
    public boolean isAttached() {
        MessageConsumer sub = subscription;
        return running.get() && sub != null && !sub.isStopped() && !sub.isFinished();
    }

    /** The filter subjects JetStream holds for this consumer, read from the server. */
    public Set<String> installedSubjects() throws IOException, JetStreamApiException {
        return Set.copyOf(Objects.requireNonNullElse(
                context.getConsumerInfo().getConsumerConfiguration().getFilterSubjects(), List.of()));
    }

    /**
     * @return the currently-active filter-subject array. Defensive copy: mutations on
     *     the returned array do not affect this consumer's state.
     */
    public String[] currentSubjects() {
        return currentSubjects.clone();
    }

    // Internals

    private void enqueueMessage(Message msg) {
        if (!running.get()) {
            safeNak(msg);
            return;
        }
        try {
            messageQueue.put(msg);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            safeNak(msg);
        }
    }

    private void processMessagesSequentially() {
        log.debug("Dispatch loop started: consumerName={}, scopeId={}", consumerName, scopeId);
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Message msg = messageQueue.poll(1, TimeUnit.SECONDS);
                if (msg != null) {
                    messageHandler.accept(msg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Per-message failures must not kill the loop; the handler is responsible
                // for ACK/NAK and surfaces ITS errors via the poison handler. Anything that
                // escapes the handler here is an UNEXPECTED bug we log loudly.
                log.error("Unexpected error in dispatch loop: consumerName={}, scopeId={}", consumerName, scopeId, e);
            }
        }
        log.debug("Dispatch loop stopped: consumerName={}, scopeId={}", consumerName, scopeId);
    }

    private void closeSubscriptionQuietly() {
        MessageConsumer sub = subscription;
        if (sub == null) {
            return;
        }
        subscription = null;
        try {
            sub.close();
        } catch (Exception e) {
            log.debug("Failed to close subscription quietly: consumerName={}, error={}", consumerName, e.getMessage());
        }
    }

    private void drainAndNakQueue() {
        Message msg;
        while ((msg = messageQueue.poll()) != null) {
            safeNak(msg);
        }
    }

    private static void safeNak(Message msg) {
        try {
            msg.nak();
        } catch (Exception ignored) {
            // Expected during shutdown when connection is closed; we already logged the cause path.
        }
    }
}
