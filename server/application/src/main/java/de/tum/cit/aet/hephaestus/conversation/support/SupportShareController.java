package de.tum.cit.aet.hephaestus.conversation.support;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonAct;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** The person shares one of their own turns with one instance admin. Only the person can start it. */
@WorkspaceScopedController
@TeamOnly
@ConditionalOnServerRole
@RequestMapping("/support-shares/turns/{turnId}")
@Tag(name = "Support share", description = "A person shares one of their turns with one instance admin")
@PreAuthorize("@workspaceSecure.isMemberWithoutElevation()")
@RequiredArgsConstructor
public class SupportShareController {

    private final SupportShareService shares;

    @PostMapping("/preview")
    @Operation(
            operationId = "previewSupportShare",
            summary = "Show exactly what an instance admin would read if you share this turn")
    @ApiResponse(responseCode = "200", description = "The preview")
    @ApiResponse(
            responseCode = "404",
            description = "The turn is not one of yours",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<SupportSharePreviewDTO> preview(
            WorkspaceContext workspace,
            @PathVariable UUID turnId,
            @Valid @RequestBody SupportSharePreviewRequestDTO body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(shares.preview(workspace.id(), PersonAct.fromPrincipal(), turnId, body.includeText()));
    }

    @PostMapping
    @Operation(operationId = "createSupportShare", summary = "Share this turn with one instance admin for 7 days")
    @ApiResponse(responseCode = "201", description = "The share")
    @ApiResponse(
            responseCode = "409",
            description = "The messages changed after the preview",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<SupportShareDTO> share(
            WorkspaceContext workspace, @PathVariable UUID turnId, @Valid @RequestBody SupportShareRequestDTO body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(shares.share(workspace.id(), PersonAct.fromPrincipal(), turnId, body));
    }
}
