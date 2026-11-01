package de.tum.cit.aet.hephaestus.integration.core.framework;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the sync scheduler that orchestrates repository
 * and pull request synchronization from external Git providers.
 *
 * <p>This class consolidates all sync-related configuration including scheduling,
 * filtering, and backfill settings into a single, cohesive configuration namespace.
 *
 * <h2>Sync Types</h2>
 * <p>The system has two distinct sync modes:
 * <ul>
 *   <li><b>Incremental Sync:</b> Syncs recent data including repositories, issues, PRs,
 *       labels, milestones, and teams. Controlled by
 *       {@code run-on-startup} and {@code cron}.</li>
 *   <li><b>Historical Backfill:</b> Fills historical issues/PRs older than the initial
 *       sync window. Controlled by {@code backfill.enabled}. Runs on separate schedule
 *       after incremental sync completes.</li>
 * </ul>
 *
 * <h2>YAML Configuration Example</h2>
 * <pre>{@code
 * hephaestus:
 *   sync:
 *     run-on-startup: true
 *     timeframe-days: 7
 *     cron: "0 0 3 * * *"
 *     cooldown-minutes: 15
 *     backfill:
 *       enabled: false
 *       batch-size: 50
 *       rate-limit-threshold: 100
 *       interval-seconds: 60
 *     filters:
 *       allowed-organizations:
 *         - "my-org"
 *       allowed-repositories:
 *         - "my-org/specific-repo"
 * }</pre>
 *
 * @param runOnStartup whether to trigger a sync run immediately when the application starts
 * @param timeframeDays number of days to look back when synchronizing pull requests
 * @param cron cron expression for scheduled sync execution
 * @param cooldownMinutes minimum minutes between consecutive sync operations
 * @param backfill configuration for historical data backfill operations
 * @param filters configuration for filtering which organizations and repositories to sync
 * @see BackfillProperties
 * @see FilterProperties
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.sync")
public record SyncSchedulerProperties(
        @DefaultValue("true") boolean runOnStartup,
        @Min(1) @Max(365) @DefaultValue("7") int timeframeDays,
        @NotBlank @DefaultValue("0 0 3 * * *") String cron,
        @Min(1) @Max(1440) @DefaultValue("15") int cooldownMinutes,
        @Valid BackfillProperties backfill,
        @Valid FilterProperties filters) {
    /**
     * Configuration for the backfill subsystem that handles historical data synchronization.
     *
     * <p>Backfill runs on a schedule, processing repositories that have completed
     * initial sync but still have historical data to fetch. Rate limit is the
     * primary throttle - backfill pauses when remaining API points drop below threshold.
     *
     * @param enabled Whether backfill processing is enabled
     * @param batchSize Maximum pages to process per repository per cycle
     * @param rateLimitThreshold Remaining API rate limit below which backfill pauses
     * @param intervalSeconds Seconds between backfill cycles. A cycle holds its replica lock for at least 30
     *     seconds, so a shorter interval runs at most every 30 seconds.
     */
    public record BackfillProperties(
            @DefaultValue("false") boolean enabled,
            @Min(1) @Max(1000) @DefaultValue("50") int batchSize,
            @Min(0) @DefaultValue("100") int rateLimitThreshold,
            @Min(10) @Max(86400) @DefaultValue("60") int intervalSeconds) {}

    /**
     * Configuration for filtering which organizations and repositories are synced.
     *
     * @param allowedOrganizations Set of organization names to include (empty = all)
     * @param allowedRepositories Set of repository names (org/repo) to include (empty = all)
     */
    public record FilterProperties(Set<String> allowedOrganizations, Set<String> allowedRepositories) {
        /** Compact constructor ensuring null safety. */
        public FilterProperties {
            if (allowedOrganizations == null) {
                allowedOrganizations = Set.of();
            }
            if (allowedRepositories == null) {
                allowedRepositories = Set.of();
            }
        }

        /** Checks if an organization passes the filter. */
        public boolean isOrganizationAllowed(String organization) {
            return allowedOrganizations.isEmpty() || allowedOrganizations.contains(organization);
        }

        /** Checks if a repository passes the filter. */
        public boolean isRepositoryAllowed(String repositoryFullName) {
            return allowedRepositories.isEmpty() || allowedRepositories.contains(repositoryFullName);
        }
    }

    /** Compact constructor ensuring nested records are never null. */
    public SyncSchedulerProperties(
            boolean runOnStartup,
            int timeframeDays,
            String cron,
            int cooldownMinutes,
            @Nullable BackfillProperties backfill,
            @Nullable FilterProperties filters) {
        this.runOnStartup = runOnStartup;
        this.timeframeDays = timeframeDays;
        this.cron = cron;
        this.cooldownMinutes = cooldownMinutes;
        this.backfill = backfill != null ? backfill : new BackfillProperties(false, 50, 100, 60);
        this.filters = filters != null ? filters : new FilterProperties(Set.of(), Set.of());
    }
}
