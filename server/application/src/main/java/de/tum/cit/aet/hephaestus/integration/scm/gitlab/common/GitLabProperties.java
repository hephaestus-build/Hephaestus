package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import static java.time.temporal.ChronoUnit.MILLIS;
import static java.time.temporal.ChronoUnit.SECONDS;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for GitLab synchronization operations.
 *
 * <p>Controls various aspects of the GitLab sync behavior including GraphQL operation
 * timeouts, API throttling, and token validation caching.
 *
 * <h2>Configuration Example</h2>
 * <pre>{@code
 * hephaestus:
 *   integration:
 *     gitlab:
 *       enabled: true
 *       default-server-url: https://gitlab.com
 *       graphql-timeout: 30s
 *       extended-graphql-timeout: 60s
 *       pagination-throttle: 200ms
 *       token-validation-cache-duration: 5m
 * }</pre>
 *
 * @param defaultServerUrl              The GitLab server URL for a workspace that names no server of its own.
 * @param graphqlTimeout                Timeout for GraphQL operations.
 * @param extendedGraphqlTimeout        Timeout for complex GraphQL operations.
 * @param paginationThrottle            Delay between pagination requests, to stay below the rate limits of GitLab.
 * @param tokenValidationCacheDuration  How long a token validation result is kept before Hephaestus checks the token
 *                                      against {@code /api/v4/user} again.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.integration.gitlab")
public record GitLabProperties(
        @NotNull @DefaultValue("https://gitlab.com") String defaultServerUrl,
        @NotNull @DurationUnit(SECONDS) @DefaultValue("30s") Duration graphqlTimeout,
        @NotNull @DurationUnit(SECONDS) @DefaultValue("60s") Duration extendedGraphqlTimeout,

        @NotNull @DurationUnit(MILLIS) @DefaultValue("200ms")
        Duration paginationThrottle,

        @NotNull @DurationUnit(SECONDS) @DefaultValue("5m") Duration tokenValidationCacheDuration) {}
