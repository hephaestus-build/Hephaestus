package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bound to {@code hephaestus.worker.*}. */
@ConfigurationProperties(prefix = "hephaestus.worker")
public record WorkerProperties(
        @Nullable String workerId, @DefaultValue Drain drain) {

    /**
     * Per-process-instance suffix, stable for the JVM's lifetime. Appended to the hostname default so
     * a worker that crashes and restarts (or a fixed-hostname compose/bare-metal redeploy) gets a
     * DIFFERENT id than its dead predecessor — otherwise the restarted process would re-heartbeat the
     * old id and mask its predecessor's orphaned jobs. K8s pod names are already unique, but
     * this makes fast orphan recovery hold in every topology.
     */
    private static final String INSTANCE_SUFFIX = UUID.randomUUID().toString().substring(0, 8);

    /**
     * @return the configured worker id (operator owns its restart semantics), or
     *     {@code <hostname>-<instance-suffix>} when unset — unique per process instance so a dead
     *     worker and its restart never share an id.
     */
    public String resolvedWorkerId() {
        if (workerId != null && !workerId.isBlank()) {
            return workerId;
        }
        String host = "worker";
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            if (hostname != null && !hostname.isBlank()) {
                host = hostname;
            }
        } catch (UnknownHostException ignored) {
            // fall through to the generic prefix
        }
        return host + "-" + INSTANCE_SUFFIX;
    }

    /**
     * Shutdown behavior of this worker.
     *
     * @param timeout How long the shutdown phase in which a stopping worker stops handing out conversation turns may
     *     take. Practice reviews run in their own sandboxes, so a worker does not wait for them.
     */
    public record Drain(@DefaultValue("5m") Duration timeout) {
        public Drain {
            if (timeout == null || timeout.isNegative()) {
                throw new IllegalArgumentException("drain.timeout must be >= 0, got: " + timeout);
            }
        }
    }
}
