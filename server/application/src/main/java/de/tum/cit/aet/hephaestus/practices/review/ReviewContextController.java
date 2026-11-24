package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewContextDTO;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@ConditionalOnServerRole
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/practices/review-context")
@Tag(name = "Practice review requests", description = "Asking for a review of a piece of work by hand")
@RequiredArgsConstructor
@Validated
public class ReviewContextController {

    private final ReviewContextService reviewContextService;

    @GetMapping
    @PreAuthorize("@workspaceSecure.isMember()")
    @Operation(
            summary = "Find the reviewed work a provider page shows",
            description = "Reads the address only: nothing is fetched from it. The address must be on the server "
                    + "this workspace is connected to. Every way of not finding the work — not mirrored, not "
                    + "monitored here, another server, deleted or confidential — is the same 404.",
            operationId = "resolveReviewContext")
    @ApiResponse(
            responseCode = "200",
            description = "The work, and what the caller may do about it",
            content = @Content(schema = @Schema(implementation = ReviewContextDTO.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Not an HTTPS address, or not the page of a pull request, merge request or issue",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "403",
            description = "The caller is not a member of this workspace",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "404",
            description = "This workspace has no reviewed work at this address",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<ReviewContextDTO> resolveReviewContext(
            WorkspaceContext workspaceContext,
            @Parameter(description = "The provider page's address, as the browser shows it")
                    @RequestParam
                    @Size(max = 2048)
                    String url) {
        return ResponseEntity.ok(reviewContextService.resolve(workspaceContext.id(), url));
    }
}
