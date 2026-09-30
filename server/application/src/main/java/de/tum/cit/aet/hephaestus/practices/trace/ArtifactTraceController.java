package de.tum.cit.aet.hephaestus.practices.trace;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.profile.PracticeProfileReviewRunService;
import de.tum.cit.aet.hephaestus.practices.trace.dto.ArtifactTraceDTO;
import de.tum.cit.aet.hephaestus.practices.trace.dto.TracedArtifactDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.WorkspaceAccessService;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * "Why didn't Hephaestus say anything about my merge request?" — answerable without a SQL console.
 *
 * <p>The list of recorded work is the workspace's, so it is an admin's. One work's trace is also a member's
 * when they name a review that observed them on it, which is how their practice profile opens it; any other
 * review, or none, answers 404, the same answer a review that does not exist gets, so a member cannot learn
 * which work or reviews exist from it. A member's trace counts only the observations about them and the
 * feedback addressed to them, since a review may have observed several people on the same work. In a user view
 * the viewed member's rule applies.
 */
@WorkspaceScopedController
@RequestMapping("/practices/trace")
@Tag(name = "Practice review trace")
@RequiredArgsConstructor
@Validated
public class ArtifactTraceController {

    private final ArtifactTraceQueryService queryService;
    private final PracticeProfileReviewRunService reviewRuns;
    private final WorkspaceAccessService access;

    @GetMapping
    @RequireAtLeastWorkspaceAdmin
    @Operation(
            summary = "List work this workspace recorded something about",
            description = "Workspace admins only. Built from the signal ledger, so it includes work that was never "
                    + "reviewed — which is exactly what a listing derived from review runs cannot show. Most "
                    + "recently signalled first.",
            operationId = "listTracedArtifacts")
    @ApiResponse(responseCode = "200", description = "Paginated artifacts returned")
    @ApiResponse(
            responseCode = "400",
            description = "Unknown artifact kind or invalid pagination",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Workspace administrator access is required",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<PagedModel<TracedArtifactDTO>> listTracedArtifacts(
            WorkspaceContext workspaceContext,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size,
            @Parameter(description = "Restrict to one kind of work, e.g. scm.pull_request")
                    @RequestParam(required = false)
                    @Nullable
                    String artifactKind) {
        return ResponseEntity.ok(new PagedModel<>(
                queryService.list(workspaceContext.id(), parseKind(artifactKind), PageRequest.of(page, size))));
    }

    @GetMapping("/{artifactKind}/{artifactId}")
    @PreAuthorize("@workspaceSecure.isMember()")
    @Operation(
            summary = "Explain what every practice did about one piece of work",
            description = "Every practice the workspace runs against this kind of work appears, including the ones "
                    + "that did nothing, each with the recorded reason. Name a review and every answer is that "
                    + "review's own. A workspace admin may read any work, with or without a review; anyone else "
                    + "must name a review that observed them on this work, and reads only the observations about "
                    + "them and the feedback addressed to them. 404 means nothing about this artifact "
                    + "was ever recorded here, the named review never ran on it, or the caller may not read it.",
            operationId = "getArtifactTrace")
    @ApiResponse(
            responseCode = "200",
            description = "Trace returned",
            content = @Content(schema = @Schema(implementation = ArtifactTraceDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Nothing recorded about this artifact in this workspace, the named review never ran on it, "
                    + "or the caller may not read it",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ArtifactTraceDTO> getArtifactTrace(
            WorkspaceContext workspaceContext,
            @Parameter(description = "Kind of work, e.g. scm.pull_request") @PathVariable String artifactKind,
            @Parameter(description = "The artifact's identifier as the ledger stores it") @PathVariable Long artifactId,
            @Parameter(
                            description = "Answer for this review alone: every state, explanation and count is what "
                                    + "this review made of the work. Omit it for every review of the work at once, "
                                    + "which only a workspace admin may.")
                    @RequestParam(required = false)
                    @Nullable
                    UUID reviewId) {
        ArtifactKind kind = parseKind(artifactKind);
        if (kind == null) {
            throw new IllegalArgumentException("An artifact kind is required");
        }
        Long developerId = null;
        if (!access.isAdmin()) {
            if (reviewId == null) {
                throw new EntityNotFoundException("Traced artifact", kind.value() + "/" + artifactId);
            }
            String review = reviewId.toString();
            developerId = reviewRuns
                    .ownRunDeveloperOn(workspaceContext.id(), reviewId, kind, artifactId)
                    .orElseThrow(() -> new EntityNotFoundException("Review", review));
        }
        return ResponseEntity.ok(queryService.trace(workspaceContext.id(), kind, artifactId, reviewId, developerId));
    }

    /** A malformed kind is a bad request, not a 500: the grammar is enforced by {@link ArtifactKind}. */
    private static @Nullable ArtifactKind parseKind(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return ArtifactKind.of(value);
    }
}
