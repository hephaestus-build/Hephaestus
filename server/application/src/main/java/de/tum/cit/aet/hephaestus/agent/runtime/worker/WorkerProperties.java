package de.tum.cit.aet.hephaestus.agent.runtime.worker;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound to {@code hephaestus.worker.*}. {@code capacity.{review,mentor}Max} accept an integer or
 * the literal {@code "auto"}; resolution lives on {@link Capacity}. {@code capacity.mentorMax} is the
 * number of Heph turns this worker's Heph host runs at once. {@code toString()} redacts the
 * registration token.
 */
@ConfigurationProperties(prefix = "hephaestus.worker")
public record WorkerProperties(
        @Nullable String workerId,
        @DefaultValue Capacity capacity,
        @DefaultValue Drain drain,
        @DefaultValue Heartbeat heartbeat,
        @DefaultValue Control control) {
    public static final String AUTO = "auto";

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

    @Override
    public String toString() {
        return ("WorkerProperties[workerId=" + workerId
                + ", capacity="
                + capacity
                + ", drain="
                + drain
                + ", heartbeat="
                + heartbeat
                + ", control="
                + control
                + "]");
    }

    /**
     * Concurrency of this worker.
     *
     * @param reviewMax Number of practice reviews this worker runs at once. Accepts an integer or auto, which means
     *     one less than the processor count and at least 1.
     * @param mentorMax Number of Heph turns this worker runs at once. Accepts an integer or auto, which means 8.
     */
    public record Capacity(
            @DefaultValue("auto") String reviewMax,
            @DefaultValue("auto") String mentorMax) {
        public int resolveReviewMax() {
            return resolve(reviewMax, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        }

        /** A Heph turn mostly waits for its model, so its default does not follow the processor count. */
        public int resolveMentorMax() {
            return resolve(mentorMax, 8);
        }

        private static int resolve(String configured, int autoDefault) {
            if (configured == null || configured.isBlank() || AUTO.equalsIgnoreCase(configured.trim())) {
                return autoDefault;
            }
            try {
                int parsed = Integer.parseInt(configured.trim());
                if (parsed < 0) {
                    throw new IllegalArgumentException("capacity must be >= 0 or 'auto', got: " + configured);
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("capacity must be an integer or 'auto', got: " + configured, e);
            }
        }
    }

    /**
     * Shutdown behavior of this worker.
     *
     * @param timeout How long a stopping worker waits for running practice reviews to finish before it cancels them.
     *     Set 0s to cancel them at once.
     */
    public record Drain(@DefaultValue("5m") Duration timeout) {
        public Drain {
            if (timeout == null || timeout.isNegative()) {
                throw new IllegalArgumentException("drain.timeout must be >= 0, got: " + timeout);
            }
        }
    }

    public record Heartbeat(@DefaultValue("20s") Duration interval) {
        public Heartbeat {
            if (interval == null || interval.isZero() || interval.isNegative()) {
                throw new IllegalArgumentException("heartbeat.interval must be positive, got: " + interval);
            }
        }
    }

    /**
     * Control channel from this worker to the server.
     *
     * @param endpoint WebSocket address of the server's worker control channel, such as
     *     ws://application-server:8080/api/workers/connect. The worker stays disconnected when it is unset.
     * @param registrationToken Token that this worker exchanges at the server for its access token. Give it the same
     *     value as hephaestus.worker.hub.token.registration-token on the server.
     */
    public record Control(
            @Nullable URI endpoint,
            @Nullable String registrationToken,
            @DefaultValue("10s") Duration handshakeTimeout) {
        public Control {
            if (handshakeTimeout == null || handshakeTimeout.isZero() || handshakeTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "control.handshakeTimeout must be positive, got: " + handshakeTimeout);
            }
        }

        @Override
        public String toString() {
            return ("Control[endpoint=" + endpoint
                    + ", registrationToken="
                    + (registrationToken == null || registrationToken.isBlank() ? "<unset>" : "<redacted>")
                    + ", handshakeTimeout="
                    + handshakeTimeout
                    + "]");
        }

        /** @return {@code true} iff the worker has enough config to attempt a WSS dial. */
        public boolean isConfigured() {
            return (endpoint != null && registrationToken != null && !registrationToken.isBlank());
        }
    }
}
