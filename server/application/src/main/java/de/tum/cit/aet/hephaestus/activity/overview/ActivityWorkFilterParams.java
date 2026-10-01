package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.core.time.TimeRange;
import de.tum.cit.aet.hephaestus.core.time.TimeRangeFilterParams;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Clock;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.web.bind.annotation.RequestParam;

public record ActivityWorkFilterParams(
        @Parameter(description = "Kinds of activity to list (repeatable); omit for every kind")
        @RequestParam(required = false)
        @Nullable
        Set<ActivityKind> kinds,

        @Parameter(description = "The previous page's nextCursor; omit for the first page")
        @RequestParam(required = false)
        @Nullable
        String cursor,

        @Parameter(description = "Page size from 1 to 100; defaults to 30")
        @RequestParam(required = false)
        @Min(1)
        @Max(100)
        @Nullable
        Integer size) {

    private static final int DEFAULT_PAGE_SIZE = 30;

    Set<ActivityEventType> eventTypes() {
        return ActivityKind.eventTypes(kinds == null ? Set.of() : kinds);
    }

    /** The range the page is read in: a later page keeps the end the first page had. */
    TimeRange range(TimeRangeFilterParams range, Clock clock) {
        return (cursor == null
                        ? range
                        : range.endingAt(ActivityWorkCursor.decode(cursor).to()))
                .toRange(clock);
    }

    /** Where the page starts; an unreadable cursor is rejected. */
    ActivityWorkCursor position(TimeRange range) {
        return cursor == null ? ActivityWorkCursor.startOf(range) : ActivityWorkCursor.decode(cursor);
    }

    int pageSize() {
        return size == null ? DEFAULT_PAGE_SIZE : size;
    }

    /** One more than the page holds, which says whether a next page exists. */
    Limit lookahead() {
        return Limit.of(pageSize() + 1);
    }
}
