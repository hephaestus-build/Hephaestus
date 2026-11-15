package de.tum.cit.aet.hephaestus.practices.curated.adoption;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.exception.PreconditionRequiredException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.practices.CatalogOriginPresenter;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

@WorkspaceScopedController
@RequestMapping("/practice-catalog/adoption")
@RequireAtLeastWorkspaceAdmin
@ConditionalOnServerRole
@Tag(name = "Practice Catalog Adoption", description = "Adopt instance catalog practices into a workspace")
@RequiredArgsConstructor
public class CatalogAdoptionController {

    private final CatalogAdoptionService service;
    private final PublicApiUrls publicApiUrls;
    private final CatalogOriginPresenter presenter;

    @GetMapping
    @Operation(summary = "List practices available for adoption", operationId = "listAdoptablePractices")
    @ApiResponse(responseCode = "200", description = "Available practices returned")
    @ApiResponse(
            responseCode = "403",
            description = "Workspace administrator access is required",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    public ResponseEntity<List<CatalogPracticeSummaryDTO>> list(WorkspaceContext context) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(service.list(context));
    }

    @GetMapping("/{slug}")
    @Operation(summary = "Get a practice adoption preview", operationId = "previewPracticeAdoption")
    @ApiResponse(
            responseCode = "200",
            description = "Adoption preview returned",
            headers =
                    @Header(
                            name = HttpHeaders.ETAG,
                            description = "Strong validator for If-Match",
                            schema = @Schema(type = "string")))
    @ApiResponse(
            responseCode = "404",
            description = "Practice is not offered",
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
    public ResponseEntity<CatalogPracticePreviewDTO> preview(WorkspaceContext context, @PathVariable String slug) {
        CatalogAdoptionPlan plan = service.preview(context, slug);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .eTag(EntityTagPrecondition.format(plan.etag()))
                .body(plan.preview());
    }

    @GetMapping("/groups/{slug}")
    @Operation(summary = "Preview adoption of a catalog group and its practices", operationId = "previewGroupAdoption")
    public ResponseEntity<CatalogGroupAdoptionPreviewDTO> previewGroup(
            WorkspaceContext context, @PathVariable String slug) {
        CatalogGroupAdoptionPlan plan = service.previewGroup(context, slug);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .eTag(EntityTagPrecondition.format(plan.etag()))
                .body(plan.preview());
    }

    @PostMapping("/{slug}")
    @Operation(summary = "Adopt a catalog practice", operationId = "adoptPractice")
    @ApiResponse(responseCode = "201", description = "Practice adopted")
    @ApiResponse(
            responseCode = "409",
            description = "The practice slug already exists in the workspace",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "412",
            description = "Catalog or workspace state changed since preview",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "428",
            description = "The If-Match preview validator is required",
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
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    public ResponseEntity<PracticeDTO> adopt(
            WorkspaceContext context,
            @PathVariable String slug,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException();
        }
        Practice adopted = service.adopt(context, slug, ifMatch);
        URI location = publicApiUrls.uri(
                "/workspaces/{workspaceSlug}/practices/{practiceSlug}", context.slug(), adopted.getSlug());
        return ResponseEntity.created(location)
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(presenter.present(context.id(), adopted));
    }

    @PostMapping("/groups/{slug}")
    @Operation(summary = "Adopt all available practices in a catalog group", operationId = "adoptGroup")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    public ResponseEntity<CatalogGroupAdoptionResultDTO> adoptGroup(
            WorkspaceContext context,
            @PathVariable String slug,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) @Nullable String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException();
        }
        CatalogAdoptionService.CatalogGroupAdoptionResult result = service.adoptGroup(context, slug, ifMatch);
        CatalogGroupAdoptionResultDTO response = new CatalogGroupAdoptionResultDTO(
                result.added().stream()
                        .map(practice -> presenter.present(context.id(), practice))
                        .toList(),
                result.moved().stream()
                        .map(practice -> presenter.present(context.id(), practice))
                        .toList());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(response);
    }
}
