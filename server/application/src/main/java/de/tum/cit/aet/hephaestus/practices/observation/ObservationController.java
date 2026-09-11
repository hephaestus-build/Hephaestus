package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.core.web.PageResponseDTO;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.observation.dto.DeveloperPracticeSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.ObservationDetailDTO;
import de.tum.cit.aet.hephaestus.practices.observation.dto.ObservationListDTO;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Read-only REST API for practice observations.
 *
 * <p>All endpoints require workspace membership (enforced by {@link WorkspaceScopedController}).
 * List, summary, and detail endpoints are scoped to the authenticated developer's own
 * observations. The pull-request endpoint returns observations for all developers on that PR.
 */
@WorkspaceScopedController
@RequestMapping("/practices/observations")
@Tag(name = "Practice Observations", description = "Read-only access to practice evaluation observations")
@RequiredArgsConstructor
@Validated
public class ObservationController {

    private final ObservationService observationService;
    private final EvidenceAuthorization evidenceAuthorization;

    @GetMapping
    @Operation(
            summary = "List observations for current user",
            description = "Paginated observations for the authenticated developer with optional filters")
    @ApiResponse(responseCode = "200", description = "Paginated observations returned")
    public ResponseEntity<PageResponseDTO<ObservationListDTO>> listObservations(
            WorkspaceContext workspaceContext, @Valid @ParameterObject ObservationFeedFilterParams filter) {
        Pageable pageable = filter.pageable();

        Page<ObservationListDTO> observations = observationService
                .getObservations(workspaceContext.id(), filter.toQuery(), pageable)
                .map(ObservationListDTO::from);
        return ResponseEntity.ok(PageResponseDTO.from(observations));
    }

    @GetMapping("/summary")
    @Operation(
            summary = "Per-practice summary for current user",
            description = "Aggregated observation counts per practice for dashboard cards")
    @ApiResponse(
            responseCode = "200",
            description = "Practice summaries returned",
            content =
                    @Content(
                            array = @ArraySchema(schema = @Schema(implementation = DeveloperPracticeSummaryDTO.class))))
    public ResponseEntity<List<DeveloperPracticeSummaryDTO>> getSummary(WorkspaceContext workspaceContext) {
        List<DeveloperPracticeSummaryDTO> summaries = observationService.getSummary(workspaceContext.id()).stream()
                .map(DeveloperPracticeSummaryDTO::from)
                .toList();
        return ResponseEntity.ok(summaries);
    }

    @GetMapping("/{observationId}")
    @Operation(summary = "Get observation detail")
    @ApiResponse(
            responseCode = "200",
            description = "Observation detail returned",
            content = @Content(schema = @Schema(implementation = ObservationDetailDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Observation not found or not owned by current user",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<ObservationDetailDTO> getObservation(
            WorkspaceContext workspaceContext, @PathVariable UUID observationId) {
        var observation = observationService.getObservation(workspaceContext.id(), observationId);
        String deliveredFeedback = observationService
                .getDeliveredGuidance(workspaceContext.id(), observationId)
                .orElse(null);
        String artifactUrl = observationService
                .getArtifactUrl(workspaceContext.id(), observation)
                .orElse(null);
        boolean includeEvidence = evidenceAuthorization.permits(
                workspaceContext.id(), observation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        return ResponseEntity.ok(
                ObservationDetailDTO.from(observation, deliveredFeedback, artifactUrl, includeEvidence));
    }

    @GetMapping("/pull-request/{prId}")
    @Operation(
            summary = "List observations for a pull request",
            description = "All observations for a specific pull request within the workspace")
    @ApiResponse(
            responseCode = "200",
            description = "PR observations returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = ObservationListDTO.class))))
    public ResponseEntity<List<ObservationListDTO>> getObservationsForPullRequest(
            WorkspaceContext workspaceContext, @PathVariable Long prId) {
        // Unlike the per-developer endpoints, this returns EVERY developer's BAD/ABSENT observations on the PR,
        // unscoped to the caller. On a public-read workspace an anonymous (membership-less) request would
        // otherwise expose them — require workspace membership.
        if (!workspaceContext.hasMembership()) {
            throw new AccessForbiddenException("Workspace membership is required to view pull-request observations");
        }
        List<ObservationListDTO> observations =
                observationService.getObservationsForPullRequest(workspaceContext.id(), prId).stream()
                        .map(ObservationListDTO::from)
                        .toList();
        return ResponseEntity.ok(observations);
    }
}
