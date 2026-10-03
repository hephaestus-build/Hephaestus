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
 *       labels, milestones, teams, and <b>GitHub Projects V2</b>. Controlled by
 *       {@code run-on-startup} and {@code cron}. Always includes project sync.</li>
 *   <li><b>Historical Backfill:</b> Fills historical issues/PRs older than the initial
 *       sync window. Controlled by {@code backfill.enabled}. Runs on separate schedule
 *       after incremental sync completes. <b>Does NOT include projects</b> - projects
 *       are fully synced during incremental sync.</li>
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
 *       allowed-projects:
 *         - "my-org/1"    # Project #1 in my-org
 *         - "my-org/5"    # Project #5 in my-org
 * }</pre>
 *
 * @param runOnStartup whether to trigger a sync run immediately when the application starts
 * @param timeframeDays number of days to look back when synchronizing pull requests
 * @param cron cron expression for scheduled sync execution. Default: daily at 3 AM
 * @param cooldownMinutes minimum minutes between consecutive sync operations
 * @param backfill configuration for historical data backfill operations
 * @param filters configuration for filtering which organizations and repositories to sync
 * @param discussions configuration for GitHub Discussions sync (disabled by default)
 * @param projects configuration for GitHub Projects V2 sync (disabled by default)
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
        @Valid FilterProperties filters,
        @Valid DiscussionsProperties discussions,
        @Valid ProjectsProperties projects) {
    /**
     * Configuration for the backfill subsystem that handles historical data synchronization.
     *
     * <p>Backfill runs on a schedule, processing repositories that have completed
     * initial sync but still have historical data to fetch. Rate limit is the
     * primary throttle - backfill pauses when remaining API points drop below threshold.
     *
     * <p><b>Note:</b> Backfill only handles issues and pull requests, not projects.
     * Projects are synced via {@link ProjectsProperties} during incremental sync.
     *
     * @param enabled Whether backfill processing is enabled
     * @param batchSize Maximum pages to process per repository per cycle
     * @param rateLimitThreshold Remaining API rate limit below which backfill pauses
     * @param intervalSeconds Seconds between backfill cycles (default: 60s)
     */
    public record BackfillProperties(
            @DefaultValue("false") boolean enabled,
            @Min(1) @Max(1000) @DefaultValue("50") int batchSize,
            @Min(0) @DefaultValue("100") int rateLimitThreshold,
            @Min(10) @Max(86400) @DefaultValue("60") int intervalSeconds) {}

    /**
     * Configuration for filtering which organizations, repositories, and projects are synced.
     *
     * @param allowedOrganizations Set of organization names to include (empty = all)
     * @param allowedRepositories Set of repository names (org/repo) to include (empty = all)
     * @param allowedProjects Set of project identifiers (org/number) to include (empty = all)
     */
    public record FilterProperties(
            Set<String> allowedOrganizations, Set<String> allowedRepositories, Set<String> allowedProjects) {
        /** Compact constructor ensuring null safety. */
        public FilterProperties {
            if (allowedOrganizations == null) {
                allowedOrganizations = Set.of();
            }
            if (allowedRepositories == null) {
                allowedRepositories = Set.of();
            }
            if (allowedProjects == null) {
                allowedProjects = Set.of();
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

        /**
         * Checks if a project passes the filter.
         *
         * @param organizationLogin the organization login (e.g., "ls1intum")
         * @param projectNumber the project number within the organization
         * @return true if the project is allowed (empty filter = all allowed)
         */
        public boolean isProjectAllowed(String organizationLogin, int projectNumber) {
            if (allowedProjects.isEmpty()) {
                return true;
            }
            String projectKey = organizationLogin + "/" + projectNumber;
            return allowedProjects.contains(projectKey);
        }
    }

    /**
     * Toggle for GitHub Discussions sync.
     *
     * <p>Discussions sync paginates discussions, their comments, and replies (three nested
     * levels of GraphQL pagination), which is slow and currently unused. Disabled by default;
     * enable per-environment when discussion data is actually needed.
     *
     * @param enabled whether discussion sync runs (default {@code false})
     */
    public record DiscussionsProperties(
            @DefaultValue("false") boolean enabled) {}

    /**
     * Toggle for GitHub Projects V2 sync.
     *
     * <p>Project sync walks projects → fields → status updates → items → item field values,
     * which is the slowest part of a sync and currently unused. Disabled by default; enable
     * per-environment when project data is actually needed.
     *
     * @param enabled whether project sync runs (default {@code false})
     */
    public record ProjectsProperties(@DefaultValue("false") boolean enabled) {}

    /** Compact constructor ensuring nested records are never null. */
    public SyncSchedulerProperties(
            boolean runOnStartup,
            int timeframeDays,
            String cron,
            int cooldownMinutes,
            @Nullable BackfillProperties backfill,
            @Nullable FilterProperties filters,
            @Nullable DiscussionsProperties discussions,
            @Nullable ProjectsProperties projects) {
        this.runOnStartup = runOnStartup;
        this.timeframeDays = timeframeDays;
        this.cron = cron;
        this.cooldownMinutes = cooldownMinutes;
        this.backfill = backfill != null ? backfill : new BackfillProperties(false, 50, 100, 60);
        this.filters = filters != null ? filters : new FilterProperties(Set.of(), Set.of(), Set.of());
        this.discussions = discussions != null ? discussions : new DiscussionsProperties(false);
        this.projects = projects != null ? projects : new ProjectsProperties(false);
    }
}
