package de.tum.cit.aet.hephaestus.agent;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The one home of the agent runtime's capacity numbers. The chart sets each value directly; nothing derives one from
 * another.
 *
 * @param leaseTtl how long a claimed run stays leased without a renewal. A holder that stalls longer loses its run,
 *     and the reaper requeues it. Allow for the database connection timeout: a false expiry stops a live holder.
 * @param leaseRenewal how often a holder renews each lease it holds, and how often each server replica reaps the
 *     expired ones. At most half the lease, so a holder survives one missed renewal.
 * @param reviewRetries how many lost leases a review job survives. A requeue that its holder does not charge, such as
 *     one for missing sandbox room, spends none.
 * @param bandAging how long a queued run waits after it was due before the claim orders it one band higher, and one
 *     more for each further wait. It never rises into the interactive band. This bounds how long scheduled work waits
 *     behind a steady stream of requested work.
 * @param sandboxStart how long a sandbox may take from its claim to its first call to the gateway. A sandbox that
 *     takes longer is deleted, and its subject's next need requests a new one.
 * @param sandboxIdle how long a review or action sandbox stays after its subject has no queued or leased run. It
 *     covers the step from one run of a subject to its next, such as a review's measuring to its composing.
 * @param conversationSandboxIdle how long a conversation's sandbox stays warm after its last call to the gateway while
 *     its conversation has no queued or leased run. The next turn after it starts a cold sandbox.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.agent.capacity")
public record AgentCapacityProperties(
        @DefaultValue("5m") @NotNull Duration leaseTtl,
        @DefaultValue("25s") @NotNull Duration leaseRenewal,
        @DefaultValue("5") @Min(0) int reviewRetries,
        @DefaultValue("10m") @NotNull Duration bandAging,
        @DefaultValue("2m") @NotNull Duration sandboxStart,
        @DefaultValue("1m") @NotNull Duration sandboxIdle,
        @DefaultValue("15m") @NotNull Duration conversationSandboxIdle) {

    public AgentCapacityProperties {
        for (Duration limit : List.of(sandboxStart, sandboxIdle, conversationSandboxIdle))
            if (limit.compareTo(Duration.ofSeconds(1)) < 0)
                throw new IllegalArgumentException(
                        "hephaestus.agent.capacity sandbox-start, sandbox-idle and conversation-sandbox-idle must be at"
                                + " least 1s");
        if (bandAging.compareTo(Duration.ofSeconds(1)) < 0)
            throw new IllegalArgumentException("hephaestus.agent.capacity.band-aging must be at least 1s");
        if (leaseRenewal.compareTo(Duration.ofSeconds(1)) < 0)
            throw new IllegalArgumentException("hephaestus.agent.capacity.lease-renewal must be at least 1s");
        if (leaseRenewal.multipliedBy(2).compareTo(leaseTtl) > 0)
            throw new IllegalArgumentException(
                    "hephaestus.agent.capacity.lease-renewal must be at most half of lease-ttl, so a holder survives"
                            + " one missed renewal");
    }
}
