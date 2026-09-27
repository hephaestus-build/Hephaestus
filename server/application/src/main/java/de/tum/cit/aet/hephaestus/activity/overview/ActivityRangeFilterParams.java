package de.tum.cit.aet.hephaestus.activity.overview;

import io.swagger.v3.oas.annotations.Parameter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

public record ActivityRangeFilterParams(
        @Parameter(
                description = "Inclusive lower bound; defaults to seven days before to. A range spans at most "
                        + ActivityRangeFilterParams.MAX_DAYS
                        + " days.")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        @Nullable
        Instant from,

        @Parameter(description = "Exclusive upper bound; defaults to now")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        @Nullable
        Instant to) {

    static final int MAX_DAYS = 400;
    private static final Duration DEFAULT_WIDTH = Duration.ofDays(7);

    ActivityRangeFilterParams endingAt(Instant end) {
        return new ActivityRangeFilterParams(from, end);
    }

    /** The range with its defaults filled in; a backwards or too wide range is rejected. */
    ActivityRange toRange(Clock clock) {
        Instant end = to != null ? to : clock.instant();
        Instant start = from != null ? from : end.minus(DEFAULT_WIDTH);
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        if (Duration.between(start, end).compareTo(Duration.ofDays(MAX_DAYS)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A range spans at most " + MAX_DAYS + " days");
        }
        return new ActivityRange(start, end);
    }
}
