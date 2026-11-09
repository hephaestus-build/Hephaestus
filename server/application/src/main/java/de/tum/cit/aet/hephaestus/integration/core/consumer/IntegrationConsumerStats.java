package de.tum.cit.aet.hephaestus.integration.core.consumer;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Lock-free status holder shared between {@link IntegrationNatsConsumer} (writes) and
 * {@link IntegrationConsumerHealthIndicator} (reads). Allocation-free on the hot path.
 */
@Component
public class IntegrationConsumerStats {

    private static final @Nullable String STATUS_UNINITIALISED = null;

    private final AtomicReference<@Nullable String> natsStatus = new AtomicReference<>(STATUS_UNINITIALISED);
    private final AtomicInteger activeScopeConsumers = new AtomicInteger(0);
    private final AtomicReference<Boolean> installationActive = new AtomicReference<>(Boolean.FALSE);
    private final AtomicReference<Instant> lastDispatchAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastNakAt = new AtomicReference<>();
    private final AtomicReference<@Nullable BrokerVersion> brokerVersion = new AtomicReference<>();

    /** The version that the connected broker reports, and whether it meets the server's minimum. */
    public record BrokerVersion(String version, boolean supported) {}

    // Readers (called from the health probe; never blocking)

    /**
     * @return the underlying NATS connection status as a coarse-grained label
     *     (typically {@code CONNECTED}, {@code DISCONNECTED}, {@code CLOSED}, …).
     *     {@link Optional#empty()} when no connection has been attempted yet.
     */
    public Optional<String> natsConnectionStatus() {
        return Optional.ofNullable(natsStatus.get());
    }

    /**
     * @return the number of currently-active scope consumers. Does not include the
     *     installation-wide consumer; see {@link #installationConsumerActive()}.
     */
    public int activeScopeConsumerCount() {
        return activeScopeConsumers.get();
    }

    /**
     * @return {@code true} when the installation-wide consumer has been started AND not
     *     yet stopped.
     */
    public boolean installationConsumerActive() {
        return Boolean.TRUE.equals(installationActive.get());
    }

    /**
     * @return the timestamp of the most recent successful message dispatch, if any. The
     *     "no message yet" case returns {@link Optional#empty()} so the probe can render
     *     a dash instead of a confusing epoch zero.
     */
    public Optional<Instant> lastDispatchAt() {
        return Optional.ofNullable(lastDispatchAt.get());
    }

    /**
     * @return the timestamp of the most recent NAK (poison or transient), if any.
     */
    public Optional<Instant> lastNakAt() {
        return Optional.ofNullable(lastNakAt.get());
    }

    // Writers (called by the consumer fleet's lifecycle + message loop)

    /** {@link Optional#empty()} until a connection reported its broker's version. */
    public Optional<BrokerVersion> brokerVersion() {
        return Optional.ofNullable(brokerVersion.get());
    }

    public void setBrokerVersion(String version, boolean supported) {
        brokerVersion.set(new BrokerVersion(version, supported));
    }

    /** Update the cached connection-status label. Null tolerated (treated as uninitialised). */
    public void setNatsConnectionStatus(@Nullable String status) {
        natsStatus.set(status);
    }

    /** Replace the active scope consumer count. */
    public void setActiveScopeConsumerCount(int count) {
        activeScopeConsumers.set(Math.max(0, count));
    }

    /** Set whether the installation-wide consumer is currently running. */
    public void setInstallationConsumerActive(boolean active) {
        installationActive.set(active);
    }

    /** Record a successful dispatch (handler returned without throwing). Null tolerated. */
    public void recordDispatch(@Nullable Instant at) {
        if (at != null) {
            lastDispatchAt.set(at);
        }
    }

    /** Record a NAK (poison or transient). Null tolerated. */
    public void recordNak(@Nullable Instant at) {
        if (at != null) {
            lastNakAt.set(at);
        }
    }
}
