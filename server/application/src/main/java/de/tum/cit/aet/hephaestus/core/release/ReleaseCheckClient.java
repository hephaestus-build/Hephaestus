package de.tum.cit.aet.hephaestus.core.release;

import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.core.release.ReleaseStatusDTO.LatestReleaseDTO;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Asks GitHub for the newest published release of this repository. One unauthenticated, conditional
 * GET on its own short-timeout client: no workspace credential, no redirect, and nothing about the
 * instance in the request beyond the static user agent — the privacy statement in
 * {@code docs/admin/install.mdx} describes exactly this request.
 */
@Component
@ConditionalOnServerRole
public class ReleaseCheckClient {
    static final String REPOSITORY = "hephaestus-build/Hephaestus";
    static final String REPOSITORY_URL = "https://github.com/" + REPOSITORY;

    /**
     * The flag {@code .github/workflows/release.yml} writes into every release's notes, invisible in
     * GitHub's rendering. Absent from releases published before the workflow learned to write it.
     */
    private static final String SCHEMA_MIGRATIONS_FLAG = "<!-- hephaestus:schema-migrations=";

    private static final Duration UNPARSEABLE_WAIT = Duration.ofHours(24);

    /** What one request established. */
    public sealed interface Outcome permits Found, NotModified, Failed {}

    /** GitHub answered with a release this checker understands. */
    public record Found(LatestReleaseDTO release, @Nullable String etag) implements Outcome {}

    /** GitHub confirmed the release behind the offered ETag is still the latest. */
    public record NotModified() implements Outcome {}

    /** The request did not yield a release; {@code retryAt} is the wait GitHub named, if any. */
    public record Failed(
            ReleaseCheckFailure reason, @Nullable Instant retryAt) implements Outcome {}

    /** The subset of GitHub's release object this checker reads; everything else is ignored. */
    record GitHubRelease(
            @JsonProperty("tag_name") @Nullable String tagName,
            boolean draft,
            boolean prerelease,
            @JsonProperty("published_at") @Nullable Instant publishedAt,
            @Nullable String body) {}

    private final RestClient client;
    private final Clock clock;

    @Autowired
    ReleaseCheckClient(Clock clock) {
        this(
                RestClient.builder()
                        .requestFactory(ClientHttpRequestFactoryBuilder.jdk()
                                .build(HttpClientSettings.defaults()
                                        .withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(10))
                                        .withRedirects(HttpRedirects.DONT_FOLLOW))),
                clock);
    }

    ReleaseCheckClient(RestClient.Builder builder, Clock clock) {
        this.clock = clock;
        client = builder.baseUrl("https://api.github.com/repos/" + REPOSITORY + "/releases/latest")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader(HttpHeaders.USER_AGENT, "Hephaestus-release-check")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /** Fetches the latest release, revalidating {@code etag} when the caller still holds one. */
    public Outcome fetchLatest(@Nullable String etag) {
        ResponseEntity<GitHubRelease> response;
        try {
            response = client.get()
                    .headers(headers -> {
                        if (etag != null) headers.setIfNoneMatch(etag);
                    })
                    .retrieve()
                    .toEntity(GitHubRelease.class);
        } catch (RestClientResponseException exception) {
            HttpHeaders headers = exception.getResponseHeaders();
            return failed(exception.getStatusCode(), headers == null ? HttpHeaders.EMPTY : headers);
        } catch (ResourceAccessException exception) {
            return new Failed(ReleaseCheckFailure.UNAVAILABLE, null);
        } catch (RestClientException exception) {
            return new Failed(ReleaseCheckFailure.MALFORMED, null);
        }
        if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_MODIFIED)) return new NotModified();
        GitHubRelease release = response.getBody();
        String tag = release == null ? null : release.tagName();
        if (release == null
                || release.draft()
                || release.prerelease()
                || release.publishedAt() == null
                || tag == null
                || !tag.startsWith("v")
                || !RunningRelease.RELEASE_VERSION.matcher(tag.substring(1)).matches()) {
            return new Failed(ReleaseCheckFailure.MALFORMED, null);
        }
        return new Found(
                new LatestReleaseDTO(
                        tag.substring(1),
                        release.publishedAt(),
                        REPOSITORY_URL + "/releases/tag/" + tag,
                        schemaMigrations(release.body())),
                response.getHeaders().getETag());
    }

    private static @Nullable Boolean schemaMigrations(@Nullable String notes) {
        if (notes == null) return null;
        if (notes.contains(SCHEMA_MIGRATIONS_FLAG + "true -->")) return true;
        if (notes.contains(SCHEMA_MIGRATIONS_FLAG + "false -->")) return false;
        return null;
    }

    /**
     * GitHub signals an exhausted primary or secondary limit as 429 or 403, with {@code Retry-After}
     * or {@code X-RateLimit-Remaining: 0} plus {@code X-RateLimit-Reset}; a 403 without either is an
     * ordinary refusal. Continuing to ask while limited risks a ban, so the wait is taken as named.
     */
    private Outcome failed(HttpStatusCode status, HttpHeaders headers) {
        Instant now = clock.instant();
        String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
        String reset =
                "0".equals(headers.getFirst("X-RateLimit-Remaining")) ? headers.getFirst("X-RateLimit-Reset") : null;
        boolean limited = status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)
                || (status.isSameCodeAs(HttpStatus.FORBIDDEN) && (retryAfter != null || reset != null));
        if (!limited) return new Failed(ReleaseCheckFailure.UNAVAILABLE, null);
        Instant retryAt = now;
        if (retryAfter != null) retryAt = later(retryAt, parseRetryAfter(retryAfter, now));
        if (reset != null) retryAt = later(retryAt, parseReset(reset, now));
        return new Failed(ReleaseCheckFailure.RATE_LIMITED, retryAt);
    }

    private static Instant later(Instant left, Instant right) {
        return right.isAfter(left) ? right : left;
    }

    private static Instant parseRetryAfter(String value, Instant now) {
        try {
            return now.plusSeconds(Long.parseLong(value));
        } catch (NumberFormatException notSeconds) {
            try {
                return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
            } catch (DateTimeException notDate) {
                return now.plus(UNPARSEABLE_WAIT);
            }
        }
    }

    private static Instant parseReset(String value, Instant now) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(value));
        } catch (NumberFormatException | DateTimeException exception) {
            return now.plus(UNPARSEABLE_WAIT);
        }
    }
}
