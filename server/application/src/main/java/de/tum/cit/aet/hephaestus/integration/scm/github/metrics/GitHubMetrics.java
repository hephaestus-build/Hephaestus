package de.tum.cit.aet.hephaestus.integration.scm.github.metrics;

public final class GitHubMetrics {

    public static final String GITHUB_GRAPHQL_RATELIMIT_LAST_QUERY_COST = "github.graphql.ratelimit.last_query_cost";
    public static final String GITHUB_GRAPHQL_RATELIMIT_LIMIT = "github.graphql.ratelimit.limit";
    public static final String GITHUB_GRAPHQL_RATELIMIT_POINTS_LIMIT = "github.graphql.ratelimit.points.limit";
    public static final String GITHUB_GRAPHQL_RATELIMIT_POINTS_REMAINING = "github.graphql.ratelimit.points.remaining";
    public static final String GITHUB_GRAPHQL_RATELIMIT_POINTS_USED = "github.graphql.ratelimit.points.used";
    public static final String GITHUB_GRAPHQL_RATELIMIT_REMAINING = "github.graphql.ratelimit.remaining";
    public static final String GITHUB_GRAPHQL_RATELIMIT_SECONDS_UNTIL_RESET =
            "github.graphql.ratelimit.seconds_until_reset";
    public static final String GITHUB_GRAPHQL_RATELIMIT_USED = "github.graphql.ratelimit.used";
    public static final String GITHUB_SYNC_ERRORS_TOTAL = "github.sync.errors.total";

    private GitHubMetrics() {}
}
