package de.tum.cit.aet.hephaestus.integration.scm.gitlab.metrics;

public final class GitLabMetrics {

    public static final String GITLAB_GRAPHQL_RATELIMIT_LAST_QUERY_COST = "gitlab.graphql.ratelimit.last_query_cost";
    public static final String GITLAB_GRAPHQL_RATELIMIT_POINTS_LIMIT = "gitlab.graphql.ratelimit.points.limit";
    public static final String GITLAB_GRAPHQL_RATELIMIT_POINTS_REMAINING = "gitlab.graphql.ratelimit.points.remaining";
    public static final String GITLAB_GRAPHQL_RATELIMIT_POINTS_USED = "gitlab.graphql.ratelimit.points.used";
    public static final String GITLAB_GRAPHQL_RATELIMIT_SECONDS_UNTIL_RESET =
            "gitlab.graphql.ratelimit.seconds_until_reset";
    public static final String GITLAB_SYNC_ERRORS_TOTAL = "gitlab.sync.errors.total";

    private GitLabMetrics() {}
}
