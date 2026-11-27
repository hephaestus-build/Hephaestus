package de.tum.cit.aet.hephaestus.integration.core.spi;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderReply.BodyError;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderReply.Signal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Every provider response that a read can meet, and the one class that it has: a throttle is never a miss. */
@Tag("unit")
public class ProviderReplyTest {

    private static final Instant NOW = Instant.parse("2026-11-26T03:00:00Z");

    private static Signal signal(int status, Map<String, String> headers, List<BodyError> errors) {
        return new Signal(status, headers, errors, null, null);
    }

    private static BodyError error(@Nullable String type, String message) {
        return new BodyError(type, message, false);
    }

    private static Signal refusal(int status, Map<String, String> headers, String body) {
        var httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::set);
        return ProviderReply.signalOf(WebClientResponseException.create(
                status, "", httpHeaders, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    static Stream<ReplyCase> responses() {
        return responsesAt(NOW);
    }

    /** The same wire examples can run against a provider whose clock reads the current time. */
    public static Stream<ReplyCase> responsesAt(Instant now) {
        Instant reset = now.plus(Duration.ofMinutes(40));
        String resetEpoch = Long.toString(reset.getEpochSecond());
        return Stream.of(
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "GitHub 200, abuse detection under FORBIDDEN, healthy quota",
                        signal(
                                200,
                                Map.of("x-ratelimit-remaining", "4000"),
                                List.of(error("FORBIDDEN", "You have triggered an abuse detection mechanism."))),
                        new ProviderReply.Throttled(now.plus(ProviderReply.UNNAMED_WAIT))),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "GitHub 200, GraphQL RATE_LIMITED",
                        signal(200, Map.of(), List.of(error("RATE_LIMITED", "API rate limit exceeded"))),
                        new ProviderReply.Throttled(now.plus(ProviderReply.UNNAMED_WAIT))),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "GitHub 200, data null, quota spent, reset in 40 minutes",
                        signal(
                                200,
                                Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", resetEpoch),
                                List.of(error(null, "Something went wrong"))),
                        new ProviderReply.Throttled(reset)),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "GitHub 200 secondary limit with Retry-After",
                        signal(
                                200,
                                Map.of("retry-after", "600", "x-ratelimit-remaining", "4000"),
                                List.of(error(null, "You have exceeded a secondary rate limit."))),
                        new ProviderReply.Throttled(now.plusSeconds(600))),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "GitHub 403 with Retry-After only",
                        signal(403, Map.of("retry-after", "120", "x-ratelimit-remaining", "4000"), List.of()),
                        new ProviderReply.Throttled(now.plusSeconds(120))),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "GitHub 403 with a spent quota and its reset",
                        signal(403, Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", resetEpoch), List.of()),
                        new ProviderReply.Throttled(reset)),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "GitHub 403 secondary limit named only in the body",
                        refusal(403, Map.of("X-RateLimit-Remaining", "4000"), """
                                {"message":"You have exceeded a secondary rate limit."}
                                """),
                        new ProviderReply.Throttled(now.plus(ProviderReply.UNNAMED_WAIT))),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "GitHub 429 with Retry-After and reset: the later deadline holds",
                        signal(
                                429,
                                Map.of(
                                        "retry-after",
                                        "60",
                                        "x-ratelimit-remaining",
                                        "0",
                                        "x-ratelimit-reset",
                                        resetEpoch),
                                List.of()),
                        new ProviderReply.Throttled(reset)),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "GitHub 429 with primary quota available uses the secondary deadline",
                        signal(
                                429,
                                Map.of(
                                        "retry-after",
                                        "60",
                                        "x-ratelimit-remaining",
                                        "4000",
                                        "x-ratelimit-reset",
                                        resetEpoch),
                                List.of()),
                        new ProviderReply.Throttled(now.plusSeconds(60))),
                new ReplyCase(
                        IntegrationKind.GITLAB,
                        "GitLab 429 with RateLimit headers",
                        signal(429, Map.of("ratelimit-remaining", "0", "ratelimit-reset", resetEpoch), List.of()),
                        new ProviderReply.Throttled(reset)),
                new ReplyCase(
                        IntegrationKind.GITLAB,
                        "GitLab 429 with Retry-After only",
                        signal(429, Map.of("retry-after", "30"), List.of()),
                        new ProviderReply.Throttled(now.plusSeconds(30))),
                new ReplyCase(
                        IntegrationKind.GITLAB,
                        "GitLab 429 with a Retry-After HTTP date",
                        signal(
                                429,
                                Map.of(
                                        "retry-after",
                                        DateTimeFormatter.RFC_1123_DATE_TIME.format(
                                                now.plus(Duration.ofHours(3)).atOffset(ZoneOffset.UTC))),
                                List.of()),
                        new ProviderReply.Throttled(now.plus(Duration.ofHours(3)))),
                new ReplyCase(
                        IntegrationKind.GITLAB,
                        "GitLab 429 with a delay beyond the timestamp range",
                        signal(429, Map.of("retry-after", Long.toString(Long.MAX_VALUE)), List.of()),
                        new ProviderReply.Throttled(Instant.MAX)),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "a healthy 403 refuses the credential",
                        refusal(403, Map.of("X-RateLimit-Remaining", "4000"), """
                                {"message":"Resource not accessible by integration"}
                                """),
                        new ProviderReply.AuthLost()),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "a 401 refuses the credential",
                        signal(401, Map.of(), List.of()),
                        new ProviderReply.AuthLost()),
                new ReplyCase(
                        IntegrationKind.GITHUB, "a real 404", refusal(404, Map.of(), ""), new ProviderReply.NotFound()),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "a GraphQL NOT_FOUND of the whole read",
                        signal(200, Map.of(), List.of(error("NOT_FOUND", "Could not resolve to a Repository"))),
                        new ProviderReply.NotFound()),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "a NOT_FOUND of one alias leaves the other fields an answer",
                        signal(200, Map.of(), List.of(new BodyError("NOT_FOUND", "Could not resolve", true))),
                        new ProviderReply.Ok()),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "a NOT_FOUND before a rate limit does not hide the throttle",
                        signal(
                                200,
                                Map.of(),
                                List.of(
                                        new BodyError("NOT_FOUND", "Could not resolve", true),
                                        error("RATE_LIMITED", "API rate limit exceeded"))),
                        new ProviderReply.Throttled(now.plus(ProviderReply.UNNAMED_WAIT))),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "a 502",
                        signal(502, Map.of(), List.of()),
                        new ProviderReply.Failed("HTTP 502")),
                new ReplyCase(
                        IntegrationKind.GITHUB,
                        "a 200 that spends the last of the quota is still an answer",
                        signal(200, Map.of("x-ratelimit-remaining", "0"), List.of()),
                        new ProviderReply.Ok()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("responses")
    void shouldClassifyEachProviderResponseOneWay(ReplyCase reply) {
        assertThat(ProviderReply.classify(reply.signal(), NOW)).isEqualTo(reply.expected());
    }

    public record ReplyCase(IntegrationKind kind, String name, Signal signal, ProviderReply expected) {
        @Override
        public String toString() {
            return name;
        }
    }
}
