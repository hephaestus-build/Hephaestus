package de.tum.cit.aet.hephaestus.core.settings.spi;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One half-year of the public usage page (ADR 0153): 1 October to 31 March, or 1 April to 30 September, in UTC. Two
 * periods never overlap, because a period can only start on 1 April or 1 October and always lasts six months.
 *
 * @param start the first day of the period, 1 April or 1 October
 */
public record UsagePeriod(LocalDate start) implements Comparable<UsagePeriod> {

    /** How long after its end a period is released. */
    public static final Duration RELEASE_DELAY = Duration.ofDays(14);

    private static final Pattern KEY = Pattern.compile("(\\d{4})-(04|10)");

    public UsagePeriod {
        if (start.getDayOfMonth() != 1 || (start.getMonth() != Month.APRIL && start.getMonth() != Month.OCTOBER)) {
            throw new IllegalArgumentException("A usage period starts on 1 April or 1 October, not " + start);
        }
    }

    /** The period that holds this instant. */
    public static UsagePeriod containing(Instant instant) {
        LocalDate day = LocalDate.ofInstant(instant, ZoneOffset.UTC);
        int month = day.getMonthValue();
        if (month >= 4 && month <= 9) {
            return new UsagePeriod(LocalDate.of(day.getYear(), 4, 1));
        }
        return new UsagePeriod(LocalDate.of(month >= 10 ? day.getYear() : day.getYear() - 1, 10, 1));
    }

    /** The newest period whose release time has come, at this instant. */
    public static UsagePeriod latestReleasable(Instant now) {
        UsagePeriod period = containing(now).previous();
        return period.releaseAt().isAfter(now) ? period.previous() : period;
    }

    /** Reads a key such as {@code 2026-04}, or nothing when the text names no period. */
    public static Optional<UsagePeriod> parse(@Nullable String key) {
        if (key == null) {
            return Optional.empty();
        }
        var match = KEY.matcher(key);
        if (!match.matches()) {
            return Optional.empty();
        }
        return Optional.of(
                new UsagePeriod(LocalDate.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), 1)));
    }

    /** The key of the period in a route and in storage, such as {@code 2026-04}. */
    public String key() {
        // Locale.ROOT: a stored withdrawal and the SQL period key are ASCII digits, whatever the JVM locale.
        return String.format(Locale.ROOT, "%04d-%02d", start.getYear(), start.getMonthValue());
    }

    /** The first day after the period. */
    public LocalDate end() {
        return start.plusMonths(6);
    }

    /** The last day of the period. */
    public LocalDate lastDay() {
        return end().minusDays(1);
    }

    public Instant startInstant() {
        return start.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public Instant endInstant() {
        return end().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** When the period is released: 14 days after it ends. */
    public Instant releaseAt() {
        return endInstant().plus(RELEASE_DELAY);
    }

    public UsagePeriod next() {
        return new UsagePeriod(end());
    }

    public UsagePeriod previous() {
        return new UsagePeriod(start.minusMonths(6));
    }

    @Override
    public int compareTo(UsagePeriod other) {
        return start.compareTo(other.start);
    }
}
