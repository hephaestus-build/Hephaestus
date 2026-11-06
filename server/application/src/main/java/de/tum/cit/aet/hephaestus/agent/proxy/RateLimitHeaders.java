package de.tum.cit.aet.hephaestus.agent.proxy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;

/**
 * What a provider's rate limit headers say about its buckets, before a call gets a 429. A provider that sends none, or
 * values that this class cannot read, says nothing.
 *
 * <p>Both families name when a bucket is <em>full</em> again, and a bucket refills all the time. So a pause lasts for a
 * second's refill of a minute's bucket, not until that time. An empty bucket is the only signal: a pool's token bound
 * covers input and output together, so no single bucket can be held to it.
 */
final class RateLimitHeaders {

    private static final int SHARE_OF_THE_REFILL = 60;

    private static final Pattern DURATION = Pattern.compile(
            "(?:(\\d+(?:\\.\\d+)?)h)?(?:(\\d+(?:\\.\\d+)?)m)?(?:(\\d+(?:\\.\\d+)?)s)?(?:(\\d+(?:\\.\\d+)?)ms)?");

    /**
     * The longest refill this class believes, so that no pause passes ten seconds. A gateway can use these names with
     * another meaning, such as an epoch in the reset header, and a pause stops every caller of the pool.
     */
    private static final Duration LONGEST = Duration.ofMinutes(10);

    private static final double LONGEST_MILLIS = 600_000;

    /** One bucket of a provider family: its two headers, and the tag it has on the meters. */
    private record Bucket(String tag, String remaining, String reset) {
        private static Bucket anthropic(String tag, String name) {
            String prefix = "anthropic-ratelimit-" + name;
            return new Bucket(tag, prefix + "-remaining", prefix + "-reset");
        }

        private static Bucket openAi(String tag, String name) {
            return new Bucket(tag, "x-ratelimit-remaining-" + name, "x-ratelimit-reset-" + name);
        }
    }

    private static final List<Bucket> BUCKETS = List.of(
            Bucket.anthropic("requests", "requests"),
            Bucket.anthropic("tokens", "tokens"),
            Bucket.anthropic("input_tokens", "input-tokens"),
            Bucket.anthropic("output_tokens", "output-tokens"),
            Bucket.openAi("requests", "requests"),
            Bucket.openAi("tokens", "tokens"));

    /** Every tag that {@link Reached#bucket()} can hold. */
    static final List<String> TAGS = List.of("requests", "tokens", "input_tokens", "output_tokens");

    private RateLimitHeaders() {}

    /**
     * An empty bucket, and how long callers pause for it.
     *
     * @param bucket one of {@link #TAGS}
     */
    record Reached(String bucket, Duration pause) {}

    /**
     * The empty bucket that needs the longest pause.
     *
     * @return {@code null} when no bucket that the headers name is empty
     */
    static @Nullable Reached reached(HttpHeaders headers, Instant now) {
        Reached longest = null;
        for (Bucket bucket : BUCKETS) {
            Reached reached = reached(bucket, headers, now);
            if (reached != null && (longest == null || reached.pause().compareTo(longest.pause()) > 0))
                longest = reached;
        }
        return longest;
    }

    private static @Nullable Reached reached(Bucket bucket, HttpHeaders headers, Instant now) {
        Long remaining = count(headers.getFirst(bucket.remaining()));
        Duration full = untilFull(headers.getFirst(bucket.reset()), now);
        if (remaining == null || remaining > 0 || full == null) return null;
        long millis = Math.max(1, full.toMillis() / SHARE_OF_THE_REFILL);
        return new Reached(bucket.tag(), Duration.ofMillis(millis));
    }

    private static @Nullable Long count(@Nullable String value) {
        if (value == null) return null;
        try {
            long parsed = Long.parseLong(value.strip());
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException notACount) {
            return null;
        }
    }

    /** Anthropic sends an RFC 3339 time, OpenAI a duration such as {@code 6m0s}, {@code 12ms} or {@code 1.5s}. */
    private static @Nullable Duration untilFull(@Nullable String value, Instant now) {
        if (value == null || value.isBlank()) return null;
        String reset = value.strip();
        Duration until = duration(reset);
        if (until == null) {
            try {
                until = Duration.between(now, OffsetDateTime.parse(reset).toInstant());
            } catch (DateTimeParseException notATime) {
                return null;
            }
        }
        if (until.isNegative() || until.isZero()) return null;
        return until.compareTo(LONGEST) > 0 ? LONGEST : until;
    }

    private static @Nullable Duration duration(String value) {
        Matcher matcher = DURATION.matcher(value);
        if (!matcher.matches()) return seconds(value);
        double millis = part(matcher.group(1), 3_600_000)
                + part(matcher.group(2), 60_000)
                + part(matcher.group(3), 1_000)
                + part(matcher.group(4), 1);
        return Duration.ofMillis((long) Math.min(Math.ceil(millis), LONGEST_MILLIS));
    }

    private static double part(@Nullable String value, long millis) {
        return value == null ? 0 : Double.parseDouble(value) * millis;
    }

    /** A bare number, which OpenAI has sent as well. */
    private static @Nullable Duration seconds(String value) {
        try {
            double seconds = Double.parseDouble(value);
            if (!Double.isFinite(seconds)) return null;
            return Duration.ofMillis((long) Math.ceil(Math.min(seconds * 1000, LONGEST_MILLIS)));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
