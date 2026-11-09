package de.tum.cit.aet.hephaestus.agent;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
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
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.agent.capacity")
public record AgentCapacityProperties(
        @DefaultValue("5m") @NotNull Duration leaseTtl,
        @DefaultValue("25s") @NotNull Duration leaseRenewal,
        @DefaultValue("5") @Min(0) int reviewRetries,
        @DefaultValue("10m") @NotNull Duration bandAging) {

    public AgentCapacityProperties {
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
