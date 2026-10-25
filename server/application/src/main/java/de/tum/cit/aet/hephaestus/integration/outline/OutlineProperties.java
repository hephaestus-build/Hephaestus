package de.tum.cit.aet.hephaestus.integration.outline;

import static java.time.temporal.ChronoUnit.HOURS;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

/**
 * Configuration for the Outline document sync and its local mirror.
 *
 * <pre>{@code
 * hephaestus.integration.outline:
 *   enabled: true
 *   sync: { cron: "0 0 0/6 * * *", export-budget: 500, catch-up-delay: PT5M }
 *   cache: { max-size-mb: 200 }
 *   staleness: 30d
 * }</pre>
 *
 * @param sync      scheduling and pacing of the reconcile passes
 * @param cache     bound on the per-workspace mirrored-body footprint
 * @param staleness How long a document that was deleted in Outline stays as a tombstone before its row is dropped.
 */
@ConfigurationProperties(prefix = "hephaestus.integration.outline")
public record OutlineProperties(
        @DefaultValue Sync sync,
        @DefaultValue Cache cache,
        @DurationUnit(HOURS) @DefaultValue("720h") Duration staleness) {
    /**
     * The {@code cron} and {@code catch-up-delay} yml keys are read straight off the Environment by the
     * {@code @Scheduled} SpEL in {@code OutlineDocumentSyncScheduler} (matching the Slack sibling), so they
     * are deliberately not bound here — a record component would be dead config.
     *
     * @param exportBudget Most document exports that one pass over a workspace may use. A collection that runs out of
     *                     budget stays pending, and the next catch-up pass continues it.
     */
    public record Sync(@DefaultValue("500") int exportBudget) {}

    /**
     * @param maxSizeMb Cap, for each workspace, on the total size of mirrored document bodies. Above it, the bodies
     *                  that were materialized longest ago are evicted, and their rows stay.
     */
    public record Cache(@DefaultValue("200") int maxSizeMb) {}
}
