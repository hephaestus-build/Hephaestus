package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class RetryAfterTest extends BaseUnitTest {

    private static final Instant NOW = Instant.parse("2026-10-26T22:00:00Z");

    @Test
    void shouldReadSecondsWhenRetryAfterIsANumber() {
        assertThat(RetryAfter.parse(headers(HttpHeaders.RETRY_AFTER, "7"), NOW)).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void shouldReadAnHttpDateWhenRetryAfterIsADate() {
        assertThat(RetryAfter.parse(headers(HttpHeaders.RETRY_AFTER, "Mon, 26 Oct 2026 22:00:30 GMT"), NOW))
                .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void shouldPreferMillisecondsWhenBothHeadersArePresent() {
        HttpHeaders headers = headers(HttpHeaders.RETRY_AFTER, "2");
        headers.set(RetryAfter.RETRY_AFTER_MS, "1500");

        assertThat(RetryAfter.parse(headers, NOW)).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void shouldReadNothingWhenTheValueIsPastZeroOrUnreadable() {
        assertThat(RetryAfter.parse(new HttpHeaders(), NOW)).isNull();
        assertThat(RetryAfter.parse(headers(HttpHeaders.RETRY_AFTER, "0"), NOW)).isNull();
        assertThat(RetryAfter.parse(headers(HttpHeaders.RETRY_AFTER, "-3"), NOW))
                .isNull();
        assertThat(RetryAfter.parse(headers(HttpHeaders.RETRY_AFTER, "soon"), NOW))
                .isNull();
        assertThat(RetryAfter.parse(headers(HttpHeaders.RETRY_AFTER, "Mon, 26 Oct 2026 21:59:00 GMT"), NOW))
                .isNull();
        assertThat(RetryAfter.parse(headers(RetryAfter.RETRY_AFTER_MS, "NaN"), NOW))
                .isNull();
    }

    @Test
    void shouldBoundAHugeValueWhenItWouldOverflow() {
        assertThat(RetryAfter.parse(headers(HttpHeaders.RETRY_AFTER, "1e300"), NOW))
                .isEqualTo(Duration.ofDays(1));
    }

    private static HttpHeaders headers(String name, String value) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(name, value);
        return headers;
    }
}
