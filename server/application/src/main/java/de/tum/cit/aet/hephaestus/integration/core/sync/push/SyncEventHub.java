package de.tum.cit.aet.hephaestus.integration.core.sync.push;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/** Manages workspace-scoped SSE subscribers and coalesced sync notifications. */
@Component
@ConditionalOnServerRole
public class SyncEventHub {

    private static final Logger log = LoggerFactory.getLogger(SyncEventHub.class);

    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();
    private static final int QUEUE_CAPACITY = 64;

    /** Limits credentialed cross-origin connection exhaustion; CORS does not prevent opening SSE streams. */
    private static final int MAX_EMITTERS_PER_WORKSPACE = 20;

    private static final String EVENT_NAME = "sync";
    private static final long HEARTBEAT_INTERVAL_MS = 20_000L;
    private static final Duration DEFAULT_COALESCE_WINDOW = Duration.ofSeconds(1);

    private final ObjectMapper objectMapper;
    private final Duration coalesceWindow;
    private final Supplier<SseEmitter> emitterFactory;
    private final Counter subscriptionsAccepted;
    private final Counter subscriptionsRejected;
    private final Counter subscriptionsFailed;
    private final Counter eventsDelivered;
    private final Counter eventsDropped;
    private final Counter eventsFailed;

    private final AtomicBoolean capWarned = new AtomicBoolean(false);

    private final Map<Long, Set<Subscriber>> subscribersByWorkspace = new ConcurrentHashMap<>();

    private final ExecutorService writerExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<CoalesceKey, SyncEventHint> pendingHints = new ConcurrentHashMap<>();
    private final Set<CoalesceKey> pendingFlushes = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService coalesceScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sync-event-coalesce");
        thread.setDaemon(true);
        return thread;
    });

    /** Selects the production constructor when the package-private test constructor is also present. */
    @Autowired
    public SyncEventHub(ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this(objectMapper, meterRegistry, DEFAULT_COALESCE_WINDOW, () -> new SseEmitter(EMITTER_TIMEOUT_MS));
    }

    SyncEventHub(
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            Duration coalesceWindow,
            Supplier<SseEmitter> emitterFactory) {
        this.objectMapper = objectMapper;
        this.coalesceWindow = coalesceWindow;
        this.emitterFactory = emitterFactory;
        this.subscriptionsAccepted =
                counter(meterRegistry, IntegrationCoreMetrics.INTEGRATION_SYNC_SSE_SUBSCRIPTIONS, "accepted");
        this.subscriptionsRejected =
                counter(meterRegistry, IntegrationCoreMetrics.INTEGRATION_SYNC_SSE_SUBSCRIPTIONS, "rejected");
        this.subscriptionsFailed =
                counter(meterRegistry, IntegrationCoreMetrics.INTEGRATION_SYNC_SSE_SUBSCRIPTIONS, "failed");
        this.eventsDelivered = counter(meterRegistry, IntegrationCoreMetrics.INTEGRATION_SYNC_SSE_EVENTS, "delivered");
        this.eventsDropped = counter(meterRegistry, IntegrationCoreMetrics.INTEGRATION_SYNC_SSE_EVENTS, "dropped");
        this.eventsFailed = counter(meterRegistry, IntegrationCoreMetrics.INTEGRATION_SYNC_SSE_EVENTS, "error");
        Gauge.builder(
                        IntegrationCoreMetrics.INTEGRATION_SYNC_SSE_SUBSCRIBERS,
                        subscribersByWorkspace,
                        subscribers -> subscribers.values().stream()
                                .mapToInt(Set::size)
                                .sum())
                .description("Currently active sync-observability SSE subscribers on this server replica")
                .register(meterRegistry);
    }

    public SseEmitter subscribe(long workspaceId) {
        SseEmitter emitter = emitterFactory.get();
        Subscriber subscriber = new Subscriber(workspaceId, emitter);

        emitter.onCompletion(() -> deregister(subscriber));
        emitter.onTimeout(() -> {
            deregister(subscriber);
            safeComplete(emitter);
        });
        emitter.onError(throwable -> deregister(subscriber));

        synchronized (subscribersByWorkspace) {
            Set<Subscriber> subscribers =
                    subscribersByWorkspace.computeIfAbsent(workspaceId, key -> ConcurrentHashMap.newKeySet());
            if (subscribers.size() >= MAX_EMITTERS_PER_WORKSPACE) {
                if (capWarned.compareAndSet(false, true)) {
                    log.warn(
                            "Sync SSE emitter cap ({}) reached for a workspace; rejecting new stream",
                            MAX_EMITTERS_PER_WORKSPACE);
                }
                subscriptionsRejected.increment();
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many live sync streams");
            }
            subscribers.add(subscriber);
        }

        subscriber.writeLock.lock();
        try {
            emitter.send(SseEmitter.event().comment("connected"));
            subscriptionsAccepted.increment();
        } catch (Exception e) {
            log.debug("Sync SSE initial flush failed; not registering: {}", e.toString());
            subscriptionsFailed.increment();
            deregister(subscriber);
            safeComplete(emitter);
        } finally {
            subscriber.writeLock.unlock();
        }
        return emitter;
    }

    /** Coalesces the latest hint per workspace, connection, and scope. */
    public void publish(long workspaceId, SyncEventHint hint) {
        CoalesceKey key = new CoalesceKey(workspaceId, hint.connectionId(), hint.scope());
        pendingHints.put(key, hint);
        if (pendingFlushes.add(key)) {
            scheduleFlush(key);
        }
    }

    private void scheduleFlush(CoalesceKey key) {
        try {
            coalesceScheduler.schedule(() -> flush(key), coalesceWindow.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            flush(key);
        }
    }

    private void flush(CoalesceKey key) {
        pendingFlushes.remove(key);
        SyncEventHint hint = pendingHints.remove(key);
        if (hint != null) {
            fanOut(key.workspaceId(), hint);
        }
    }

    private void fanOut(long workspaceId, SyncEventHint hint) {
        Set<Subscriber> subscribers = subscribersByWorkspace.get(workspaceId);
        if (subscribers == null || subscribers.isEmpty()) {
            return;
        }
        for (Subscriber subscriber : subscribers) {
            enqueueHint(subscriber, hint);
        }
    }

    private void enqueueHint(Subscriber subscriber, SyncEventHint hint) {
        if (subscriber.closed.get()) {
            return;
        }
        synchronized (subscriber.queue) {
            if (subscriber.queue.size() >= QUEUE_CAPACITY) {
                subscriber.queue.pollFirst(); // drop-oldest
                eventsDropped.increment();
            }
            subscriber.queue.addLast(hint);
        }
        scheduleDrain(subscriber);
    }

    private void scheduleDrain(Subscriber subscriber) {
        if (subscriber.draining.compareAndSet(false, true)) {
            try {
                writerExecutor.execute(() -> drain(subscriber));
            } catch (RejectedExecutionException e) {
                subscriber.draining.set(false);
            }
        }
    }

    private void drain(Subscriber subscriber) {
        subscriber.writeLock.lock();
        try {
            while (true) {
                SyncEventHint hint;
                synchronized (subscriber.queue) {
                    hint = subscriber.queue.pollFirst();
                }
                if (hint == null) {
                    break;
                }
                if (!sendHint(subscriber, hint)) {
                    return; // failure already deregistered the subscriber
                }
            }
        } finally {
            subscriber.writeLock.unlock();
            subscriber.draining.set(false);
            boolean more;
            synchronized (subscriber.queue) {
                more = !subscriber.queue.isEmpty();
            }
            if (more && !subscriber.closed.get()) {
                scheduleDrain(subscriber);
            }
        }
    }

    private boolean sendHint(Subscriber subscriber, SyncEventHint hint) {
        try {
            String json = objectMapper.writeValueAsString(hint);
            subscriber.emitter.send(SseEmitter.event().name(EVENT_NAME).data(json));
            eventsDelivered.increment();
            return true;
        } catch (Exception e) {
            log.debug("Sync SSE send failed; deregistering: {}", e.toString());
            eventsFailed.increment();
            deregister(subscriber);
            safeComplete(subscriber.emitter);
            return false;
        }
    }

    /** Keeps quiet streams alive through the proxy's idle timeout. */
    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MS, initialDelay = HEARTBEAT_INTERVAL_MS)
    @WorkspaceAgnostic("Writes transport heartbeats to already-authorized in-memory subscribers")
    void sendHeartbeats() {
        for (Set<Subscriber> subscribers : subscribersByWorkspace.values()) {
            for (Subscriber subscriber : subscribers) {
                if (subscriber.closed.get()) {
                    continue;
                }
                if (!subscriber.heartbeatPending.compareAndSet(false, true)) {
                    continue;
                }
                try {
                    writerExecutor.execute(() -> sendPing(subscriber));
                } catch (RejectedExecutionException ignored) {
                    subscriber.heartbeatPending.set(false);
                }
            }
        }
    }

    private void sendPing(Subscriber subscriber) {
        if (subscriber.closed.get()) {
            return;
        }
        subscriber.writeLock.lock();
        try {
            if (subscriber.closed.get()) {
                return;
            }
            subscriber.emitter.send(SseEmitter.event().comment("ping"));
        } catch (Exception e) {
            log.debug("Sync SSE heartbeat failed; deregistering: {}", e.toString());
            deregister(subscriber);
            safeComplete(subscriber.emitter);
        } finally {
            subscriber.writeLock.unlock();
            subscriber.heartbeatPending.set(false);
        }
    }

    private void deregister(Subscriber subscriber) {
        if (!subscriber.closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (subscribersByWorkspace) {
            Set<Subscriber> subscribers = subscribersByWorkspace.get(subscriber.workspaceId);
            if (subscribers != null) {
                subscribers.remove(subscriber);
                if (subscribers.isEmpty()) {
                    subscribersByWorkspace.remove(subscriber.workspaceId, subscribers);
                }
            }
        }
    }

    private static void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
        }
    }

    @PreDestroy
    void shutdown() {
        for (Set<Subscriber> subscribers : new ArrayList<>(subscribersByWorkspace.values())) {
            for (Subscriber subscriber : new ArrayList<>(subscribers)) {
                deregister(subscriber);
                safeComplete(subscriber.emitter);
            }
        }
        coalesceScheduler.shutdownNow();
        writerExecutor.shutdown();
        try {
            if (!writerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                writerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            writerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    int subscriberCount(long workspaceId) {
        Set<Subscriber> subscribers = subscribersByWorkspace.get(workspaceId);
        return subscribers == null ? 0 : subscribers.size();
    }

    private static Counter counter(MeterRegistry meterRegistry, String name, String outcome) {
        return Counter.builder(name)
                .description("Sync-observability SSE transport outcomes")
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    private record CoalesceKey(long workspaceId, Long connectionId, String scope) {}

    private static final class Subscriber {

        final long workspaceId;
        final SseEmitter emitter;
        final Deque<SyncEventHint> queue = new ArrayDeque<>();
        final ReentrantLock writeLock = new ReentrantLock();
        final AtomicBoolean draining = new AtomicBoolean(false);
        final AtomicBoolean heartbeatPending = new AtomicBoolean(false);
        final AtomicBoolean closed = new AtomicBoolean(false);

        Subscriber(long workspaceId, SseEmitter emitter) {
            this.workspaceId = workspaceId;
            this.emitter = emitter;
        }
    }
}
