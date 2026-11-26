package de.tum.cit.aet.hephaestus.integration.core.spi;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderReply.BodyError;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderReply.Signal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Every provider response that a read can meet, and the one class that it has: a throttle is never a miss. */
@Tag("unit")
class ProviderReplyTest {

    private static final Instant NOW = Instant.parse("2026-11-26T03:00:00Z");
    private static final Instant RESET = NOW.plus(Duration.ofMinutes(40));
    private static final String RESET_EPOCH = Long.toString(RESET.getEpochSecond());

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

    static Stream<Arguments> responses() {
        return Stream.of(
                Arguments.of(
                        "GitHub 200, abuse detection under FORBIDDEN, healthy quota",
                        signal(
                                200,
                                Map.of("x-ratelimit-remaining", "4000"),
                                List.of(error("FORBIDDEN", "You have triggered an abuse detection mechanism."))),
                        new ProviderReply.Throttled(NOW.plus(ProviderReply.UNNAMED_WAIT))),
                Arguments.of(
                        "GitHub 200, GraphQL RATE_LIMITED",
                        signal(200, Map.of(), List.of(error("RATE_LIMITED", "API rate limit exceeded"))),
                        new ProviderReply.Throttled(NOW.plus(ProviderReply.UNNAMED_WAIT))),
                Arguments.of(
                        "GitHub 200, data null, quota spent, reset in 40 minutes",
                        signal(
                                200,
                                Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", RESET_EPOCH),
                                List.of(error(null, "Something went wrong"))),
                        new ProviderReply.Throttled(RESET)),
                Arguments.of(
                        "GitHub 200 secondary limit with Retry-After",
                        signal(
                                200,
                                Map.of("retry-after", "600", "x-ratelimit-remaining", "4000"),
                                List.of(error(null, "You have exceeded a secondary rate limit."))),
                        new ProviderReply.Throttled(NOW.plusSeconds(600))),
                Arguments.of(
                        "GitHub 403 with Retry-After only",
                        signal(403, Map.of("retry-after", "120", "x-ratelimit-remaining", "4000"), List.of()),
                        new ProviderReply.Throttled(NOW.plusSeconds(120))),
                Arguments.of(
                        "GitHub 403 with a spent quota and its reset",
                        signal(403, Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", RESET_EPOCH), List.of()),
                        new ProviderReply.Throttled(RESET)),
                Arguments.of(
                        "GitHub 403 secondary limit named only in the body",
                        refusal(403, Map.of("X-RateLimit-Remaining", "4000"), """
                                {"message":"You have exceeded a secondary rate limit."}
                                """),
                        new ProviderReply.Throttled(NOW.plus(ProviderReply.UNNAMED_WAIT))),
                Arguments.of(
                        "GitHub 429 with Retry-After and reset: the later deadline holds",
                        signal(
                                429,
                                Map.of(
                                        "retry-after",
                                        "60",
                                        "x-ratelimit-remaining",
                                        "0",
                                        "x-ratelimit-reset",
                                        RESET_EPOCH),
                                List.of()),
                        new ProviderReply.Throttled(RESET)),
                Arguments.of(
                        "GitHub 429 with primary quota available uses the secondary deadline",
                        signal(
                                429,
                                Map.of(
                                        "retry-after",
                                        "60",
                                        "x-ratelimit-remaining",
                                        "4000",
                                        "x-ratelimit-reset",
                                        RESET_EPOCH),
                                List.of()),
                        new ProviderReply.Throttled(NOW.plusSeconds(60))),
                Arguments.of(
                        "GitLab 429 with RateLimit headers",
                        signal(429, Map.of("ratelimit-remaining", "0", "ratelimit-reset", RESET_EPOCH), List.of()),
                        new ProviderReply.Throttled(RESET)),
                Arguments.of(
                        "GitLab 429 with Retry-After only",
                        signal(429, Map.of("retry-after", "30"), List.of()),
                        new ProviderReply.Throttled(NOW.plusSeconds(30))),
                Arguments.of(
                        "a healthy 403 refuses the credential",
                        refusal(403, Map.of("X-RateLimit-Remaining", "4000"), """
                                {"message":"Resource not accessible by integration"}
                                """),
                        new ProviderReply.AuthLost()),
                Arguments.of(
                        "a 401 refuses the credential", signal(401, Map.of(), List.of()), new ProviderReply.AuthLost()),
                Arguments.of("a real 404", refusal(404, Map.of(), ""), new ProviderReply.NotFound()),
                Arguments.of(
                        "a GraphQL NOT_FOUND of the whole read",
                        signal(200, Map.of(), List.of(error("NOT_FOUND", "Could not resolve to a Repository"))),
                        new ProviderReply.NotFound()),
                Arguments.of(
                        "a NOT_FOUND of one alias leaves the other fields an answer",
                        signal(200, Map.of(), List.of(new BodyError("NOT_FOUND", "Could not resolve", true))),
                        new ProviderReply.Ok()),
                Arguments.of(
                        "a NOT_FOUND before a rate limit does not hide the throttle",
                        signal(
                                200,
                                Map.of(),
                                List.of(
                                        new BodyError("NOT_FOUND", "Could not resolve", true),
                                        error("RATE_LIMITED", "API rate limit exceeded"))),
                        new ProviderReply.Throttled(NOW.plus(ProviderReply.UNNAMED_WAIT))),
                Arguments.of("a 502", signal(502, Map.of(), List.of()), new ProviderReply.Failed("HTTP 502")),
                Arguments.of(
                        "a 200 that spends the last of the quota is still an answer",
                        signal(200, Map.of("x-ratelimit-remaining", "0"), List.of()),
                        new ProviderReply.Ok()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("responses")
    void shouldClassifyEachProviderResponseOneWay(String name, Signal signal, ProviderReply expected) {
        assertThat(ProviderReply.classify(signal, NOW)).isEqualTo(expected);
    }
}
