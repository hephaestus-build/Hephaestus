package de.tum.cit.aet.hephaestus.core.settings.spi;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Whether the public usage page is on, and what an instance admin withdrew from it (ADR 0153 decisions 11 and 14).
 *
 * @param allowed whether the page answers at all
 * @param withdrawn each withdrawal, as stored: a period key such as {@code 2026-04}, or a period key and a figure
 *     such as {@code 2026-04/REVIEWED_PEOPLE}
 * @param unreadable whether a stored withdrawal could not be read; then every figure is hidden, so a withdrawal never
 *     fails open
 */
public record PublicUsageState(boolean allowed, Set<String> withdrawn, boolean unreadable) {

    private static final char FIGURE_SEPARATOR = '/';

    public PublicUsageState {
        withdrawn = Set.copyOf(withdrawn);
    }

    /**
     * Reads the stored withdrawals. One that names no period or no figure, or is no text at all, hides everything; the
     * readable ones stay listed.
     */
    public static PublicUsageState of(boolean allowed, Collection<? extends @Nullable String> stored) {
        var readable = new HashSet<String>();
        boolean unreadable = false;
        for (@Nullable String entry : stored) {
            if (entry != null && parse(entry).isPresent()) {
                readable.add(entry);
            } else {
                unreadable = true;
            }
        }
        return new PublicUsageState(allowed, readable, unreadable);
    }

    /** The stored form of a withdrawal of a whole period, or of one of its figures. */
    public static String entry(UsagePeriod period, @Nullable UsageFigure figure) {
        return figure == null ? period.key() : period.key() + FIGURE_SEPARATOR + figure.name();
    }

    /** Whether the whole period is withdrawn. */
    public boolean hides(UsagePeriod period) {
        return unreadable || withdrawn.contains(entry(period, null));
    }

    /** Whether this figure of the period is withdrawn, alone or with its period. */
    public boolean hides(UsagePeriod period, UsageFigure figure) {
        return hides(period) || withdrawn.contains(entry(period, figure));
    }

    /** A stored withdrawal as its period and figure; no figure means the whole period. */
    public record Withdrawal(UsagePeriod period, @Nullable UsageFigure figure) {}

    public static Optional<Withdrawal> parse(String entry) {
        int separator = entry.indexOf(FIGURE_SEPARATOR);
        if (separator < 0) {
            return UsagePeriod.parse(entry).map(period -> new Withdrawal(period, null));
        }
        var figureName = entry.substring(separator + 1);
        var figure = Arrays.stream(UsageFigure.values())
                .filter(candidate -> candidate.name().equals(figureName))
                .findFirst();
        return UsagePeriod.parse(entry.substring(0, separator))
                .flatMap(period -> figure.map(value -> new Withdrawal(period, value)));
    }
}
