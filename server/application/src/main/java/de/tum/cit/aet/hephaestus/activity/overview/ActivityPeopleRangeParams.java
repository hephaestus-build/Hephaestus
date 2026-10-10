package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.core.time.TimeRange;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public record ActivityPeopleRangeParams(
        @Parameter(
                description = "Activity range",
                schema = @Schema(allowableValues = {"30d", "90d", "1y", "all", "custom"}))
        @Nullable
        String range,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable
        Instant from,

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable
        Instant to) {

    public TimeRange resolve(Clock clock, Instant earliest) {
        Instant end = to == null ? clock.instant() : to;
        String preset = range == null ? (from == null ? "90d" : "custom") : range;
        Instant start =
                switch (preset) {
                    case "30d" -> end.minus(Duration.ofDays(30));
                    case "90d" -> end.minus(Duration.ofDays(90));
                    case "1y" -> end.minus(Duration.ofDays(365));
                    case "all" -> earliest.isBefore(end) ? earliest : end;
                    case "custom" -> {
                        if (from == null) {
                            throw invalid("A custom range needs a start date.");
                        }
                        yield from;
                    }
                    default -> throw invalid("Select 30d, 90d, 1y, all, or custom.");
                };
        if (from != null && !preset.equals("custom")) {
            throw invalid("Use a custom range with a start date.");
        }
        if (start.isAfter(end)) {
            throw invalid("The start date must not be after the end date.");
        }
        return new TimeRange(start, end);
    }

    ActivityPeopleRangeParams endingAt(Instant end) {
        return new ActivityPeopleRangeParams(range, from, end);
    }

    private static ResponseStatusException invalid(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
