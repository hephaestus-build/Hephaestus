package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static java.time.temporal.ChronoUnit.MILLIS;
import static java.time.temporal.ChronoUnit.MINUTES;
import static java.time.temporal.ChronoUnit.SECONDS;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for GitHub synchronization operations.
 *
 * <p>Controls various aspects of the sync behavior including GraphQL operation timeouts,
 * incremental sync settings, and API throttling. All timeout values are expressed as
 * {@link Duration} objects for type safety and flexibility in configuration.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * hephaestus:
 *   integration:
 *     github:
 *       sync:
 *         graphql-timeout: 30s
 *         extended-graphql-timeout: 60s
 *         pagination-throttle: 200ms
 *         incremental-sync-enabled: true
 *         incremental-sync-buffer: 5m
 * }</pre>
 *
 * <h2>Duration Format</h2>
 * <p>Duration properties accept various formats:
 * <ul>
 *   <li>{@code 30s} – 30 seconds</li>
 *   <li>{@code 1m} – 1 minute</li>
 *   <li>{@code 200ms} – 200 milliseconds</li>
 *   <li>{@code PT30S} – ISO-8601 format</li>
 * </ul>
 *
 * @param graphqlTimeout         default timeout for GraphQL operations; used when blocking on
 *                               reactive GraphQL client responses (default: 30 seconds)
 * @param extendedGraphqlTimeout extended timeout for complex GraphQL operations such as syncing
 *                               review comments with nested thread structures (default: 60 seconds)
 * @param backfillGraphqlTimeout Timeout for backfill operations, which fetch pull requests with their reviews,
 *                               threads and comments. Large repositories need a longer timeout than regular sync.
 * @param paginationThrottle     delay between pagination requests to avoid hammering GitHub's API;
 *                               reduces 502/504 errors caused by rapid-fire complex queries
 *                               (default: 200 milliseconds)
 * @param incrementalSyncEnabled whether to use incremental sync based on last sync timestamp;
 *                               when enabled, sync operations fetch only items updated since the
 *                               last sync (default: true)
 * @param incrementalSyncBuffer  Time subtracted from the last sync time, so that items updated shortly before it are
 *                               still fetched despite clock differences with GitHub.
 * @param backfillPrPageSize     Page size of pull request backfill queries. It is smaller than in regular sync,
 *                               because backfill responses include reviews, threads and comments.
 * @see <a href="https://docs.github.com/en/graphql/overview/rate-limits-and-node-limits-for-the-graphql-api">
 *      GitHub GraphQL Rate Limits</a>
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.integration.github.sync")
public record GitHubSyncProperties(
        @NotNull @DurationUnit(SECONDS) @DefaultValue("30s") Duration graphqlTimeout,
        @NotNull @DurationUnit(SECONDS) @DefaultValue("60s") Duration extendedGraphqlTimeout,

        @NotNull @DurationUnit(SECONDS) @DefaultValue("120s")
        Duration backfillGraphqlTimeout,

        @NotNull @DurationUnit(MILLIS) @DefaultValue("200ms")
        Duration paginationThrottle,

        @DefaultValue("true") boolean incrementalSyncEnabled,
        @NotNull @DurationUnit(MINUTES) @DefaultValue("5m") Duration incrementalSyncBuffer,
        @DefaultValue("10") int backfillPrPageSize) {}
