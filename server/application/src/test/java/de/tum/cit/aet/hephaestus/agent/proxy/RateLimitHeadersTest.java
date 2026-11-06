package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;

class RateLimitHeadersTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-11-06T12:00:00Z");

    private static HttpHeaders headers(String... pairs) {
        var headers = new HttpHeaders();
        for (int i = 0; i < pairs.length; i += 2) headers.add(pairs[i], pairs[i + 1]);
        return headers;
    }

    private static RateLimitHeaders.Reached reached(HttpHeaders headers) {
        return Objects.requireNonNull(RateLimitHeaders.reached(headers, NOW));
    }

    @Test
    void shouldPauseAnEmptyAnthropicBucketForASecondOfAMinutesRefill() {
        var answer = headers(
                "anthropic-ratelimit-input-tokens-remaining", "0",
                "anthropic-ratelimit-input-tokens-reset", "2026-11-06T12:01:00Z");

        var reached = reached(answer);

        assertThat(reached.bucket()).isEqualTo("input_tokens");
        assertThat(reached.pause()).isEqualTo(Duration.ofSeconds(1));
    }

    @ParameterizedTest
    @CsvSource({"6m0s, 6000", "1s, 16", "12ms, 1", "59.70, 995"})
    void shouldReadTheResetFormsOfOpenAi(String reset, long pauseMillis) {
        var answer = headers("x-ratelimit-remaining-requests", "0", "x-ratelimit-reset-requests", reset);

        var reached = reached(answer);

        assertThat(reached.bucket()).isEqualTo("requests");
        assertThat(reached.pause().toMillis()).isEqualTo(pauseMillis);
    }

    @ParameterizedTest
    @CsvSource({"1h2m3.5s", "1793965613", "5d"})
    void shouldNeverPauseForLongerThanTenSeconds(String reset) {
        var answer = headers("x-ratelimit-remaining-requests", "0", "x-ratelimit-reset-requests", reset);

        assertThat(reached(answer).pause()).isLessThanOrEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void shouldNameTheBucketThatNeedsTheLongestPause() {
        var answer = headers(
                "anthropic-ratelimit-requests-remaining", "0",
                "anthropic-ratelimit-requests-reset", "2026-11-06T12:00:30Z",
                "anthropic-ratelimit-output-tokens-remaining", "0",
                "anthropic-ratelimit-output-tokens-reset", "2026-11-06T12:01:00Z");

        assertThat(reached(answer).bucket()).isEqualTo("output_tokens");
    }

    @ParameterizedTest
    @CsvSource({
        "1, 60s", // room left, however small
        "0, soon", // an unreadable reset
        "none, 60s", // an unreadable count
        "-1, 60s", // a count that no provider sends
        "0, 2026-11-06T11:59:00Z", // a reset in the past
    })
    void shouldSayNothingWithoutAReadableEmptyBucket(String remaining, String reset) {
        var answer = headers("x-ratelimit-remaining-requests", remaining, "x-ratelimit-reset-requests", reset);

        assertThat(RateLimitHeaders.reached(answer, NOW)).isNull();
    }

    @Test
    void shouldSayNothingWithoutHeaders() {
        assertThat(RateLimitHeaders.reached(new HttpHeaders(), NOW)).isNull();
    }
}
