package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import java.time.Duration;

/**
 * Constants used across GitHub synchronization services.
 * <p>
 * Centralizes common configuration values to avoid duplication and ensure
 * consistency across sync services.
 */
public final class GitHubSyncConstants {

    private GitHubSyncConstants() {
        // Utility class - prevent instantiation
    }

    // API Configuration

    /**
     * GitHub REST API base URL.
     */
    public static final String GITHUB_API_BASE_URL = "https://api.github.com";

    // Pagination

    /**
     * Default page size for GraphQL queries (100 items per page).
     * <p>
     * Used for most sync operations like issues and comments.
     * GitHub API allows up to 100 items per page, so we use the maximum to minimize API calls.
     */
    public static final int DEFAULT_PAGE_SIZE = 100;

    /**
     * Page size for issue sync GraphQL queries.
     * <p>
     * Uses a reduced page size because the issue query embeds comments (first 10),
     * assignees, and labels per issue. With 25 issues per
     * page the worst case is ~25 × (10 + 10 + 10) = 750 nested nodes — safely
     * under GitHub's MAX_NODE_LIMIT. Previous value of 100 triggered
     * MAX_NODE_LIMIT_EXCEEDED errors on repositories with many issue cross-references.
     */
    public static final int ISSUE_SYNC_PAGE_SIZE = 25;

    /**
     * Page size for pull request sync GraphQL queries.
     * <p>
     * Uses a small page size because the PR query has triple-nested pagination:
     * pullRequests -> reviews -> reviewThreads -> comments, which creates exponential
     * data volume. With the nested collection sizes of 5, each PR can generate up to
     * 5 reviews + 5 threads × 5 comments = 30 nodes. At 10 PRs per page, worst case
     * is ~300 nested nodes per query — well within GitHub's compute budget.
     * <p>
     * Previous value of 25 caused GitHub API timeouts (502 errors) on repositories
     * with heavy review activity (e.g., Artemis with 10,000+ PRs).
     */
    public static final int PR_SYNC_PAGE_SIZE = 10;

    /**
     * Large page size for GraphQL queries (100 items per page).
     * <p>
     * Used for bulk operations like labels, milestones, and other simpler entities.
     */
    public static final int LARGE_PAGE_SIZE = 100;

    /**
     * Maximum number of pages to fetch in pagination loops.
     * <p>
     * Acts as a defensive upper bound to prevent infinite loops if the API
     * incorrectly reports hasNextPage=true forever or returns the same cursor.
     * With 100 items per page, this allows up to 100,000 items before stopping.
     * If this limit is reached, a warning is logged and the loop exits gracefully.
     */
    public static final int MAX_PAGINATION_PAGES = 1000;

    // Transport retry configuration
    public static final int TRANSPORT_MAX_RETRIES = 3;
    public static final Duration TRANSPORT_INITIAL_BACKOFF = Duration.ofSeconds(2);
    public static final Duration TRANSPORT_MAX_BACKOFF = Duration.ofSeconds(15);
    public static final double JITTER_FACTOR = 0.5;

    // Adaptive Page Sizing

    /**
     * Remaining-points threshold below which page sizes are halved.
     */
    private static final int LOW_REMAINING_THRESHOLD = 500;

    /**
     * Remaining-points threshold below which page sizes are quartered.
     */
    private static final int CRITICAL_REMAINING_THRESHOLD = 100;

    /**
     * Returns an adjusted page size based on current rate-limit budget.
     * <p>
     * When the rate-limit budget is healthy the base page size is returned
     * unchanged. As the budget drops, the page size is reduced to slow down
     * point consumption and give the budget time to reset:
     * <ul>
     *   <li>{@code remaining >= 500} → full {@code basePageSize}</li>
     *   <li>{@code 100 <= remaining < 500} → {@code basePageSize / 2} (min 10)</li>
     *   <li>{@code remaining < 100} → {@code basePageSize / 4} (min 5)</li>
     * </ul>
     *
     * @param basePageSize the nominal page size (e.g. {@link #DEFAULT_PAGE_SIZE})
     * @param remaining    current rate-limit points remaining for the scope
     * @return the (possibly reduced) page size to use for the next query
     */
    public static int adaptPageSize(int basePageSize, int remaining) {
        if (remaining >= LOW_REMAINING_THRESHOLD) {
            return basePageSize;
        }
        if (remaining >= CRITICAL_REMAINING_THRESHOLD) {
            return Math.max(10, basePageSize / 2);
        }
        return Math.max(5, basePageSize / 4);
    }
}
