package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.sandbox.InteractiveSandboxProperties;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.DockerOperations;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxContainerManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.docker.SandboxLabels;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.AttachedSandboxState;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.EvictionReason;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory registry of live sessions keyed by {@code (userId, workspaceId)}. Owns capacity caps,
 * idle reaper and post-restart orphan sweep.
 */
@WorkspaceAgnostic("Interactive sandbox registry keys by user+workspace, not by workspace-iteration semantics")
public class InteractiveSandboxRegistry {

    private static final Logger log = LoggerFactory.getLogger(InteractiveSandboxRegistry.class);

    private final InteractiveSandboxProperties properties;
    private final SandboxContainerManager containerManager;
    private final InteractiveSandboxMetrics metrics;
    private final StdinWriteWatchdog watchdog;

    private final ConcurrentHashMap<SessionKey, DockerAttachedSandboxAdapter> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> sessionsPerUser = new ConcurrentHashMap<>();
    private volatile boolean shuttingDown = false;

    public InteractiveSandboxRegistry(
            InteractiveSandboxProperties properties,
            SandboxContainerManager containerManager,
            InteractiveSandboxMetrics metrics,
            StdinWriteWatchdog watchdog,
            MeterRegistry meterRegistry) {
        this.properties = properties;
        this.containerManager = containerManager;
        this.metrics = metrics;
        this.watchdog = watchdog;
        Gauge.builder(AgentMetrics.MENTOR_SESSION_ACTIVE, sessions, ConcurrentHashMap::size)
                .description("Currently attached mentor sandbox sessions on this replica")
                .register(meterRegistry);
        meterRegistry.gauge(
                AgentMetrics.MENTOR_WATCHDOG_TARGETS, Tags.empty(), watchdog, StdinWriteWatchdog::activeTargets);
    }

    @Nullable
    DockerAttachedSandboxAdapter find(String userId, String workspaceId) {
        return sessions.get(new SessionKey(userId, workspaceId));
    }

    /** Like {@link #find}, but {@code null} unless state is {@link AttachedSandboxState#ATTACHED}. */
    public @Nullable DockerAttachedSandboxAdapter findLive(String userId, String workspaceId) {
        DockerAttachedSandboxAdapter sandbox = sessions.get(new SessionKey(userId, workspaceId));
        return sandbox != null && sandbox.state() == AttachedSandboxState.ATTACHED ? sandbox : null;
    }

    /**
     * On non-{@code REGISTERED}, the caller tears down the sandbox. Caps are enforced atomically:
     * the per-user counter is incremented inside a {@code compute()} block (per-key lock), and a
     * global rollback runs if {@code putIfAbsent} or the post-check fails.
     */
    public RegistrationOutcome tryRegister(DockerAttachedSandboxAdapter sandbox) {
        Objects.requireNonNull(sandbox, "sandbox");
        var id = sandbox.identity();
        SessionKey key = new SessionKey(id.userId(), id.workspaceId());

        // Per-user atomic reservation: increment only when a slot is available.
        AtomicReference<RegistrationOutcome> userResult = new AtomicReference<>();
        sessionsPerUser.compute(id.userId(), (u, prev) -> {
            AtomicInteger c = prev != null ? prev : new AtomicInteger();
            if (c.get() >= properties.maxSessionsPerUser()) {
                userResult.set(RegistrationOutcome.MAX_SESSIONS_PER_USER);
                // Don't materialise an empty counter purely to reject.
                return prev;
            }
            c.incrementAndGet();
            return c;
        });
        if (userResult.get() != null) {
            return userResult.get();
        }
        AtomicInteger userCount = sessionsPerUser.get(id.userId());
        if (userCount == null) {
            throw new IllegalStateException("User session reservation was not created");
        }

        if (sessions.size() >= properties.maxSessionsTotal()) {
            decrementUser(id.userId(), userCount);
            return RegistrationOutcome.MAX_SESSIONS_TOTAL;
        }
        if (sessions.putIfAbsent(key, sandbox) != null) {
            decrementUser(id.userId(), userCount);
            return RegistrationOutcome.DUPLICATE;
        }
        // Race: concurrent putIfAbsent calls may have pushed total over the cap. Roll back if so.
        if (sessions.size() > properties.maxSessionsTotal()) {
            sessions.remove(key, sandbox);
            decrementUser(id.userId(), userCount);
            return RegistrationOutcome.MAX_SESSIONS_TOTAL;
        }
        watchdog.register(id.sessionId(), sandbox);
        return RegistrationOutcome.REGISTERED;
    }

    /** Identity-based remove avoids races with re-register. */
    void onSandboxClosed(DockerAttachedSandboxAdapter sandbox) {
        var id = sandbox.identity();
        SessionKey key = new SessionKey(id.userId(), id.workspaceId());
        boolean removed = sessions.remove(key, sandbox);
        if (removed) {
            AtomicInteger userCount = sessionsPerUser.get(id.userId());
            if (userCount != null) {
                decrementUser(id.userId(), userCount);
            }
        }
        watchdog.unregister(id.sessionId());
    }

    /** Atomic decrement-and-conditional-remove (compute holds the per-key lock). */
    private void decrementUser(String userId, AtomicInteger userCount) {
        sessionsPerUser.compute(userId, (u, cur) -> {
            if (cur == null) return null;
            int v = cur.decrementAndGet();
            return v <= 0 ? null : cur;
        });
    }

    public void reap() {
        if (shuttingDown) {
            // During @PreDestroy a maintenance sweep may already be in progress.
            return;
        }
        Duration ttl = Duration.ofSeconds(properties.idleTtlSeconds());
        for (DockerAttachedSandboxAdapter sandbox : sessions.values()) {
            if (sandbox.state() != AttachedSandboxState.ATTACHED) {
                continue;
            }
            if (sandbox.idleFor().compareTo(ttl) > 0) {
                log.info(
                        "Reaping idle sandbox: sessionId={}, idleFor={}s",
                        sandbox.identity().sessionId(),
                        sandbox.idleFor().toSeconds());
                // Fire and forget so one closing session does not delay the remaining idle sessions.
                sandbox.terminate(EvictionReason.IDLE);
            }
        }
    }

    /** After a restart the in-memory registry is gone; any {@code KIND=interactive} container is orphan. */
    public void onStartup() {
        try {
            List<DockerOperations.ContainerInfo> managed = containerManager.listManagedContainers();
            int removed = 0;
            for (DockerOperations.ContainerInfo c : managed) {
                if (SandboxLabels.KIND_INTERACTIVE.equals(c.labels().get(SandboxLabels.KIND))) {
                    try {
                        containerManager.forceRemove(c.id());
                        removed++;
                    } catch (Exception e) {
                        log.warn("Failed to remove orphaned interactive container {}: {}", c.id(), e.getMessage());
                    }
                }
            }
            if (removed > 0) {
                log.info("Removed {} orphaned interactive container(s) on startup", removed);
            }
        } catch (Exception e) {
            log.warn("Startup interactive container sweep failed: {}", e.getMessage());
        }
    }

    /** Parallel close; grace + 5 s total deadline matches the per-session waitForClose budget. */
    @PreDestroy
    public void shutdown() {
        shuttingDown = true;
        if (sessions.isEmpty()) {
            return;
        }
        int count = sessions.size();
        log.info("Closing {} mentor sandbox(es) for shutdown", count);
        Duration grace = Duration.ofSeconds(properties.graceTimeoutSeconds());
        List<DockerAttachedSandboxAdapter> snapshot = new ArrayList<>(sessions.values());
        CompletableFuture<?>[] futures = snapshot.stream()
                .map(sandbox -> CompletableFuture.runAsync(() -> {
                    try {
                        sandbox.close(grace);
                    } catch (Exception e) {
                        log.warn("Error closing sandbox during shutdown: {}", e.getMessage());
                    }
                }))
                .toArray(CompletableFuture[]::new);
        long deadlineSeconds = grace.toSeconds() + 5;
        try {
            CompletableFuture.allOf(futures).get(deadlineSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Parallel shutdown did not complete cleanly within {}s: {}", deadlineSeconds, e.getMessage());
        }
    }

    int size() {
        return sessions.size();
    }

    public record SessionKey(String userId, String workspaceId) {}

    public enum RegistrationOutcome {
        REGISTERED,
        DUPLICATE,
        MAX_SESSIONS_PER_USER,
        MAX_SESSIONS_TOTAL,
    }
}
