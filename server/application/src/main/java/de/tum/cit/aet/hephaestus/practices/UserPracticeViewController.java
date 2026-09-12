package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.UserViewRead;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.dto.ReviewedPracticeDTO;
import de.tum.cit.aet.hephaestus.practices.groupdetail.PracticeGroupReviewRunFilterParams;
import de.tum.cit.aet.hephaestus.practices.groupdetail.PracticeGroupReviewRunService;
import de.tum.cit.aet.hephaestus.practices.groupdetail.PracticeGroupTrendQueryService;
import de.tum.cit.aet.hephaestus.practices.groupdetail.dto.PracticeGroupReviewRunsPageDTO;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationService;
import de.tum.cit.aet.hephaestus.practices.observation.dto.ObservationDetailDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.observation.trend.dto.PracticeGroupTrendDTO;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@WorkspaceScopedController
@ConditionalOnServerRole
@RequestMapping("/user-view/users/{userId}/practices")
@PreAuthorize("hasAuthority('app_admin')")
@Tag(name = "User view")
@RequiredArgsConstructor
@Validated
public class UserPracticeViewController {
    private final UserPracticeViewService practices;

    public record UserPracticeSummaryDTO(
            @NonNull List<PracticeGroupDTO> groups,
            @NonNull List<PracticeGroupStandingDTO> groupStandings,
            @NonNull List<PracticeStandingDTO> standings,
            @NonNull List<ReviewedPracticeDTO> practices) {}

    private final PracticeGroupTrendQueryService trends;
    private final PracticeGroupReviewRunService runs;
    private final ObservationService observations;

    @GetMapping
    @UserViewRead
    @Operation(
            summary = "View a user's existing private practice profile without signing in as them",
            operationId = "getUserPracticeView")
    public ResponseEntity<UserPracticeSummaryDTO> summary(WorkspaceContext workspace, @PathVariable Long userId) {
        return response(practices.summary(workspace, userId));
    }

    @GetMapping("/groups/{groupSlug}/trend")
    @UserViewRead
    @Operation(summary = "View a user's private practice group trend", operationId = "getUserViewTrend")
    public ResponseEntity<PracticeGroupTrendDTO> trend(
            WorkspaceContext workspace, @PathVariable Long userId, @PathVariable String groupSlug) {
        return response(trends.get(workspace, userId, groupSlug));
    }

    @GetMapping("/groups/{groupSlug}/runs")
    @UserViewRead
    @Operation(summary = "View a user's private practice review history", operationId = "listUserViewRuns")
    public ResponseEntity<PracticeGroupReviewRunsPageDTO> runs(
            WorkspaceContext workspace,
            @PathVariable Long userId,
            @PathVariable String groupSlug,
            @Valid @ParameterObject PracticeGroupReviewRunFilterParams filter) {
        return response(runs.list(workspace, userId, groupSlug, filter.runFilters(), filter.pageable()));
    }

    @GetMapping("/observations/{observationId}")
    @UserViewRead
    @Operation(summary = "View a user's private observation and feedback", operationId = "getUserViewObservation")
    public ResponseEntity<ObservationDetailDTO> observation(
            WorkspaceContext workspace, @PathVariable Long userId, @PathVariable UUID observationId) {
        return response(observations.getObservationDetail(workspace.id(), userId, observationId));
    }

    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
