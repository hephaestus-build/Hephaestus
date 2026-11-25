package de.tum.cit.aet.hephaestus.practices.usage;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import org.jspecify.annotations.NonNull;

/**
 * One released half-year of the public usage page.
 *
 * @param period the key of the period, such as {@code 2026-04}
 * @param firstDay the first day of the period, 1 April or 1 October
 * @param lastDay the last day of the period, 30 September or 31 March
 * @param releasedOn the day the server released the period, in UTC
 */
public record PublicUsagePeriodDTO(
        @NonNull @Schema(example = "2026-04") String period,
        @NonNull LocalDate firstDay,
        @NonNull LocalDate lastDay,
        @NonNull LocalDate releasedOn,
        @NonNull PublicUsageStatus status,
        @NonNull PublicUsageFiguresDTO figures) {}
