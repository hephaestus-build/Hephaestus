package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.core.event.WorkspacesInitializedEvent;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.NatsSubscriptionInfo;
import de.tum.cit.aet.hephaestus.integration.core.spi.NatsSubscriptionProvider.StreamSubscription;
import de.tum.cit.aet.hephaestus.integration.core.sync.activity.ConnectionActivityRecorder;
import io.nats.client.Connection;
import io.nats.client.ConsumerContext;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamOptions;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.StreamContext;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.DeliverPolicy;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unified JetStream consumer fleet for every integration kind and the sole consumer-side entry point:
 * per-scope consumers, the installation-wide consumer, lifecycle, reconnect, and dispatch. Per-scope
 * lifecycle is gated by the in-flight {@code pendingScopeSetup} set so two callers can't race to create
 * the same JetStream consumer; connection creation is double-checked-locked.
 */
@Order(1)
@Service
@ConditionalOnProperty(name = RuntimeRole.SERVER_PROPERTY, havingValue = "true", matchIfMissing = true)
public class IntegrationNatsConsumer {

    private static final Logger log = LoggerFactory.getLogger(IntegrationNatsConsumer.class);

    /**
     * Kind whose stream backs the installation-wide consumer. Today only GitHub
     * publishes installation events; we go through {@link ConsumerSubjectMath#streamNameFor}
     * so the field name and consumer wiring stay vendor-neutral while the singular
     * supported value remains explicit. New installation-capable vendors will need a
     * sibling consumer (and likely a different field), not an in-place rename.
     */
    private static final IntegrationKind INSTALLATION_AWARE_KIND = IntegrationKind.GITHUB;

    /** NATS client connection timeout — keep short; reconnect handles long outages. */
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(10);

    /** How long a caller that needs the scope to itself waits for a setup task in flight to finish. */
    private static final Duration SCOPE_SETUP_WAIT = Duration.ofSeconds(10);

    /** Maximum reconnect attempts before we throw {@link NatsConnectionException}. */
    private static final int MAX_RECONNECT_ATTEMPTS = 6;

    /** Base for the reconnect exponential backoff. */
    private static final long RECONNECT_BASE_MS = 1_000L;

    /** Hard cap on the reconnect exponential backoff. */
    private static final long RECONNECT_MAX_MS = 30_000L;

    /**
     * Attempts a scope reconcile gets before it stops re-arming. A scope binds several streams, and one can be
     * legitimately absent for a while (the webhook pod creates the {@code outline} stream on its own boot, which
     * may land after ours), so the reconcile self-heals on the connect-retry backoff rather than needing a restart.
     */
    private static final int MAX_SCOPE_RECONCILE_ATTEMPTS = 6;

    private final Object connectionLock = new Object();
    private final Object scopeLifecycleMonitor = new Object();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /**
     * JetStream consumers per scope id. A scope may bind more than one stream, so each maps to a list of
     * {@link ScopeConsumer}s — one per (scope, stream).
     */
    private final Map<Long, List<ScopeConsumer>> scopeConsumers = new ConcurrentHashMap<>();

    /**
     * Atomic placeholder set guarding against duplicate setup. Holding the scope ID
     * here means a setup task is in-flight on a virtual thread.
     */
    private final Set<Long> pendingScopeSetup = ConcurrentHashMap.newKeySet();

    /**
     * Scopes that were asked to reconcile while a setup task held them in {@link #pendingScopeSetup}. That task
     * reconciles once more before it releases the scope, because its own read of the subscription info may
     * predate the change behind the request.
     */
    private final Set<Long> reconcileRequestedScopes = ConcurrentHashMap.newKeySet();

    private final Set<Long> stoppingScopes = ConcurrentHashMap.newKeySet();

    /**
     * Consecutive failed reconcile attempts per scope, for the self-healing retry backoff. Cleared on the
     * first successful reconcile (and when the scope is stopped), so a later transient failure starts fresh.
     */
    private final Map<Long, Integer> scopeReconcileAttempts = new ConcurrentHashMap<>();

    /** Installation consumer — single-instance, mutable across restarts. */
    private volatile @Nullable ScopeConsumer installationConsumer;

    /** Virtual-thread executor for scope setup and installation kicks. */
    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** Delay timer for the scope-reconcile retry. Single daemon thread — it only ever submits to the executor. */
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "integration-consumer-retry");
        thread.setDaemon(true);
        return thread;
    });

    private @Nullable Connection natsConnection;

    private final NatsConnectionProperties connectionProperties;
    private final NatsConsumerProperties consumerProperties;
    private final NatsSubscriptionProvider subscriptionProvider;
    private final IntegrationMessageDispatcher dispatcher;
    private final IntegrationPoisonHandler poisonHandler;
    private final IntegrationConsumerStats stats;
    private final ConnectionActivityRecorder activityRecorder;

    public IntegrationNatsConsumer(
            NatsConnectionProperties connectionProperties,
            NatsConsumerProperties consumerProperties,
            NatsSubscriptionProvider subscriptionProvider,
            IntegrationMessageDispatcher dispatcher,
            IntegrationPoisonHandler poisonHandler,
            IntegrationConsumerStats stats,
            ConnectionActivityRecorder activityRecorder) {
        this.connectionProperties = connectionProperties;
        this.consumerProperties = consumerProperties;
        this.subscriptionProvider = subscriptionProvider;
        this.dispatcher = dispatcher;
        this.poisonHandler = poisonHandler;
        this.stats = stats;
        this.activityRecorder = activityRecorder;
    }

    // Lifecycle

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!connectionProperties.enabled()) {
            log.info("Integration NATS consumer disabled (hephaestus.sync.nats.enabled=false)");
            stats.setNatsConnectionStatus("DISABLED");
            return;
        }
        validateConfiguration();
        try {
            connectWithRetry();
        } catch (NatsConnectionException e) {
            // The boot listener fired in a thread Spring will swallow; if we rethrow,
            // the application keeps running with no consumer and only a generic
            // ApplicationListener exception in the log. Mark the health indicator
            // explicitly so /actuator/health surfaces the failure and the operator
            // gets a real signal instead of a silent dead consumer thread.
            stats.setNatsConnectionStatus("FAILED_BOOT");
            log.error(
                    "Integration NATS consumer failed to connect after {} attempts; health indicator will report DOWN",
                    MAX_RECONNECT_ATTEMPTS,
                    e);
            return;
        }
        log.info(
                "Integration NATS consumer ready: server={}, awaiting WorkspacesInitializedEvent for installation consumer",
                connectionProperties.server());
    }

    @EventListener(WorkspacesInitializedEvent.class)
    public void onWorkspacesInitialized(WorkspacesInitializedEvent event) {
        if (!connectionProperties.enabled() || shuttingDown.get()) {
            return;
        }
        log.info("Workspaces initialized; starting installation consumer: workspaceCount={}", event.workspaceCount());
        virtualThreadExecutor.submit(() -> {
            try {
                ensureNatsConnectionEstablished();
                setupInstallationConsumer();
            } catch (Exception e) {
                log.error("Failed to start installation consumer", e);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        if (!connectionProperties.enabled()) {
            return;
        }
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        log.info("Shutting down integration NATS consumer fleet");

        for (var entry : scopeConsumers.entrySet()) {
            for (ScopeConsumer consumer : entry.getValue()) {
                try {
                    consumer.stop();
                } catch (Exception e) {
                    log.debug("Failed to stop scope consumer: scopeId={}", entry.getKey(), e);
                }
            }
        }
        scopeConsumers.clear();
        scopeReconcileAttempts.clear();
        stats.setActiveScopeConsumerCount(0);
        retryScheduler.shutdownNow();

        ScopeConsumer installation = installationConsumer;
        if (installation != null) {
            try {
                installation.stop();
            } catch (Exception e) {
                log.debug("Failed to stop installation consumer", e);
            }
            installationConsumer = null;
            stats.setInstallationConsumerActive(false);
        }

        virtualThreadExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            virtualThreadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (natsConnection != null) {
            try {
                natsConnection.close();
            } catch (Exception e) {
                log.debug("Failed to close NATS connection", e);
            }
            natsConnection = null;
        }
        stats.setNatsConnectionStatus("CLOSED");
        log.info("Integration NATS consumer fleet shut down");
    }

    // Public surface (called by workspace lifecycle services)

    /**
     * Start (or no-op if already running) a JetStream consumer for the given scope.
     * Returns immediately; the actual setup happens on a virtual thread. Safe to
     * call concurrently for the same scope — duplicate setup is suppressed.
     */
    public void startConsumingScope(Long scopeId) {
        if (!connectionProperties.enabled() || shuttingDown.get() || scopeId == null) {
            return;
        }
        if (scopeConsumers.containsKey(scopeId)) {
            log.debug("Scope consumer already exists: scopeId={}", scopeId);
            return;
        }
        if (!beginScopeSetup(scopeId)) {
            log.debug("Scope consumer setup already in progress: scopeId={}", scopeId);
            return;
        }
        submitScopeSetup(scopeId);
    }

    /**
     * Reconcile the filter subjects of a running or starting scope. No-op if the scope has neither
     * consumers nor a setup in flight. When a scope gains a stream (e.g. Outline is connected after boot)
     * the reconcile creates the additional consumer; when it loses one the reconcile tears that consumer
     * down.
     *
     * <p>Shares {@link #pendingScopeSetup} with {@link #startConsumingScope(Long)}: two concurrent
     * reconciles of one scope would race the read-modify-write on {@link #scopeConsumers} and the loser's
     * freshly-created consumer would be overwritten without {@code stop()} — leaking its virtual thread
     * and an orphaned server-side durable. A request that arrives while a setup is in flight is handed to
     * that setup, which reconciles once more before it releases the scope.
     */
    public void updateScopeConsumer(Long scopeId) {
        if (!connectionProperties.enabled() || shuttingDown.get() || scopeId == null) {
            return;
        }
        synchronized (scopeLifecycleMonitor) {
            if (!scopeConsumers.containsKey(scopeId) && !pendingScopeSetup.contains(scopeId)) {
                log.debug("Skipped consumer update: reason=notRunning, scopeId={}", scopeId);
                return;
            }
            if (!beginScopeSetup(scopeId)) {
                log.debug("Scope reconcile already in progress: scopeId={}", scopeId);
                return;
            }
        }
        submitScopeSetup(scopeId);
    }

    /**
     * Creates or updates the scope's consumers on the calling thread and returns once JetStream holds the
     * scope's current subscription on {@code streamName}, so the caller can open a delivery path whose
     * events must not be skipped. Waits for a setup task already in flight instead of racing it, and
     * reconciles again for every change requested while it runs, so no requested subject is left to a
     * later pass.
     *
     * @throws IOException when a stream's consumer cannot be created, updated or read back; the usual retry
     *     is armed for creation and update failures
     * @throws IllegalStateException when the fleet is not running, the scope is stopping or stays busy, or
     *     JetStream does not hold the scope's subscription on {@code streamName}
     */
    public void establishScopeConsumer(Long scopeId, String streamName) throws IOException {
        if (!connectionProperties.enabled() || shuttingDown.get()) {
            throw new IllegalStateException("Integration NATS consumer is not running");
        }
        claimScopeSetup(scopeId);
        boolean released = false;
        try {
            do {
                ensureNatsConnectionEstablished();
                reconcileScope(scopeId);
                requireInstalledSubscription(scopeId, streamName);
                released = endScopeSetup(scopeId);
            } while (!released);
        } finally {
            if (!released && !endScopeSetup(scopeId)) {
                submitScopeSetup(scopeId);
            }
        }
    }

    /**
     * Requires a live local subscription and reads the stream's durable back from JetStream, requiring the
     * filter this pass installed.
     */
    private void requireInstalledSubscription(Long scopeId, String streamName) throws IOException {
        ScopeConsumer consumer = scopeConsumers.getOrDefault(scopeId, List.of()).stream()
                .filter(candidate -> candidate.streamName().equals(streamName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Scope has no consumer on stream " + streamName + ": scopeId=" + scopeId));
        if (!consumer.isAttached()) {
            throw new IllegalStateException(
                    "Scope consumer is not subscribed: scopeId=" + scopeId + ", stream=" + streamName);
        }
        Set<String> installed;
        try {
            installed = consumer.installedSubjects();
        } catch (JetStreamApiException e) {
            throw new IOException("Failed to read scope consumer for scopeId=" + scopeId + " stream=" + streamName, e);
        }
        if (!installed.equals(Set.of(consumer.currentSubjects()))) {
            throw new IllegalStateException("Scope consumer filter differs from its subscription: scopeId=" + scopeId
                    + ", stream=" + streamName);
        }
    }

    /** Stops every consumer for the scope and waits until no handler or setup task is running. */
    public void stopConsumingScope(Long scopeId) {
        stopConsumingScope(scopeId, false);
    }

    /**
     * Stops a terminally-deleted scope and rejects queued restarts. A rolled-back purge releases the
     * fence; a committed purge keeps it because PURGED workspaces cannot resume.
     */
    public void stopConsumingScopeForPurge(Long scopeId) {
        stopConsumingScope(scopeId, true);
    }

    private void stopConsumingScope(Long scopeId, boolean retainFenceOnCommit) {
        if (scopeId == null) {
            return;
        }
        synchronized (scopeLifecycleMonitor) {
            stoppingScopes.add(scopeId);
            long deadline = System.nanoTime() + SCOPE_SETUP_WAIT.toNanos();
            while (pendingScopeSetup.contains(scopeId)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    stoppingScopes.remove(scopeId);
                    throw new IllegalStateException("Timed out waiting for scope consumer setup: scopeId=" + scopeId);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(scopeLifecycleMonitor, remaining);
                } catch (InterruptedException e) {
                    stoppingScopes.remove(scopeId);
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while stopping scope consumer: scopeId=" + scopeId, e);
                }
            }
        }
        scopeReconcileAttempts.remove(scopeId);
        List<ScopeConsumer> consumers = scopeConsumers.get(scopeId);
        boolean stopped = false;
        try {
            if (consumers == null || consumers.isEmpty()) {
                log.debug("No scope consumer to stop: scopeId={}", scopeId);
                stopped = true;
                return;
            }
            for (ScopeConsumer consumer : consumers) {
                consumer.stop();
                cleanupConsumer(consumer.streamName(), consumer.consumerName());
            }
            scopeConsumers.remove(scopeId, consumers);
            stats.setActiveScopeConsumerCount(totalConsumerCount());
            log.info("Stopped scope consumers: scopeId={}, count={}", scopeId, consumers.size());
            stopped = true;
        } finally {
            if (!retainFenceOnCommit || !stopped) {
                stoppingScopes.remove(scopeId);
            } else if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != TransactionSynchronization.STATUS_COMMITTED) {
                            stoppingScopes.remove(scopeId);
                            startConsumingScope(scopeId);
                        }
                    }
                });
            }
        }
    }

    // JetStream setup

    /**
     * Bring the scope's set of JetStream consumers in line with its current subscription info:
     * existing consumers whose stream is still desired are updated in place; new streams create
     * a fresh consumer; streams no longer desired are torn down.
     *
     * <h3>Partial failure</h3>
     * A scope binds several streams, so this loop can fail halfway — typically because one stream does not
     * exist yet (the {@code outline} stream is created by the webhook pod, which may boot after us). Every
     * consumer this pass already {@code start()}ed is therefore <b>committed to {@link #scopeConsumers}
     * before the failure propagates</b>. Dropping them on the floor would leave a live, untracked consumer:
     * never stopped on shutdown, invisible to {@link #updateScopeConsumer(Long)} (which would no-op as "not
     * running"), and duplicated on its own durable by the next {@link #startConsumingScope(Long)}.
     *
     * <p>The failure also re-arms a backed-off retry, so a transient one heals itself instead of requiring
     * an app restart.
     */
    void reconcileScope(Long scopeId) throws IOException {
        Optional<NatsSubscriptionInfo> infoOpt = subscriptionProvider.getSubscriptionInfo(scopeId);
        if (infoOpt.isEmpty()) {
            log.warn("No subscription info available: scopeId={}", scopeId);
            return;
        }
        Map<String, StreamSubscription> desired = new LinkedHashMap<>();
        for (StreamSubscription sub : infoOpt.get().streamSubscriptions()) {
            if (!sub.subjects().isEmpty()) {
                desired.put(sub.streamName(), sub);
            }
        }

        List<ScopeConsumer> current = scopeConsumers.getOrDefault(scopeId, List.of());
        List<ScopeConsumer> next = new ArrayList<>();
        Set<String> kept = new HashSet<>();
        try {
            for (ScopeConsumer existing : current) {
                StreamSubscription want = desired.get(existing.streamName());
                if (want == null) {
                    stopAndCleanup(existing);
                    continue;
                }
                try {
                    existing.updateSubjects(want.subjects().toArray(String[]::new));
                } catch (JetStreamApiException | IOException e) {
                    // The consumer is still running with its previous subjects — keep it TRACKED (an
                    // untracked live consumer is exactly the leak this method exists to prevent) and let
                    // the retry re-apply the new subjects.
                    next.add(existing);
                    kept.add(existing.streamName());
                    throw new IOException("Failed to update scope consumer for scopeId=" + scopeId, e);
                }
                next.add(existing);
                kept.add(existing.streamName());
            }
            for (StreamSubscription want : desired.values()) {
                if (!kept.contains(want.streamName())) {
                    next.add(createScopeConsumer(scopeId, want));
                }
            }
        } catch (IOException | RuntimeException e) {
            // Commit whatever is already running (see javadoc), then re-arm and rethrow.
            commitScopeConsumers(scopeId, next);
            log.error(
                    "Partial scope consumer reconcile: scopeId={}, running={}, desired={} — retrying",
                    scopeId,
                    next.size(),
                    desired.size(),
                    e);
            scheduleScopeReconcileRetry(scopeId);
            throw e;
        }

        commitScopeConsumers(scopeId, next);
        scopeReconcileAttempts.remove(scopeId);
    }

    /**
     * Publish this pass's consumer list for the scope. Every {@code start()}ed consumer MUST land here —
     * {@link #scopeConsumers} is the only handle by which a consumer can later be stopped or updated.
     */
    private void commitScopeConsumers(Long scopeId, List<ScopeConsumer> consumers) {
        if (stoppingScopes.contains(scopeId)) {
            consumers.forEach(this::stopAndCleanup);
            return;
        }
        if (consumers.isEmpty()) {
            scopeConsumers.remove(scopeId);
            log.info("Skipped scope consumer setup (no subjects): scopeId={}", scopeId);
        } else {
            scopeConsumers.put(scopeId, List.copyOf(consumers));
        }
        stats.setActiveScopeConsumerCount(totalConsumerCount());
    }

    /**
     * Re-arm a failed reconcile on the connect-retry backoff curve. Gives up (loudly) after
     * {@link #MAX_SCOPE_RECONCILE_ATTEMPTS} — an indefinite retry against a permanently-broken stream would
     * just be a log firehose; the successfully-created consumers from the partial pass keep working either way.
     */
    private void scheduleScopeReconcileRetry(Long scopeId) {
        if (shuttingDown.get() || !connectionProperties.enabled() || stoppingScopes.contains(scopeId)) {
            return;
        }
        int attempt = scopeReconcileAttempts.merge(scopeId, 1, Integer::sum);
        if (attempt >= MAX_SCOPE_RECONCILE_ATTEMPTS) {
            log.error(
                    "Giving up on scope consumer reconcile after {} attempts: scopeId={}; "
                            + "the scope is consuming only the streams that could be bound",
                    attempt,
                    scopeId);
            scopeReconcileAttempts.remove(scopeId);
            return;
        }
        long delayMs = reconnectBackoffMs(attempt - 1);
        log.warn("Retrying scope consumer reconcile: scopeId={}, attempt={}, delayMs={}", scopeId, attempt, delayMs);
        submitDelayed(() -> retryReconcileScope(scopeId), delayMs);
    }

    /**
     * Retry pass. Shares {@link #pendingScopeSetup} with the other reconcile entry points: if one is already
     * in flight for this scope it takes over the request. Unlike {@link #updateScopeConsumer(Long)} this does
     * NOT require the scope to already be in {@link #scopeConsumers}: a reconcile that failed on its very
     * first stream has no entry there and would otherwise never come back.
     */
    private void retryReconcileScope(Long scopeId) {
        if (shuttingDown.get() || !connectionProperties.enabled()) {
            return;
        }
        if (!beginScopeSetup(scopeId)) {
            log.debug("Scope reconcile retry handed to the reconcile in progress: scopeId={}", scopeId);
            return;
        }
        submitScopeSetup(scopeId);
    }

    /** Runs the scope's reconcile on a virtual thread, repeating it while requests arrive during a pass. */
    private void submitScopeSetup(Long scopeId) {
        try {
            virtualThreadExecutor.submit(() -> {
                do {
                    try {
                        ensureNatsConnectionEstablished();
                        reconcileScope(scopeId);
                    } catch (Exception e) {
                        log.error("Failed to reconcile scope consumer: scopeId={}", scopeId, e);
                    }
                } while (!endScopeSetup(scopeId));
            });
        } catch (RejectedExecutionException e) {
            endScopeSetup(scopeId);
        }
    }

    /**
     * Claims the scope for one setup task. While another task holds it the request is recorded instead,
     * and that task reconciles again before releasing the scope.
     */
    private boolean beginScopeSetup(Long scopeId) {
        synchronized (scopeLifecycleMonitor) {
            if (stoppingScopes.contains(scopeId)) {
                return false;
            }
            if (pendingScopeSetup.add(scopeId)) {
                return true;
            }
            reconcileRequestedScopes.add(scopeId);
            return false;
        }
    }

    /** Claims the scope for the calling thread, waiting for a setup task in flight to release it. */
    private void claimScopeSetup(Long scopeId) {
        synchronized (scopeLifecycleMonitor) {
            long deadline = System.nanoTime() + SCOPE_SETUP_WAIT.toNanos();
            while (true) {
                if (stoppingScopes.contains(scopeId)) {
                    throw new IllegalStateException("Scope consumer is stopping: scopeId=" + scopeId);
                }
                if (pendingScopeSetup.add(scopeId)) {
                    return;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new IllegalStateException("Timed out waiting for scope consumer setup: scopeId=" + scopeId);
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(scopeLifecycleMonitor, remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while waiting for scope consumer setup: scopeId=" + scopeId, e);
                }
            }
        }
    }

    /** Releases the scope, or returns {@code false} when a request arrived during the pass and must be run. */
    private boolean endScopeSetup(Long scopeId) {
        synchronized (scopeLifecycleMonitor) {
            if (reconcileRequestedScopes.remove(scopeId) && !stoppingScopes.contains(scopeId) && !shuttingDown.get()) {
                return false;
            }
            pendingScopeSetup.remove(scopeId);
            scopeLifecycleMonitor.notifyAll();
            return true;
        }
    }

    private void submitDelayed(Runnable task, long delayMs) {
        try {
            retryScheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            log.debug("Scope reconcile retry not scheduled: consumer fleet is shutting down");
        }
    }

    /** Package-private + overridable so the reconcile's partial-failure path is unit-testable without a broker. */
    ScopeConsumer createScopeConsumer(Long scopeId, StreamSubscription subscription) throws IOException {
        String streamName = subscription.streamName();
        String[] subjects = subscription.subjects().toArray(String[]::new);
        // Stream-suffixed durable so a scope's SCM and Outline consumers never share a durable name.
        String consumerName = ConsumerSubjectMath.scopeConsumerName(durableBaseName(), scopeId) + "-" + streamName;
        try {
            JetStreamOptions jsOptions = JetStreamOptions.builder()
                    .requestTimeout(connectionProperties.consumer().requestTimeout())
                    .build();
            StreamContext streamContext =
                    Objects.requireNonNull(natsConnection).getStreamContext(streamName, jsOptions);
            ConsumerContext consumerContext = createOrUpdateConsumer(streamContext, consumerName, subjects);

            ScopeConsumer scope = new ScopeConsumer(
                    scopeId,
                    consumerName,
                    streamName,
                    consumerContext,
                    streamContext,
                    subjects,
                    msg -> handleMessage(scopeId, msg));
            scope.start();
            log.info(
                    "Started scope consumer: scopeId={}, stream={}, subjectCount={}",
                    scopeId,
                    streamName,
                    subjects.length);
            return scope;
        } catch (JetStreamApiException e) {
            throw new IOException(
                    "Failed to set up scope consumer for scopeId=" + scopeId + " stream=" + streamName, e);
        }
    }

    private void stopAndCleanup(ScopeConsumer consumer) {
        try {
            consumer.stop();
            cleanupConsumer(consumer.streamName(), consumer.consumerName());
        } catch (Exception e) {
            log.error("Failed to stop scope consumer: consumerName={}", consumer.consumerName(), e);
        }
    }

    private int totalConsumerCount() {
        int total = 0;
        for (List<ScopeConsumer> list : scopeConsumers.values()) {
            total += list.size();
        }
        return total;
    }

    private void setupInstallationConsumer() throws IOException {
        String streamName = ConsumerSubjectMath.streamNameFor(INSTALLATION_AWARE_KIND)
                .orElseThrow(() -> new IllegalStateException(
                        "No NATS stream resolved for installation-aware kind=" + INSTALLATION_AWARE_KIND));
        String[] subjects = new String[] {
            ConsumerSubjectMath.installationAwareSubjectFilter(INSTALLATION_AWARE_KIND),
        };
        String consumerName = ConsumerSubjectMath.installationConsumerName(durableBaseName());

        try {
            JetStreamOptions jsOptions = JetStreamOptions.builder()
                    .requestTimeout(connectionProperties.consumer().requestTimeout())
                    .build();
            StreamContext streamContext =
                    Objects.requireNonNull(natsConnection).getStreamContext(streamName, jsOptions);
            ConsumerContext consumerContext = createOrUpdateConsumer(streamContext, consumerName, subjects);

            ScopeConsumer installation = new ScopeConsumer(
                    null,
                    consumerName,
                    streamName,
                    consumerContext,
                    streamContext,
                    subjects,
                    msg -> handleMessage(null, msg));
            installation.start();
            installationConsumer = installation;
            stats.setInstallationConsumerActive(true);
            log.info("Started installation consumer: consumerName={}", consumerName);
        } catch (JetStreamApiException e) {
            throw new IOException("Failed to set up installation consumer", e);
        }
    }

    private ConsumerContext createOrUpdateConsumer(StreamContext streamContext, String consumerName, String[] subjects)
            throws IOException, JetStreamApiException {
        boolean durable = !isBlank(connectionProperties.durableConsumerName());

        if (durable) {
            try {
                ConsumerContext existing = streamContext.getConsumerContext(consumerName);
                ConsumerConfiguration config = existing.getConsumerInfo().getConsumerConfiguration();
                Set<String> existingSubjects = new HashSet<>(config.getFilterSubjects());
                Set<String> desiredSubjects = new HashSet<>(Arrays.asList(subjects));
                Duration desiredThreshold = consumerProperties.inactiveThreshold();
                // A consumer created before the threshold was configured keeps its old lifetime
                // until something rewrites it, so reconcile it here rather than only on create.
                boolean thresholdMatches = Objects.equals(
                        Objects.requireNonNullElse(config.getInactiveThreshold(), Duration.ZERO), desiredThreshold);
                if (existingSubjects.equals(desiredSubjects) && thresholdMatches) {
                    return existing;
                }
                log.info(
                        "Updating durable consumer: consumerName={}, oldSubjectCount={}, newSubjectCount={}, inactiveThreshold={}",
                        consumerName,
                        existingSubjects.size(),
                        desiredSubjects.size(),
                        desiredThreshold);
                return streamContext.createOrUpdateConsumer(ConsumerConfiguration.builder(config)
                        .filterSubjects(subjects)
                        .inactiveThreshold(desiredThreshold)
                        .build());
            } catch (JetStreamApiException e) {
                // 10014 = consumer not found: expected on first start / after cleanup, fall through
                // to the create-new path. Any other API error (auth, server fault) is a real failure
                // and must not be silently reinterpreted as "create a fresh consumer".
                if (e.getApiErrorCode() != 10014) {
                    throw e;
                }
                log.debug("Durable consumer not found, creating fresh: consumerName={}", consumerName);
            }
        }

        ConsumerConfiguration configuration =
                newConsumerConfiguration(subjects, consumerProperties, durable ? consumerName : null);
        log.info(
                "Creating {} consumer: consumerName={}, subjectCount={}, deliverPolicy={}",
                durable ? "durable" : "ephemeral",
                durable ? consumerName : "(ephemeral)",
                subjects.length,
                configuration.getDeliverPolicy());
        return streamContext.createOrUpdateConsumer(configuration);
    }

    static ConsumerConfiguration newConsumerConfiguration(
            String[] subjects, NatsConsumerProperties consumerProperties, @Nullable String durableName) {
        ConsumerConfiguration.Builder builder = ConsumerConfiguration.builder()
                .filterSubjects(subjects)
                .deliverPolicy(DeliverPolicy.New)
                .ackWait(consumerProperties.ackWait())
                .maxAckPending(consumerProperties.maxAckPending())
                // Server-side cap on redeliveries. Without this, MaxDeliver=∞ would let a
                // permanently-failing message NAK forever; the bound is enforced today only
                // by IntegrationPoisonHandler counting deliveredCount on our side, which
                // means a JetStream-side observability tool (`nats stream info`) cannot see
                // the policy. Mirrors the value the poison handler uses to ACK-terminate.
                .maxDeliver(consumerProperties.poison().maxRedeliver());
        if (!isBlank(durableName)) {
            // Only a durable is worth reaping: JetStream already deletes an ephemeral seconds after
            // its last pull, so a threshold here would lengthen that rather than bound it.
            builder.durable(Objects.requireNonNull(durableName))
                    .inactiveThreshold(consumerProperties.inactiveThreshold());
        }
        return builder.build();
    }

    private String durableBaseName() {
        String name = connectionProperties.durableConsumerName();
        if (isBlank(name)) {
            return "hephaestus";
        }
        return Objects.requireNonNull(name);
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    // Message dispatch

    /**
     * Single entry point for every queued message across every scope. The dispatcher is
     * the only resolution path; empty resolutions ACK as no-op (unknown events are not
     * errors). Exceptions land in {@link IntegrationPoisonHandler#nakWithBackoff} which
     * NAKs with exponential backoff and ACKs once the redelivery budget is exhausted.
     *
     * @param scopeId the workspace scope that owns the {@link ScopeConsumer} which delivered this
     *                message, or {@code null} for the installation-wide consumer (org-level GitHub
     *                events aren't tied to a single workspace). Used only to record webhook-liveness
     *                via {@link ConnectionActivityRecorder} — never affects dispatch/ACK/NAK.
     */
    void handleMessage(@Nullable Long scopeId, Message msg) {
        if (shuttingDown.get()) {
            safeNak(msg);
            return;
        }
        String subject = msg.getSubject();
        try {
            Optional<IntegrationMessageHandler> handler = dispatcher.dispatch(subject);
            if (handler.isEmpty()) {
                log.debug("No handler for subject, ACK-as-no-op: subject={}", sanitizeForLog(subject));
                msg.ack();
                return;
            }
            IntegrationMessageHandler resolvedHandler = handler.get();
            resolvedHandler.onMessage(msg);
            msg.ack();
            stats.recordDispatch(Instant.now());
            if (scopeId != null) {
                // Liveness watermark = "last event received and dispatched", NOT "content was ingested". A handler
                // may legitimately no-op (dedup, a Slack message dropped by the per-channel consent gate, a filtered
                // event) and still return normally; that event genuinely arrived, so stamping it is correct. Health
                // derivation does not read this field, so the coarse-freshness semantics carry no correctness risk.
                EventTypeKey key = resolvedHandler.key();
                if (key == null) {
                    throw new IllegalStateException("Integration handler returned a null key");
                }
                activityRecorder.recordEventProcessed(scopeId, key.kind(), key.eventType());
            }
        } catch (Exception e) {
            if (!shuttingDown.get()) {
                log.error("Handler failed for subject={}: {}", sanitizeForLog(subject), e.getMessage(), e);
            }
            stats.recordNak(Instant.now());
            poisonHandler.nakWithBackoff(msg);
        }
    }

    // Connection management

    private void validateConfiguration() {
        if (isBlank(connectionProperties.server())) {
            throw new IllegalStateException("hephaestus.sync.nats.server must be set when enabled=true");
        }
    }

    private void connectWithRetry() {
        Options options = buildOptions();
        int attempt = 0;
        while (!shuttingDown.get()) {
            try {
                natsConnection = Nats.connect(options);
                stats.setNatsConnectionStatus("CONNECTED");
                log.info("Established NATS connection: server={}", connectionProperties.server());
                return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException e) {
                long delay = reconnectBackoffMs(attempt);
                stats.setNatsConnectionStatus("RECONNECTING");
                log.error(
                        "NATS connection attempt failed: server={}, attempt={}, nextDelayMs={}",
                        connectionProperties.server(),
                        attempt,
                        delay,
                        e);
                if (attempt >= MAX_RECONNECT_ATTEMPTS) {
                    throw new NatsConnectionException(
                            "Failed to connect to NATS after " + MAX_RECONNECT_ATTEMPTS + " attempts", e);
                }
                sleepUninterruptibly(delay);
                attempt++;
            }
        }
    }

    private Options buildOptions() {
        return NatsOptions.builder(connectionProperties)
                .connectionListener((conn, type) -> {
                    if (conn != null && conn.getStatus() != null) {
                        stats.setNatsConnectionStatus(conn.getStatus().name());
                    }
                    if (conn != null && conn.getServerInfo() != null) {
                        log.debug(
                                "NATS connection event: type={}, port={}",
                                type,
                                conn.getServerInfo().getPort());
                    } else {
                        log.debug("NATS connection event: type={}", type);
                    }
                })
                .maxReconnects(-1)
                .reconnectWait(consumerProperties.reconnectDelay())
                .connectionTimeout(CONNECTION_TIMEOUT)
                .build();
    }

    /** Package-private + overridable so the reconcile/retry paths are unit-testable without a broker. */
    void ensureNatsConnectionEstablished() {
        if (natsConnection != null && natsConnection.getStatus() == Connection.Status.CONNECTED) {
            return;
        }
        synchronized (connectionLock) {
            if (natsConnection != null && natsConnection.getStatus() == Connection.Status.CONNECTED) {
                return;
            }
            try {
                natsConnection = Nats.connect(buildOptions());
                stats.setNatsConnectionStatus("CONNECTED");
            } catch (IOException e) {
                stats.setNatsConnectionStatus("DISCONNECTED");
                throw new NatsConnectionException("Failed to establish NATS connection", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NatsConnectionException("Connection attempt interrupted", e);
            }
        }
    }

    private void cleanupConsumer(String streamName, String consumerName) {
        if (natsConnection == null || natsConnection.getStatus() != Connection.Status.CONNECTED) {
            return;
        }
        try {
            natsConnection.jetStreamManagement().deleteConsumer(streamName, consumerName);
        } catch (JetStreamApiException e) {
            // 10014 = consumer not found, expected on restart paths.
            if (e.getApiErrorCode() != 10014) {
                log.debug("Failed to delete consumer: consumerName={}, error={}", consumerName, e.getMessage());
            }
        } catch (Exception e) {
            log.debug("Failed to delete consumer: consumerName={}, error={}", consumerName, e.getMessage());
        }
    }

    /**
     * Full-jitter exponential backoff for connection retries. Mirrors the AWS recipe:
     * {@code clamp(base * 2^attempt + jitter, max)}.
     */
    static long reconnectBackoffMs(int attempt) {
        int clamped = Math.max(0, Math.min(attempt, 6));
        long exponential = RECONNECT_BASE_MS * (1L << clamped);
        long jitter = ThreadLocalRandom.current().nextLong(1_000L);
        return Math.min(exponential + jitter, RECONNECT_MAX_MS);
    }

    private static void sleepUninterruptibly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void safeNak(Message msg) {
        try {
            msg.nak();
        } catch (Exception ignored) {
            // Expected during shutdown.
        }
    }

    // Test seams (package-private — used by unit tests in the same package).

    boolean isShuttingDown() {
        return shuttingDown.get();
    }

    int scopeConsumerCount() {
        return scopeConsumers.size();
    }

    /** The consumers currently TRACKED for a scope — i.e. the ones the fleet can still stop or update. */
    List<ScopeConsumer> trackedConsumers(Long scopeId) {
        return scopeConsumers.getOrDefault(scopeId, List.of());
    }

    boolean isInstallationActive() {
        return installationConsumer != null;
    }
}
