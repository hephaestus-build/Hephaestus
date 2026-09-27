package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.RequestParam;

public record ActivityTimelineFilterParams(
        @Parameter(description = "Kinds of activity to list (repeatable); omit for every kind")
        @RequestParam(required = false)
        @Nullable
        Set<ActivityKind> kinds,

        @Parameter(description = "The previous page's nextCursor; omit for the first page")
        @RequestParam(required = false)
        @Nullable
        String cursor,

        @Parameter(description = "Page size from 1 to 50; defaults to 20")
        @RequestParam(required = false)
        @Min(1)
        @Max(50)
        @Nullable
        Integer size) {

    private static final int DEFAULT_PAGE_SIZE = 20;

    Set<ActivityEventType> eventTypes() {
        return ActivityKind.eventTypes(kinds == null ? Set.of() : kinds);
    }

    /** Where the page starts; an unreadable cursor is rejected. */
    ActivityTimelineCursor position(ActivityRange range) {
        return cursor == null ? ActivityTimelineCursor.startOf(range) : ActivityTimelineCursor.decode(cursor);
    }

    /** The page size only: the cursor, not an offset, says where the page starts. */
    Pageable pageable() {
        return PageRequest.ofSize(size == null ? DEFAULT_PAGE_SIZE : size);
    }
}
