package de.tum.cit.aet.hephaestus.practices.curated;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.CatalogDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionOptionsService;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceDefaults;
import de.tum.cit.aet.hephaestus.practices.curated.dto.CreateCuratedGroupRequestDTO;
import de.tum.cit.aet.hephaestus.practices.curated.dto.CreateCuratedPracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.curated.dto.CuratedCatalogDTO;
import de.tum.cit.aet.hephaestus.practices.curated.dto.CuratedCatalogSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.curated.dto.CuratedGroupDTO;
import de.tum.cit.aet.hephaestus.practices.curated.dto.CuratedGroupRequestDTO;
import de.tum.cit.aet.hephaestus.practices.curated.dto.CuratedPracticeDTO;
import de.tum.cit.aet.hephaestus.practices.curated.dto.CuratedPracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.curated.dto.CuratedPracticeSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.curated.dto.UpdateCuratedStatusRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PlacePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDefinitionOptionsDTO;
import de.tum.cit.aet.hephaestus.practices.dto.ReorderPracticeGroupsRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.ReorderPracticesRequestDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/admin/practice-catalog")
@RecentSignInExempt(reason = "curates practice content and its order; grants no access and stores no credential")
@PreAuthorize("hasAuthority('app_admin')")
@ConditionalOnServerRole
@Tag(name = "Admin Practice Catalog", description = "The starting catalog copied into new workspaces")
@RequiredArgsConstructor
@Validated
public class CuratedCatalogAdminController {

    private final CuratedCatalogService service;
    private final PracticeEvidenceDefaults evidenceDefaults;
    private final PracticeDefinitionOptionsService definitionOptionsService;

    @GetMapping("/definition-options")
    @Operation(
            summary = "Read practice definition options",
            description =
                    "Returns available review events, recommended requirements, and allowed evidence sources by work type",
            operationId = "adminGetPracticeDefinitionOptions")
    public ResponseEntity<PracticeDefinitionOptionsDTO> definitionOptions() {
        return ResponseEntity.ok(definitionOptionsService.options());
    }

    @GetMapping
    @Operation(
            summary = "Read the instance catalog",
            description =
                    "Practice summaries, complete groups, ordering, and catalog state. Fetch a practice for its full definition.",
            operationId = "adminGetCuratedCatalog")
    public ResponseEntity<CuratedCatalogDTO> catalog() {
        EffectiveCatalog catalog = service.catalog();
        return catalogResponse(catalog);
    }

    @GetMapping("/practices/{slug}")
    @Operation(summary = "Read a catalog practice", operationId = "adminGetCuratedPractice")
    @ApiResponse(
            responseCode = "200",
            description = "The practice",
            content = @Content(schema = @Schema(implementation = CuratedPracticeDTO.class)))
    public ResponseEntity<CuratedPracticeDTO> getPractice(@PathVariable String slug) {
        return ok(service.practice(slug), CuratedPracticeDTO::from);
    }

    @PostMapping("/practices")
    @Operation(summary = "Add a practice to the catalog", operationId = "adminCreateCuratedPractice")
    @ApiResponse(
            responseCode = "201",
            description = "Practice added",
            content = @Content(schema = @Schema(implementation = CuratedPracticeDTO.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Slug already exists",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CURATED_PRACTICE")
    public ResponseEntity<CuratedPracticeDTO> createPractice(
            @Valid @RequestBody CreateCuratedPracticeRequestDTO request) {
        var entry = service.createPractice(request.slug(), definition(request.definition()));
        return created(entry.slug(), entry.etag(), CuratedPracticeDTO.from(entry));
    }

    @PutMapping("/practices/{slug}")
    @Operation(summary = "Replace a practice definition", operationId = "adminUpdateCuratedPractice")
    @ApiResponse(
            responseCode = "200",
            description = "The practice",
            content = @Content(schema = @Schema(implementation = CuratedPracticeDTO.class)))
    @ApiResponse(
            responseCode = "412",
            description = "The supplied ETag is stale",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "428",
            description = "If-Match is required",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CURATED_PRACTICE")
    public ResponseEntity<CuratedPracticeDTO> updatePractice(
            @PathVariable String slug,
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch,
            @Valid @RequestBody CuratedPracticeRequestDTO request) {
        return ok(service.writePractice(slug, precondition(ifMatch), definition(request)), CuratedPracticeDTO::from);
    }

    @PatchMapping("/practices/{slug}/status")
    @Operation(
            summary = "Exclude a practice from new workspaces, or include it again",
            operationId = "adminUpdateCuratedPracticeStatus")
    @ApiResponse(
            responseCode = "200",
            description = "The practice",
            content = @Content(schema = @Schema(implementation = CuratedPracticeDTO.class)))
    @ApiResponse(
            responseCode = "412",
            description = "The supplied ETag is stale",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "428",
            description = "If-Match is required",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CURATED_PRACTICE")
    public ResponseEntity<CuratedPracticeDTO> updatePracticeStatus(
            @PathVariable String slug,
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch,
            @Valid @RequestBody UpdateCuratedStatusRequestDTO request) {
        return ok(service.setPracticeStatus(slug, precondition(ifMatch), request.status()), CuratedPracticeDTO::from);
    }

    @DeleteMapping("/practices/{slug}/override")
    @Operation(
            summary = "Use the Hephaestus definition of a practice",
            description = "Discards the customization, so the practice follows the Hephaestus default again.",
            operationId = "adminDeleteCuratedPracticeOverride")
    @ApiResponse(
            responseCode = "200",
            description = "The practice",
            content = @Content(schema = @Schema(implementation = CuratedPracticeDTO.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Hephaestus ships no definition for this slug",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "412",
            description = "The supplied ETag is stale",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "428",
            description = "If-Match is required",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CURATED_PRACTICE")
    public ResponseEntity<CuratedPracticeDTO> resetPractice(
            @PathVariable String slug,
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch) {
        return ok(service.resetPractice(slug, precondition(ifMatch)), CuratedPracticeDTO::from);
    }

    @PutMapping("/practices/{slug}/override/acknowledgement")
    @Operation(
            summary = "Keep the saved practice customization",
            description = "Records that the Hephaestus update was reviewed and keeps the saved definition.",
            operationId = "adminKeepCuratedPractice")
    @ApiResponse(
            responseCode = "200",
            description = "The practice",
            content = @Content(schema = @Schema(implementation = CuratedPracticeDTO.class)))
    @ApiResponse(
            responseCode = "412",
            description = "The supplied ETag is stale",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "428",
            description = "If-Match is required",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CURATED_PRACTICE")
    public ResponseEntity<CuratedPracticeDTO> keepPractice(
            @PathVariable String slug,
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch) {
        return ok(service.keepPractice(slug, precondition(ifMatch)), CuratedPracticeDTO::from);
    }

    @PatchMapping("/practices/reorder")
    @Operation(summary = "Reorder practices within one catalog group", operationId = "adminReorderCuratedPractices")
    @AuditExempt(reason = "catalog order affects presentation, not review execution or delivery")
    public ResponseEntity<CuratedCatalogDTO> reorderPractices(
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch,
            @Valid @RequestBody ReorderPracticesRequestDTO request) {
        return catalogResponse(
                service.reorderPractices(precondition(ifMatch), request.groupSlug(), request.orderedSlugs()));
    }

    @PatchMapping("/practices/{slug}/placement")
    @Operation(summary = "Move a practice to another catalog group", operationId = "adminPlaceCuratedPractice")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CURATED_PRACTICE")
    public ResponseEntity<CuratedCatalogDTO> placePractice(
            @PathVariable String slug,
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch,
            @Valid @RequestBody PlacePracticeRequestDTO request) {
        return catalogResponse(
                service.placePractice(slug, precondition(ifMatch), request.groupSlug(), request.position()));
    }

    @GetMapping("/groups/{slug}")
    @Operation(summary = "Read a catalog group", operationId = "adminGetCuratedGroup")
    @ApiResponse(
            responseCode = "200",
            description = "The group",
            content = @Content(schema = @Schema(implementation = CuratedGroupDTO.class)))
    public ResponseEntity<CuratedGroupDTO> getGroup(@PathVariable String slug) {
        return ok(service.group(slug), CuratedGroupDTO::from);
    }

    @PostMapping("/groups")
    @Operation(summary = "Add a group to the catalog", operationId = "adminCreateCuratedGroup")
    @ApiResponse(
            responseCode = "201",
            description = "Group added",
            content = @Content(schema = @Schema(implementation = CuratedGroupDTO.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Slug already exists",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CURATED_PRACTICE_GROUP")
    public ResponseEntity<CuratedGroupDTO> createGroup(@Valid @RequestBody CreateCuratedGroupRequestDTO request) {
        var entry = service.createGroup(request.slug(), request.definition().definition());
        return created(entry.slug(), entry.etag(), CuratedGroupDTO.from(entry));
    }

    @PutMapping("/groups/{slug}")
    @Operation(summary = "Replace a group definition", operationId = "adminUpdateCuratedGroup")
    @ApiResponse(
            responseCode = "200",
            description = "The group",
            content = @Content(schema = @Schema(implementation = CuratedGroupDTO.class)))
    @ApiResponse(
            responseCode = "412",
            description = "The supplied ETag is stale",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "428",
            description = "If-Match is required",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CURATED_PRACTICE_GROUP")
    public ResponseEntity<CuratedGroupDTO> updateGroup(
            @PathVariable String slug,
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch,
            @Valid @RequestBody CuratedGroupRequestDTO request) {
        return ok(service.writeGroup(slug, precondition(ifMatch), request.definition()), CuratedGroupDTO::from);
    }

    @PatchMapping("/groups/{slug}/status")
    @Operation(
            summary = "Exclude a group from new workspaces, or include it again",
            description =
                    "Excluding a group also excludes its practices from new workspaces; existing workspaces do not change.",
            operationId = "adminUpdateCuratedGroupStatus")
    @ApiResponse(
            responseCode = "200",
            description = "The updated catalog",
            content = @Content(schema = @Schema(implementation = CuratedCatalogDTO.class)))
    @ApiResponse(
            responseCode = "412",
            description = "The supplied ETag is stale",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "428",
            description = "If-Match is required",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CURATED_PRACTICE_GROUP")
    public ResponseEntity<CuratedCatalogDTO> updateGroupStatus(
            @PathVariable String slug,
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch,
            @Valid @RequestBody UpdateCuratedStatusRequestDTO request) {
        return catalogResponse(service.setGroupStatus(slug, precondition(ifMatch), request.status()));
    }

    @DeleteMapping("/groups/{slug}/override")
    @Operation(summary = "Use the Hephaestus definition of a group", operationId = "adminDeleteCuratedGroupOverride")
    @ApiResponse(
            responseCode = "200",
            description = "The group",
            content = @Content(schema = @Schema(implementation = CuratedGroupDTO.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Hephaestus ships no definition for this slug",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "412",
            description = "The supplied ETag is stale",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "428",
            description = "If-Match is required",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CURATED_PRACTICE_GROUP")
    public ResponseEntity<CuratedGroupDTO> resetGroup(
            @PathVariable String slug,
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch) {
        return ok(service.resetGroup(slug, precondition(ifMatch)), CuratedGroupDTO::from);
    }

    @PutMapping("/groups/{slug}/override/acknowledgement")
    @Operation(summary = "Keep the saved group customization", operationId = "adminKeepCuratedGroup")
    @ApiResponse(
            responseCode = "200",
            description = "The group",
            content = @Content(schema = @Schema(implementation = CuratedGroupDTO.class)))
    @ApiResponse(
            responseCode = "412",
            description = "The supplied ETag is stale",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(
            responseCode = "428",
            description = "If-Match is required",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CURATED_PRACTICE_GROUP")
    public ResponseEntity<CuratedGroupDTO> keepGroup(
            @PathVariable String slug,
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch) {
        return ok(service.keepGroup(slug, precondition(ifMatch)), CuratedGroupDTO::from);
    }

    @PatchMapping("/groups/reorder")
    @Operation(summary = "Reorder catalog groups", operationId = "adminReorderCuratedGroups")
    @AuditExempt(reason = "catalog order affects presentation, not review execution or delivery")
    public ResponseEntity<CuratedCatalogDTO> reorderGroups(
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch,
            @Valid @RequestBody ReorderPracticeGroupsRequestDTO request) {
        return catalogResponse(service.reorderGroups(precondition(ifMatch), request.orderedSlugs()));
    }

    @DeleteMapping("/order")
    @Operation(summary = "Use the Hephaestus default order", operationId = "adminResetCuratedCatalogOrder")
    @AuditExempt(reason = "catalog order affects presentation, not review execution or delivery")
    public ResponseEntity<CuratedCatalogDTO> resetOrder(
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch) {
        return catalogResponse(service.resetOrder(precondition(ifMatch)));
    }

    private PracticeDefinition definition(CuratedPracticeRequestDTO request) {
        var evidence = request.automatedReviewPolicy() == null
                ? evidenceDefaults.policyFor(PracticeBinding.artifactKindOf(request.bindings()))
                : request.automatedReviewPolicy();
        return request.definition(evidence);
    }

    private static ResponseEntity<CuratedCatalogDTO> catalogResponse(EffectiveCatalog catalog) {
        CuratedCatalogDTO body = new CuratedCatalogDTO(
                catalog.etag(),
                catalog.customOrder(),
                CuratedCatalogSummaryDTO.from(catalog.summary()),
                catalog.groups().stream().map(CuratedGroupDTO::from).toList(),
                catalog.practices().stream()
                        .map(entry -> CuratedPracticeSummaryDTO.from(entry, catalog.isEffectivelyOffered(entry)))
                        .toList());
        return ResponseEntity.ok().eTag(etag(catalog.etag())).body(body);
    }

    private static <D extends CatalogDefinition, T> ResponseEntity<T> ok(
            CatalogEntry<D> entry, java.util.function.Function<CatalogEntry<D>, T> body) {
        return ResponseEntity.ok().eTag(etag(entry.etag())).body(body.apply(entry));
    }

    private static <T> ResponseEntity<T> created(String slug, String tag, T body) {
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{slug}")
                .buildAndExpand(slug)
                .toUri();
        return ResponseEntity.created(location).eTag(etag(tag)).body(body);
    }

    private static String etag(String tag) {
        return EntityTagPrecondition.format(tag);
    }

    private static @Nullable EntityTagPrecondition precondition(@Nullable String ifMatch) {
        return ifMatch == null ? null : EntityTagPrecondition.parse(ifMatch);
    }
}
