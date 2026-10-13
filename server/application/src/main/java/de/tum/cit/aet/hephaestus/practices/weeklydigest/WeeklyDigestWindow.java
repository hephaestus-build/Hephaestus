package de.tum.cit.aet.hephaestus.practices.weeklydigest;

import de.tum.cit.aet.hephaestus.practices.profile.OverviewWindow;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/**
 * One local calendar week of a developer: after Monday 00:00, at or before the next Monday 00:00, in their time zone.
 * In one time zone, weeks tile without a gap or an overlap, so what one digest lists the next one never lists again.
 *
 * @param weekStart the Monday the week starts on
 */
public record WeeklyDigestWindow(LocalDate weekStart, ZoneId zone) {

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    public WeeklyDigestWindow {
        if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("A weekly digest week starts on a Monday, not " + weekStart);
        }
    }

    /** The week before the one {@code now} falls in, in {@code zone}. */
    public static WeeklyDigestWindow previousWeek(Instant now, ZoneId zone) {
        LocalDate thisMonday = LocalDate.ofInstant(now, zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return new WeeklyDigestWindow(thisMonday.minusWeeks(1), zone);
    }

    public Instant start() {
        return weekStart.atStartOfDay(zone).toInstant();
    }

    public Instant end() {
        return weekStart.plusWeeks(1).atStartOfDay(zone).toInstant();
    }

    /** The Sunday the week ends on. */
    public LocalDate weekEnd() {
        return weekStart.plusDays(6);
    }

    /** The week's days as notes name them: "5–11 October", or "29 September – 5 October" across two months. */
    public String days() {
        LocalDate last = weekEnd();
        if (weekStart.getMonth() == last.getMonth()) {
            return weekStart.getDayOfMonth() + "–" + DAY_MONTH.format(last);
        }
        return DAY_MONTH.format(weekStart) + " – " + DAY_MONTH.format(last);
    }

    /** {@link #days()} with the year: "5–11 October 2026", or "29 December 2025 – 4 January 2026". */
    public String daysWithYear() {
        LocalDate last = weekEnd();
        if (weekStart.getYear() == last.getYear()) {
            return days() + " " + last.getYear();
        }
        return DAY_MONTH_YEAR.format(weekStart) + " – " + DAY_MONTH_YEAR.format(last);
    }

    /** The same span as the Practice profile reads change over, so "in the window" means one thing. */
    public OverviewWindow overview() {
        return new OverviewWindow(start(), end());
    }
}
