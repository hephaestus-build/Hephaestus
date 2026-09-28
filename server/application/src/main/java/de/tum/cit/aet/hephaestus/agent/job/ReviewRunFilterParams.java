package de.tum.cit.aet.hephaestus.agent.job;

import io.swagger.v3.oas.annotations.Parameter;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Optional filters for the review listing, flattened onto the query string by {@link ParameterObject}.
 *
 * <p>{@code from}/{@code to} are the same half-open window the sibling observation and feedback
 * listings take — see {@code ReviewObservationFilterParams} — down to the rejection message, so the
 * three surfaces under {@code /practices/reviews} answer a pasted date range identically.
 */
public record ReviewRunFilterParams(
        @Parameter(description = "Statuses to list (repeatable); omit for every status")
        @RequestParam(required = false)
        @Nullable
        List<AgentJobStatus> status,

        @Parameter(description = "Inclusive lower bound on when the review was requested")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        @Nullable
        Instant from,

        @Parameter(description = "Exclusive upper bound on when the review was requested")
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        @Nullable
        Instant to) {
    /** Rejects a backwards window, which would otherwise return an empty page for no visible reason. */
    public ReviewRunFilterParams validated() {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must not be after to");
        }
        return this;
    }

    public Collection<AgentJobStatus> statuses() {
        return status == null || status.isEmpty() ? EnumSet.allOf(AgentJobStatus.class) : status;
    }
}
