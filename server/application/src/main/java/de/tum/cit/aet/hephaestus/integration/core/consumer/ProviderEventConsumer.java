package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationDispatchScope;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.ProviderEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRecorder;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Handles the provider events in the inbox. A claim leases the oldest due event of a scope ({@link
 * ProviderEventStore#claim}), runs its handler under the handler timeout, and ends the lease: done, retried later, or a
 * dead letter. {@link ProviderEventClaimers} claim on their own threads. The lease is renewed while the handler runs, so a server that stops loses its leases within {@link
 * #LEASE}, and the reaper gives them to another server.
 *
 * <p>A handler that outlives the handler timeout is interrupted. One that stops within {@link #HANDLER_STOP_GRACE}
 * counts as a failed attempt. One that does not stop could keep writing beside the next event of its scope, so this
 * server breaks its liveness and keeps the lease while the thread lives: Kubernetes restarts the Pod, the thread dies
 * with it, and the lease expires.
 */
@Component
@ConditionalOnServerRole
public class ProviderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProviderEventConsumer.class);

    /** How long a lease lasts without a renewal. */
    static final Duration LEASE = Duration.ofMinutes(2);

    private static final Duration RENEWAL = Duration.ofSeconds(30);

    /** How long an interrupted handler may take to stop before this server breaks its liveness. */
    static final Duration HANDLER_STOP_GRACE = Duration.ofSeconds(30);

    /**
     * How long a stopping server waits for an interrupted handler; it fits {@link ProviderEventClaimers#SHUTDOWN_RELEASE}.
     */
    private static final Duration SHUTDOWN_STOP_GRACE = Duration.ofSeconds(2);

    private final ProviderEventStore store;
    private final IntegrationMessageDispatcher dispatcher;
    private final List<RouteAdmission> routeAdmissions;
    private final DeliveryInbox inbox;
    private final IntegrationPoisonHandler failures;
    private final EventTraceContext traceContext;
    private final IntegrationMessageMeters meters;
    private final ConnectionActivityRecorder activityRecorder;
    private final ProviderEventNotifications notifications;
    private final ProviderEventConsumerProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** Set once the claimers stop: an interrupted handler then gets the shorter grace, and breaks no liveness. */
    private final AtomicBoolean stopping = new AtomicBoolean();

    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("provider-event-renewal").daemon(true).factory());
    private volatile @Nullable Instant lastHandledAt;
    private volatile @Nullable Instant lastFailedAt;

    public ProviderEventConsumer(
            ProviderEventStore store,
            IntegrationMessageDispatcher dispatcher,
            List<RouteAdmission> routeAdmissions,
            DeliveryInbox inbox,
            IntegrationPoisonHandler failures,
            EventTraceContext traceContext,
            IntegrationMessageMeters meters,
            ConnectionActivityRecorder activityRecorder,
            ProviderEventNotifications notifications,
            ProviderEventConsumerProperties properties,
            ApplicationEventPublisher events,
            Clock clock) {
        this.store = store;
        this.dispatcher = dispatcher;
        this.routeAdmissions = routeAdmissions;
        this.inbox = inbox;
        this.failures = failures;
        this.traceContext = traceContext;
        this.meters = meters;
        this.activityRecorder = activityRecorder;
        this.notifications = notifications;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    /** Called by the claimers when the server stops, before they interrupt the handlers that still run. */
    void stopping() {
        stopping.set(true);
    }

    @PreDestroy
    void close() {
        renewals.shutdownNow();
        handlers.shutdownNow();
    }

    @Nullable
    Instant lastHandledAt() {
        return lastHandledAt;
    }

    @Nullable
    Instant lastFailedAt() {
        return lastFailedAt;
    }

    /** Gives the leases of a stopped server to another; an event that used its attempts becomes a dead letter. */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    @WorkspaceAgnostic("The reaper takes back the expired leases of every workspace")
    public void reap() {
        int reaped = store.reapExpired(properties.poison().maxAttempts());
        if (reaped > 0) {
            log.warn("Took back expired provider event leases: count={}", reaped);
            notifications.queued();
        }
    }

    /** Deletes the handled events after the replay window and the dead letters after their retention. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @SchedulerLock(name = "provider-event-retention", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @WorkspaceAgnostic("Retention removes the ended events of every workspace")
    public void deleteEnded() {
        Instant now = clock.instant();
        try {
            int deleted =
                    store.deleteEnded(now.minus(properties.replayWindow()), now.minus(properties.deadRetention()));
            log.debug("Deleted ended provider events: count={}", deleted);
        } catch (RuntimeException failure) {
            meters.retentionFailed();
            log.atWarn()
                    .addKeyValue("event.name", "provider_event.retention_failed")
                    .addKeyValue("error.type", failure.getClass().getName())
                    .log("Provider event retention failed; the next pass deletes what this one left");
        }
    }

    /**
     * Claims one due event and handles it on this thread.
     *
     * @return whether an event was claimed
     */
    public boolean claimAndHandle() {
        Optional<ProviderEventStore.Claimed> claimed;
        try {
            claimed = store.claim(LEASE);
        } catch (RuntimeException failure) {
            // A claim that cannot start its transaction, such as on a full pool, fails as a TransactionException.
            // The claimer waits for the next poll; it must not end.
            log.warn(
                    "Provider event claim failed: error.type={}",
                    failure.getClass().getName());
            return false;
        }
        if (claimed.isEmpty()) return false;
        try {
            handle(claimed.get());
        } catch (RuntimeException failure) {
            // The outcome could not be recorded; the lease expires and the reaper returns the event.
            log.warn(
                    "Provider event outcome not recorded: eventId={}, error.type={}",
                    claimed.get().id(),
                    failure.getClass().getName());
        }
        return true;
    }

    void handle(ProviderEventStore.Claimed claimed) {
        ProviderEvent event = claimed.event();
        meters.received(event, claimed.attempt());
        ScheduledFuture<?> renewal = renewals.scheduleAtFixedRate(
                () -> renew(claimed), RENEWAL.toMillis(), RENEWAL.toMillis(), TimeUnit.MILLISECONDS);
        try {
            handleAttempt(claimed, System.nanoTime());
        } finally {
            renewal.cancel(false);
        }
    }

    private void handleAttempt(ProviderEventStore.Claimed claimed, long start) {
        ProviderEvent event = claimed.event();
        AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        AtomicReference<@Nullable IntegrationMessageHandler> handled = new AtomicReference<>();
        AtomicBoolean erasedByHandler = new AtomicBoolean();
        BoundedRun run = new BoundedRun(() -> traceContext.process(event, () -> {
            try {
                handled.set(dispatch(claimed, erasedByHandler));
            } catch (Throwable thrown) {
                if (thrown instanceof VirtualMachineError && !(thrown instanceof StackOverflowError)) throw thrown;
                failure.set(thrown);
            }
        }));
        try {
            run.await(properties.handlerTimeout());
        } catch (TimeoutException timedOut) {
            boolean stopped = run.stop();
            timedOut(claimed, stopped);
            // A handler that would not stop keeps its lease, so no server handles the event beside the stuck thread.
            if (!stopped) run.awaitEnd();
            if (dropIfFenced(claimed, start)) return;
            meters.handled(event, IntegrationMessageMeters.Outcome.TIMED_OUT, System.nanoTime() - start);
            lastFailedAt = clock.instant();
            failures.handleFailure(claimed, new HandlerTimeoutException(properties.handlerTimeout()));
            return;
        } catch (InterruptedException stopping) {
            // The server stops: the handler stops with it, and the event goes back without spending an attempt.
            if (!run.stop()) {
                breakLiveness();
                run.awaitEnd();
            }
            if (!store.release(claimed.id(), claimed.leaseEpoch())) dropIfFenced(claimed, start);
            Thread.currentThread().interrupt();
            return;
        } catch (ExecutionException crashed) {
            rethrowError(crashed);
            failure.set(crashed);
        }
        Throwable thrown = failure.get();
        if (thrown != null) {
            failed(claimed, thrown, start);
            return;
        }
        if (!erasedByHandler.get() && !store.complete(claimed.id(), claimed.leaseEpoch())) {
            dropIfFenced(claimed, start);
            return;
        }
        meters.handled(event, IntegrationMessageMeters.Outcome.COMPLETED, System.nanoTime() - start);
        lastHandledAt = clock.instant();
        IntegrationMessageHandler handler = handled.get();
        Long workspaceId = claimed.workspaceId();
        if (handler != null && workspaceId != null) {
            // Liveness watermark: the event arrived and was dispatched, also when its handler did nothing with it.
            EventTypeKey key = handler.key();
            activityRecorder.recordEventProcessed(workspaceId, key.kind(), key.eventType());
        }
    }

    @Nullable
    private IntegrationMessageHandler dispatch(ProviderEventStore.Claimed claimed, AtomicBoolean erasedByHandler) {
        ProviderEvent event = claimed.event();
        return new IntegrationDispatchScope(claimed.workspaceId()).call(() -> {
            Optional<IntegrationMessageHandler> handler = dispatcher.dispatch(event.subject());
            if (handler.isEmpty()) return null;
            IntegrationMessageHandler resolved = handler.get();
            Optional<RouteAdmission> admission = routeAdmissions.stream()
                    .filter(candidate -> candidate.owns(event.subject()))
                    .findFirst();
            boolean[] admitted = {true};
            erasedByHandler.set(inbox.dispatch(claimed.id(), claimed.leaseEpoch(), event.receivedAt(), () -> {
                if (admission.isEmpty()) resolved.onMessage(event);
                else admitted[0] = admission.get().admit(claimed.workspaceId(), event, () -> resolved.onMessage(event));
            }));
            if (!admitted[0]) {
                log.info(
                        "Skipped event: reason=routeNotAdmitted, scopeId={}, route={}",
                        claimed.workspaceId(),
                        ConsumerSubjectMath.routeLabel(event.subject()));
                return null;
            }
            return resolved;
        });
    }

    private void failed(ProviderEventStore.Claimed claimed, Throwable thrown, long start) {
        if (dropIfFenced(claimed, start)) return;
        ProviderEvent event = claimed.event();
        if (IntegrationPoisonHandler.diagnosticCause(thrown) instanceof ProviderEventBusyException busy) {
            log.atDebug()
                    .addKeyValue("event.name", "integration.message.retry")
                    .addKeyValue("integration.operation", "write-fence")
                    .addKeyValue("reason", busy.reason().tag)
                    .addKeyValue("integration.event.id", claimed.id())
                    .addKeyValue("integration.scope.id", claimed.workspaceId())
                    .log("Provider event waits for its write fence");
            meters.handled(event, IntegrationMessageMeters.Outcome.RETRIED, System.nanoTime() - start);
            failures.retry(claimed, false);
            return;
        }
        // Exception messages can contain raw provider payloads. Log only the failure type.
        // One failed delivery is a retry, at WARN with its delivery count; the dead letter is the verdict, at ERROR.
        var logged = log.atWarn()
                .addKeyValue("event.name", "integration.message.failed")
                .addKeyValue("integration.operation", "handle")
                .addKeyValue("integration.event.id", claimed.id())
                .addKeyValue("integration.route", ConsumerSubjectMath.routeLabel(event.subject()))
                .addKeyValue("integration.delivery", claimed.attempt())
                .addKeyValue("error.type", thrown.getClass().getName());
        if (claimed.workspaceId() != null) logged = logged.addKeyValue("integration.scope.id", claimed.workspaceId());
        Throwable diagnostic = IntegrationPoisonHandler.diagnosticCause(thrown);
        String state = IntegrationPoisonHandler.sqlState(thrown);
        if (state != null) logged = logged.addKeyValue("db.response.status_code", state);
        if (claimed.attempt() <= 1) {
            logged = logged.addKeyValue("error.root_type", diagnostic.getClass().getName())
                    .addKeyValue("error.stack_trace", IntegrationPoisonHandler.diagnosticFrames(diagnostic));
        }
        logged.log("Provider event handler failed");
        meters.handled(event, IntegrationMessageMeters.Outcome.FAILED, System.nanoTime() - start);
        lastFailedAt = clock.instant();
        failures.handleFailure(claimed, thrown);
    }

    private boolean dropIfFenced(ProviderEventStore.Claimed claimed, long start) {
        try {
            store.assertLease(claimed.id(), claimed.leaseEpoch());
            return false;
        } catch (ProviderEventFenceException fenced) {
            meters.dropped(claimed.event(), fenced.reason(), System.nanoTime() - start);
            log.atInfo()
                    .addKeyValue("event.name", "integration.message.dropped")
                    .addKeyValue("reason", fenced.reason().tag)
                    .addKeyValue("integration.scope.id", claimed.workspaceId())
                    .addKeyValue("integration.event.id", claimed.id())
                    .log("Provider event work dropped");
            return true;
        }
    }

    private void renew(ProviderEventStore.Claimed claimed) {
        try {
            if (!store.renew(claimed.id(), claimed.leaseEpoch(), LEASE)) {
                log.debug("Provider event renewal skipped a locked or replaced lease: eventId={}", claimed.id());
            }
        } catch (RuntimeException failure) {
            log.warn(
                    "Provider event lease renewal failed: eventId={}, error.type={}",
                    claimed.id(),
                    failure.getClass().getName());
        }
    }

    private void timedOut(ProviderEventStore.Claimed claimed, boolean stopped) {
        // A handler that stopped hands its event back for another delivery; one that did not stop breaks this replica.
        var logged = (stopped ? log.atWarn() : log.atError())
                .addKeyValue("event.name", "integration.message.timed_out")
                .addKeyValue(
                        "integration.route",
                        ConsumerSubjectMath.routeLabel(claimed.event().subject()))
                .addKeyValue("integration.delivery", claimed.attempt())
                .addKeyValue(
                        "integration.handler_timeout",
                        properties.handlerTimeout().toString())
                .addKeyValue("integration.handler_stopped", stopped);
        if (claimed.workspaceId() != null) logged = logged.addKeyValue("integration.scope.id", claimed.workspaceId());
        if (stopped) {
            logged.log("Handler timed out; its event returns to the inbox");
            return;
        }
        logged.log("Handler timed out and did not stop; this replica restarts and keeps the event until then");
        breakLiveness();
    }

    /** A handler's Error, such as an OutOfMemoryError, ends the claimer: it is no failure of the event. */
    @SuppressWarnings("PMD.PreserveStackTrace") // The wrapper only carries the handler thread's Error across.
    private static void rethrowError(ExecutionException crashed) {
        if (crashed.getCause() instanceof Error error) throw error;
    }

    /** Restarts this replica through Kubernetes, so a handler thread that would not stop dies with the Pod. */
    private void breakLiveness() {
        if (stopping.get()) return;
        AvailabilityChangeEvent.publish(events, this, LivenessState.BROKEN);
    }

    private Duration stopGrace() {
        // While the server stops, the release has to fit its shutdown wait.
        Duration grace = stopping.get() ? SHUTDOWN_STOP_GRACE : HANDLER_STOP_GRACE;
        Duration timeout = properties.handlerTimeout();
        return timeout.compareTo(grace) < 0 ? timeout : grace;
    }

    /** A handler on a thread of its own: the claimer waits for it at most a bound, and can stop it. */
    private final class BoundedRun {

        private final CountDownLatch ended = new CountDownLatch(1);
        private final AtomicBoolean started = new AtomicBoolean();
        private final Future<?> future;

        BoundedRun(Runnable body) {
            this.future = handlers.submit(() -> {
                started.set(true);
                try {
                    body.run();
                } finally {
                    ended.countDown();
                }
            });
        }

        void await(Duration bound) throws TimeoutException, InterruptedException, ExecutionException {
            future.get(bound.toMillis(), TimeUnit.MILLISECONDS);
        }

        /** Interrupts the handler and waits the stop grace for its thread to end; true when no thread runs it. */
        boolean stop() {
            future.cancel(true);
            boolean interrupted = Thread.interrupted();
            long deadline = System.nanoTime() + stopGrace().toNanos();
            try {
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    try {
                        if (ended.await(Math.max(0, remaining), TimeUnit.NANOSECONDS)) return true;
                        return !started.get();
                    } catch (InterruptedException again) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        /** Waits until the handler's thread ends, through every interrupt, and keeps the caller's interrupt. */
        void awaitEnd() {
            boolean interrupted = Thread.interrupted();
            try {
                while (true) {
                    try {
                        ended.await();
                        return;
                    } catch (InterruptedException again) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }
}
