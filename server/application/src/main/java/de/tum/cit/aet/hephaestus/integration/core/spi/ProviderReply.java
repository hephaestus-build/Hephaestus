package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * What one provider response means for the work that asked: the one classification of every provider's status, headers,
 * body errors and quota. A caller decides by the class, never by a status code. A throttle is a wait until the
 * provider's own deadline, never a miss: GitHub and GitLab name it by status, by header, or only in the body.
 */
public sealed interface ProviderReply {
    /** The provider answered. Errors under one aliased field, if any, leave only those fields unanswered. */
    record Ok() implements ProviderReply {}

    /** The provider says that the read resource does not exist. */
    record NotFound() implements ProviderReply {}

    /** The provider limits further reads with this credential until {@code until}. */
    record Throttled(Instant until) implements ProviderReply {}

    /** The provider refuses the credential itself. */
    record AuthLost() implements ProviderReply {}

    /** Any other failure. {@code reason} names its kind only, never provider text. */
    record Failed(String reason) implements ProviderReply {}

    /**
     * What the transport saw of one response. A filter of the provider's client fills it, so an answered request is
     * classified with its headers too.
     */
    final class Exchange {
        private volatile int status;
        private volatile Map<String, String> headers = Map.of();

        public void record(int status, Map<String, String> headers) {
            this.status = status;
            this.headers = Map.copyOf(headers);
        }

        /** The HTTP status, or 0 when no response arrived. */
        public int status() {
            return status;
        }

        public Map<String, String> headers() {
            return headers;
        }
    }

    /**
     * The provider's HTTP refusal behind a failed read, or null. The GraphQL client wraps it in its transport exception,
     * so the classification looks through the causes.
     */
    static @Nullable WebClientResponseException refusalOf(Throwable failure) {
        // A bounded walk: a cause chain can loop.
        Throwable cause = failure;
        int depth = 0;
        while (cause != null && depth++ < 16) {
            if (cause instanceof WebClientResponseException refusal) return refusal;
            cause = cause.getCause();
        }
        return null;
    }

    /** An HTTP 403 can name a throttle only in its error body, with no GraphQL envelope. */
    static Signal signalOf(WebClientResponseException refusal) {
        int status = refusal.getStatusCode().value();
        List<BodyError> errors =
                status == 403 ? List.of(new BodyError(null, refusal.getResponseBodyAsString(), false)) : List.of();
        return new Signal(status, headersOf(refusal.getHeaders()), errors, null, null);
    }

    /** The first value of each header, by its name in lower case, as a {@link Signal} holds them. */
    static Map<String, String> headersOf(HttpHeaders headers) {
        Map<String, String> lower = new HashMap<>();
        headers.forEach((name, values) -> {
            if (!values.isEmpty()) lower.putIfAbsent(name.toLowerCase(Locale.ROOT), values.getFirst());
        });
        return lower;
    }

    /** The wait for a throttle that names no deadline: GitHub asks for at least a minute after a secondary limit. */
    Duration UNNAMED_WAIT = Duration.ofMinutes(1);

    /**
     * One response as the transport and the body show it.
     *
     * @param status the HTTP status
     * @param headers the response headers, names in lower case, first value each
     * @param errors the body's GraphQL errors, empty for none
     * @param remaining the quota that the body reports left, or null
     * @param resetAt when the body says the quota resets, or null
     */
    record Signal(
            int status,
            Map<String, String> headers,
            List<BodyError> errors,
            @Nullable Integer remaining,
            @Nullable Instant resetAt) {
        public Signal {
            headers = Map.copyOf(headers);
            errors = List.copyOf(errors);
        }
    }

    /**
     * One GraphQL error of a body.
     *
     * @param underAlias whether its path names one aliased field, which spoils only that field
     */
    record BodyError(@Nullable String type, @Nullable String message, boolean underAlias) {}

    static ProviderReply classify(Signal signal, Instant now) {
        Integer remainingHeader = integer(first(signal.headers(), "x-ratelimit-remaining", "ratelimit-remaining"));
        Instant resetHeader = epoch(first(signal.headers(), "x-ratelimit-reset", "ratelimit-reset"));
        boolean primaryQuotaAvailable =
                signal.headers().containsKey("x-ratelimit-reset") && remainingHeader != null && remainingHeader > 0;
        Instant retryAfter = retryAfter(signal.headers().get("retry-after"), now);
        boolean exhausted = (remainingHeader != null && remainingHeader <= 0)
                || (signal.remaining() != null && signal.remaining() <= 0);
        boolean throttleInBody = signal.errors().stream().anyMatch(ProviderReply::isThrottle);
        int status = signal.status();
        boolean throttled = status == 429
                || throttleInBody
                || (status == 403 && (retryAfter != null || exhausted))
                || (status / 100 == 2 && !signal.errors().isEmpty() && exhausted);
        if (throttled) {
            List<Instant> deadlines = new ArrayList<>();
            if (retryAfter != null) deadlines.add(retryAfter);
            // The quota's reset applies when the quota is spent. A secondary limit leaves it, so its reset can be an
            // hour away from the real wait.
            if (exhausted || (status == 429 && !primaryQuotaAvailable)) {
                Instant reset = resetHeader != null ? resetHeader : signal.resetAt();
                if (reset != null) deadlines.add(reset);
            }
            Instant until = deadlines.stream().max(Instant::compareTo).orElse(now.plus(UNNAMED_WAIT));
            return new Throttled(until.isAfter(now) ? until : now.plus(UNNAMED_WAIT));
        }
        if (status == 401 || status == 403) return new AuthLost();
        if (status == 404) return new NotFound();
        if (status / 100 != 2) return new Failed("HTTP " + status);
        for (BodyError error : signal.errors()) {
            if (error.underAlias()) continue;
            String type = error.type() == null ? "" : error.type();
            return switch (type) {
                case "NOT_FOUND" -> new NotFound();
                case "UNAUTHORIZED", "FORBIDDEN" -> new AuthLost();
                default -> new Failed("GraphQL " + (type.matches("[A-Z_]{1,64}") ? type : "error"));
            };
        }
        return new Ok();
    }

    /** A rate limit, a secondary limit or GitHub's abuse detection, by its type or by its text, whatever its type. */
    private static boolean isThrottle(BodyError error) {
        if ("RATE_LIMIT".equals(error.type()) || "RATE_LIMITED".equals(error.type())) return true;
        String text = error.message() == null ? "" : error.message().toLowerCase(Locale.ROOT);
        return text.contains("rate limit")
                || text.contains("ratelimit")
                || text.contains("secondary rate")
                || text.contains("abuse detection");
    }

    private static @Nullable String first(Map<String, String> headers, String... names) {
        for (String name : names) {
            String value = headers.get(name);
            if (value != null) return value;
        }
        return null;
    }

    private static @Nullable Integer integer(@Nullable String value) {
        try {
            return value == null ? null : Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable Long seconds(@Nullable String value) {
        try {
            return value == null ? null : Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable Instant epoch(@Nullable String value) {
        Long seconds = seconds(value);
        if (seconds == null || seconds < Instant.MIN.getEpochSecond()) return null;
        return Instant.ofEpochSecond(Math.min(seconds, Instant.MAX.getEpochSecond()));
    }

    private static @Nullable Instant retryAfter(@Nullable String value, Instant now) {
        if (value == null) return null;
        String header = value.trim();
        if (header.matches("[0-9]+")) {
            Long seconds = seconds(header);
            long available = Instant.MAX.getEpochSecond() - now.getEpochSecond();
            return seconds == null || seconds > available ? Instant.MAX : now.plusSeconds(seconds);
        }
        try {
            return DateTimeFormatter.RFC_1123_DATE_TIME.parse(header, Instant::from);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
