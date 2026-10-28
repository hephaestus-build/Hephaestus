package de.tum.cit.aet.hephaestus.agent.proxy;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/** The pause a provider's rate limit answer asks for. */
final class RetryAfter {

    /** OpenAI and Azure OpenAI send it beside {@code Retry-After}, in milliseconds. */
    static final String RETRY_AFTER_MS = "retry-after-ms";

    private RetryAfter() {}

    /**
     * Reads {@code retry-after-ms}, else {@code Retry-After} as seconds or as an HTTP date (RFC 9110 § 10.2.3).
     *
     * @return {@code null} when neither header holds a valid, positive pause
     */
    static @Nullable Duration parse(HttpHeaders headers, Instant now) {
        Duration millis = positive(number(headers.getFirst(RETRY_AFTER_MS)), false);
        if (millis != null) return millis;
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) return null;
        Duration seconds = positive(number(value), true);
        if (seconds != null) return seconds;
        try {
            Duration until = Duration.between(
                    now,
                    ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant());
            return until.isNegative() || until.isZero() ? null : until;
        } catch (DateTimeParseException notADate) {
            return null;
        }
    }

    private static @Nullable Double number(@Nullable String value) {
        if (value == null) return null;
        try {
            double parsed = Double.parseDouble(value.strip());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static @Nullable Duration positive(@Nullable Double value, boolean seconds) {
        if (value == null || value <= 0) return null;
        // Larger than any cap; keeps the conversion below from overflowing.
        double millis = Math.min(
                seconds ? value * 1000 : value, (double) Duration.ofDays(1).toMillis());
        return Duration.ofMillis((long) Math.ceil(millis));
    }
}
