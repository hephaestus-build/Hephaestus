package de.tum.cit.aet.hephaestus.integration.slack.domain;

import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The Slack message timestamp (<em>ts</em>) value type: {@code <epoch-seconds>.<microseconds>}, always rendered with
 * a 10-digit second field and a 6-digit microsecond field.
 *
 * <p>Slack's {@code ts} is the identity of a message within a channel, so it is stored and compared verbatim as a
 * string. The fixed width makes lexicographic order equal numeric order, which is what the SQL watermark comparisons
 * ({@code last_ts}, {@code last_reviewed_ts}, {@code last_history_synced_ts}) rely on. Parsing a {@code ts} through
 * {@code double} would lose the microsecond digit at the far end of the range, so every comparison here is done on
 * the two integer fields.
 */
public final class SlackTs {

    private static final int MICROS_DIGITS = 6;
    private static final long MICROS_PER_SECOND = 1_000_000L;

    private SlackTs() {}

    /** Render an instant as a Slack {@code ts}, truncating to microsecond precision. */
    public static String ofInstant(Instant instant) {
        long micros = instant.getNano() / 1_000L;
        return String.format(Locale.ROOT, "%010d.%06d", instant.getEpochSecond(), micros);
    }

    /**
     * Whether {@code ts} denotes a moment strictly after {@code boundary}. An unparseable or blank {@code ts} is
     * {@code false} — every caller uses this as a fail-closed gate.
     */
    public static boolean isAfter(@Nullable String ts, Instant boundary) {
        Long micros = toEpochMicros(ts);
        return micros != null && micros > epochMicros(boundary);
    }

    /**
     * Compare two Slack timestamps as instants. Unparseable values sort before every parseable one, so a corrupt
     * watermark can never suppress ingestion of a real message.
     */
    public static int compare(@Nullable String left, @Nullable String right) {
        Long a = toEpochMicros(left);
        Long b = toEpochMicros(right);
        if (a == null || b == null) {
            return Boolean.compare(a != null, b != null);
        }
        return Long.compare(a, b);
    }

    /** The later of two timestamps, treating unparseable/absent as "earlier than anything". */
    public static @Nullable String max(@Nullable String left, @Nullable String right) {
        return compare(left, right) >= 0 ? left : right;
    }

    /** The instant a Slack {@code ts} denotes, or {@code null} when it is absent or malformed. */
    public static @Nullable Instant toInstant(@Nullable String ts) {
        Long micros = toEpochMicros(ts);
        return micros == null
                ? null
                : Instant.ofEpochSecond(micros / MICROS_PER_SECOND, (micros % MICROS_PER_SECOND) * 1_000L);
    }

    /** Epoch microseconds for a Slack {@code ts}, or {@code null} when it is absent or malformed. */
    public static @Nullable Long toEpochMicros(@Nullable String ts) {
        if (ts == null || ts.isBlank()) {
            return null;
        }
        int dot = ts.indexOf('.');
        String secondsPart = dot < 0 ? ts : ts.substring(0, dot);
        String microsPart = dot < 0 ? "" : ts.substring(dot + 1);
        if (microsPart.length() > MICROS_DIGITS) {
            return null;
        }
        try {
            long seconds = Long.parseLong(secondsPart);
            long micros = microsPart.isEmpty() ? 0L : Long.parseLong(microsPart);
            // "12.5" is 500000µs, not 5µs — Slack always sends 6 digits, but pad defensively.
            for (int i = microsPart.length(); i < MICROS_DIGITS; i++) {
                micros *= 10;
            }
            if (seconds < 0 || micros < 0) {
                return null;
            }
            return Math.addExact(Math.multiplyExact(seconds, MICROS_PER_SECOND), micros);
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    private static long epochMicros(Instant instant) {
        return instant.getEpochSecond() * MICROS_PER_SECOND + instant.getNano() / 1_000L;
    }
}
