package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.web.QueryFilterSupport;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Query parameters for the developer observation feed. Bound with {@code @ParameterObject}, so the wire
 * format stays one flat query string — this only changes the Java signature, not the HTTP contract.
 *
 * <p>Every optional component is a nullable wrapper; the compact constructor supplies the ordering
 * defaults and {@link QueryFilterSupport} the paging ones, and states why neither can come from a
 * {@code defaultValue} on {@code @RequestParam}.
 */
public record ObservationFeedFilterParams(
        @Parameter(description = "Filter by practice slug") @RequestParam(required = false) @Nullable
        String practiceSlug,

        @Parameter(description = "Filter by the practice group the observed practice belongs to")
        @RequestParam(required = false)
        @Nullable
        String groupSlug,

        @Parameter(description = "Filter by presence") @RequestParam(required = false) @Nullable
        Presence presence,

        @RequestParam(required = false) @Nullable AssessmentStatus assessmentStatus,
        /**
         * Bare strings, not {@link ArtifactKind}s — {@link QueryFilterSupport#artifactKind} has the reason,
         * and parses them in {@link #toQuery()}, where a malformed value becomes a 400.
         */
        @Parameter(
                description = "Only observations on these kinds of reviewed work, e.g. scm.pull_request (repeatable)")
        @RequestParam(required = false)
        @Nullable
        List<String> artifactKinds,

        @Parameter(description = "Only observations with these severities (repeatable); omit for all")
        @RequestParam(required = false)
        @Nullable
        List<Severity> severities,

        @Parameter(description = "Drop NOT_APPLICABLE rows — only observations where the practice actually applied")
        @RequestParam(required = false)
        @Nullable
        Boolean displayableOnly,

        @Parameter(description = "Feed ordering: DATE (default) or SEVERITY (most severe first, ties newest-first)")
        @RequestParam(required = false)
        ObservationService.@Nullable ObservationSort sort,

        @Parameter(
                description = "Ordering direction: for DATE newest/oldest first, for SEVERITY most/least severe first")
        @RequestParam(required = false)
        Sort.@Nullable Direction direction,

        @Parameter(description = "Zero-based page") @RequestParam(required = false) @PositiveOrZero @Nullable
        Integer page,

        @Parameter(description = "Page size from 1 to 100") @RequestParam(required = false) @Min(1) @Max(100) @Nullable
        Integer size) {
    private static final int DEFAULT_PAGE_SIZE = 20;

    public ObservationFeedFilterParams {
        displayableOnly = displayableOnly != null && displayableOnly;
        sort = sort == null ? ObservationService.ObservationSort.DATE : sort;
        direction = direction == null ? Sort.Direction.DESC : direction;
    }

    /**
     * The page to read, already normalised and sorted.
     *
     * <p>The severity query carries its own ORDER BY, so it must not also receive a sort: adding one
     * would have the database order by two different keys.
     */
    public Pageable pageable() {
        Pageable page = QueryFilterSupport.pageable(this.page, size, DEFAULT_PAGE_SIZE);
        return sort() == ObservationService.ObservationSort.SEVERITY
                ? page
                : PageRequest.of(
                        page.getPageNumber(),
                        page.getPageSize(),
                        Sort.by(Objects.requireNonNull(direction), "observedAt"));
    }

    /** The domain-facing shape; {@code direction} collapses into the severity sort's only use of it. */
    public ObservationFeedQuery toQuery() {
        return new ObservationFeedQuery(
                practiceSlug,
                groupSlug,
                assessmentStatus,
                presence,
                QueryFilterSupport.artifactKinds(artifactKinds),
                severities,
                Objects.requireNonNull(displayableOnly),
                Objects.requireNonNull(sort),
                direction == Sort.Direction.DESC);
    }
}
