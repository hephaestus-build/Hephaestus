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
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * One unauthenticated, conditional GET for the newest published release, on its own client so no
 * workspace credential can reach it. The request carries nothing about the instance beyond the static
 * user agent; {@code docs/admin/install.mdx} promises operators exactly that.
 */
@Component
@ConditionalOnServerRole
public class ReleaseCheckClient {
    private static final String REPOSITORY = "hephaestus-build/Hephaestus";

    /** Written into every release's notes by {@code .github/workflows/release.yml}; older releases have none. */
    private static final String SCHEMA_MIGRATIONS_FLAG = "<!-- hephaestus:schema-migrations=";

    private static final Duration UNPARSEABLE_WAIT = Duration.ofHours(24);

    public sealed interface Outcome permits Found, NotModified, Failed {}

    public record Found(LatestReleaseDTO release, @Nullable String etag) implements Outcome {}

    public record NotModified() implements Outcome {}

    /** {@code retryAt} is the wait GitHub named, present only on a rate limit. */
    public record Failed(
            ReleaseCheckFailure reason, @Nullable Instant retryAt) implements Outcome {}

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
        this(RestClient.builder().requestFactory(isolatedRequestFactory()), clock);
    }

    ReleaseCheckClient(RestClient.Builder builder, Clock clock) {
        this.clock = clock;
        client = builder.baseUrl("https://api.github.com/repos/" + REPOSITORY + "/releases/latest")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader(HttpHeaders.USER_AGENT, "Hephaestus-release-check")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    private static ClientHttpRequestFactory isolatedRequestFactory() {
        return ClientHttpRequestFactoryBuilder.jdk()
                .build(HttpClientSettings.defaults()
                        .withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(10))
                        .withRedirects(HttpRedirects.DONT_FOLLOW));
    }

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
                        "https://github.com/" + REPOSITORY + "/releases/tag/" + tag,
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
     * GitHub signals an exhausted limit as 429 or 403 with {@code Retry-After} or
     * {@code X-RateLimit-Remaining: 0} plus {@code X-RateLimit-Reset}; a 403 without either is an
     * ordinary refusal. Asking again inside the window risks a ban, so the wait is taken as named.
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

    /** Seconds or an HTTP date per RFC 9110; anything else earns the longest wait rather than a retry. */
    private static Instant parseRetryAfter(String value, Instant now) {
        try {
            return value.chars().allMatch(Character::isDigit)
                    ? now.plusSeconds(Long.parseLong(value))
                    : ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant();
        } catch (NumberFormatException | DateTimeException exception) {
            return now.plus(UNPARSEABLE_WAIT);
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
