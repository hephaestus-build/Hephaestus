package de.tum.cit.aet.hephaestus.practices.trace;

import de.tum.cit.aet.hephaestus.core.exception.AccessForbiddenException;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.trace.dto.ArtifactTraceDTO;
import de.tum.cit.aet.hephaestus.practices.trace.dto.TracedArtifactDTO;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * "Why didn't Hephaestus say anything about my merge request?" — answerable without a SQL console.
 *
 * <p>Open to any workspace <em>member</em>, not just an admin: the question is a developer's before it
 * is an operator's. Membership is the bar {@code ObservationController} already sets for reading every
 * developer's observations on a pull request, and this surface carries counts rather than content.
 *
 * <p>A GET under {@code /workspaces/**} is {@code permitAll} at the filter chain and a public-read
 * workspace admits anonymous callers, so the membership check has to be made here or it is not made.
 */
@WorkspaceScopedController
@RequestMapping("/practices/trace")
@Tag(name = "Practice review trace")
@RequiredArgsConstructor
@Validated
public class ArtifactTraceController {

    private final ArtifactTraceQueryService queryService;

    @GetMapping
    @Operation(
            summary = "List work this workspace recorded something about",
            description = "Built from the signal ledger, so it includes work that was never reviewed — which is "
                    + "exactly what a listing derived from review runs cannot show. Most recently signalled first.",
            operationId = "listTracedArtifacts")
    @ApiResponse(responseCode = "200", description = "Paginated artifacts returned")
    @ApiResponse(
            responseCode = "400",
            description = "Unknown artifact kind or invalid pagination",
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
        requireMembership(workspaceContext);
        return ResponseEntity.ok(new PagedModel<>(
                queryService.list(workspaceContext.id(), parseKind(artifactKind), PageRequest.of(page, size))));
    }

    @GetMapping("/{artifactKind}/{artifactId}")
    @Operation(
            summary = "Explain what every practice did about one piece of work",
            description = "Every practice the workspace runs against this kind of work appears, including the ones "
                    + "that did nothing, each with the recorded reason. Name a review and every answer is that "
                    + "review's own, so an older review of the same work speaks for itself. 404 means nothing about "
                    + "this artifact was ever recorded here — not that the trace is unavailable.",
            operationId = "getArtifactTrace")
    @ApiResponse(
            responseCode = "200",
            description = "Trace returned",
            content = @Content(schema = @Schema(implementation = ArtifactTraceDTO.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Nothing recorded about this artifact in this workspace",
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
                                    + "this review made of the work. Omit it for every review of the work at once.")
                    @RequestParam(required = false)
                    @Nullable
                    UUID reviewId) {
        requireMembership(workspaceContext);
        ArtifactKind kind = parseKind(artifactKind);
        if (kind == null) {
            throw new IllegalArgumentException("An artifact kind is required");
        }
        return ResponseEntity.ok(queryService.trace(workspaceContext.id(), kind, artifactId, reviewId));
    }

    private static void requireMembership(WorkspaceContext workspaceContext) {
        if (!workspaceContext.hasMembership()) {
            throw new AccessForbiddenException("Workspace membership is required to read a practice review trace");
        }
    }

    /** A malformed kind is a bad request, not a 500: the grammar is enforced by {@link ArtifactKind}. */
    private static @Nullable ArtifactKind parseKind(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return ArtifactKind.of(value);
    }
}
