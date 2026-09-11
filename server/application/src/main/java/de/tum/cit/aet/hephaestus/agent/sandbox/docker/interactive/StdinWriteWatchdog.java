package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared timestamp inspector that enforces {@code hephaestus.mentor.stdin-write-timeout-ms}
 * across active sessions. {@link #tick()} runs on a dedicated worker maintenance scheduler; each tick is
 * O(N) timestamp reads with no blocking.
 */
public final class StdinWriteWatchdog {

    private static final Logger log = LoggerFactory.getLogger(StdinWriteWatchdog.class);

    private final ConcurrentHashMap<UUID, StallTarget> targets = new ConcurrentHashMap<>();

    public void register(UUID sessionId, StallTarget target) {
        targets.put(sessionId, target);
    }

    public void unregister(UUID sessionId) {
        targets.remove(sessionId);
    }

    public void tick() {
        long now = System.nanoTime();
        for (var entry : targets.entrySet()) {
            StallTarget t = entry.getValue();
            try {
                if (t.writeStalled(now)) {
                    log.warn("Stdin write stalled — triggering destroyForcibly: sessionId={}", entry.getKey());
                    t.onWriteTimeout();
                }
            } catch (Throwable th) {
                log.warn("Watchdog tick failed for session {}: {}", entry.getKey(), th.toString());
            }
        }
    }

    public int activeTargets() {
        return targets.size();
    }

    public interface StallTarget {
        boolean writeStalled(long nowNanos);

        /** Must be idempotent and non-blocking. */
        void onWriteTimeout();
    }

    public boolean isRegistered(UUID sessionId) {
        return targets.containsKey(sessionId);
    }
}
