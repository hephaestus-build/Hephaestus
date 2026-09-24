package de.tum.cit.aet.hephaestus.practices.groupdetail;

import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.web.QueryFilterSupport;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.RequestParam;

public record PracticeGroupReviewRunFilterParams(
        @RequestParam(required = false) @Nullable String practiceSlug,

        @Parameter(description = "Only reviews of these artifact kinds, e.g. scm.pull_request (repeatable)")
        @RequestParam(required = false)
        @Nullable
        List<String> artifactKinds,

        @RequestParam(required = false) @Nullable List<Severity> severities,

        @Parameter(description = "Zero-based page, at most 100")
        @RequestParam(required = false)
        @PositiveOrZero
        @Max(100)
        @Nullable
        Integer page,

        @Parameter(description = "Page size from 1 to 50") @RequestParam(required = false) @Min(1) @Max(50) @Nullable
        Integer size) {
    private static final int DEFAULT_PAGE_SIZE = 10;

    public Pageable pageable() {
        return QueryFilterSupport.pageable(page, size, DEFAULT_PAGE_SIZE);
    }

    public PracticeGroupReviewRunService.RunFilters runFilters() {
        return new PracticeGroupReviewRunService.RunFilters(
                practiceSlug, QueryFilterSupport.artifactKinds(artifactKinds), severities);
    }
}
