package de.tum.cit.aet.hephaestus.practices.catalog;

import de.tum.cit.aet.hephaestus.core.AuditExempt;
import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionOptionsService;
import de.tum.cit.aet.hephaestus.practices.dto.AcceptPracticeReleaseRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogGroupDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogGroupInputDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogGroupUpdateDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogPracticeDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogPracticeInputDTO;
import de.tum.cit.aet.hephaestus.practices.dto.CatalogPracticeUpdateDTO;
import de.tum.cit.aet.hephaestus.practices.dto.MovePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDefinitionOptionsDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeReleaseProposalDTO;
import de.tum.cit.aet.hephaestus.practices.dto.ReorderRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.SetCatalogStatusRequestDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
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
@RequireInstanceAdmin
@WorkspaceAgnostic("The instance catalog has no workspace owner")
@RecentSignInExempt(
        reason = "Catalog edits change practice content and organization without changing access or credentials")
@RequiredArgsConstructor
@ConditionalOnServerRole
public class CatalogAdminController {
    private final CatalogService catalog;
    private final CatalogCustomizationService customization;
    private final PracticeDefinitionOptionsService options;

    @GetMapping
    @Operation(operationId = "getInstanceCatalog")
    public ResponseEntity<CatalogDTO> get() {
        var effective = catalog.effectiveCatalog();
        return ResponseEntity.ok().eTag(effective.etag()).body(catalog.view(effective));
    }

    @GetMapping("/definition-options")
    @Operation(operationId = "getInstanceDefinitionOptions")
    public PracticeDefinitionOptionsDTO options() {
        return options.options(null);
    }

    @GetMapping("/practices/{slug}")
    @Operation(operationId = "getCatalogPractice")
    public CatalogPracticeDTO practice(@PathVariable String slug) {
        return catalog.practiceView(catalog.practice(slug));
    }

    @GetMapping("/groups/{slug}")
    @Operation(operationId = "getCatalogGroup")
    public CatalogGroupDTO group(@PathVariable String slug) {
        return catalog.groupView(catalog.group(slug));
    }

    @PostMapping("/practices")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_PRACTICE")
    @Operation(operationId = "createCatalogPractice")
    @ApiResponse(responseCode = "201", description = "Practice created; URL in the Location header")
    public ResponseEntity<CatalogPracticeDTO> createPractice(@RequestBody @Valid CatalogPracticeInputDTO input) {
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{slug}")
                        .buildAndExpand(input.slug())
                        .toUri())
                .body(catalog.practiceView(customization.createPractice(input)));
    }

    @PutMapping("/practices/{slug}")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_PRACTICE")
    @Operation(operationId = "updateCatalogPractice")
    public CatalogPracticeDTO updatePractice(
            @PathVariable String slug,
            @RequestBody @Valid CatalogPracticeUpdateDTO input,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        return catalog.practiceView(customization.updatePractice(slug, input, precondition(ifMatch)));
    }

    @PostMapping("/groups")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_GROUP")
    @Operation(operationId = "createCatalogGroup")
    @ApiResponse(responseCode = "201", description = "Group created; URL in the Location header")
    public ResponseEntity<CatalogGroupDTO> createGroup(@RequestBody @Valid CatalogGroupInputDTO input) {
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{slug}")
                        .buildAndExpand(input.slug())
                        .toUri())
                .body(catalog.groupView(customization.createGroup(input)));
    }

    @PutMapping("/groups/{slug}")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_GROUP")
    @Operation(operationId = "updateCatalogGroup")
    public CatalogGroupDTO updateGroup(
            @PathVariable String slug,
            @RequestBody @Valid CatalogGroupUpdateDTO input,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        return catalog.groupView(customization.updateGroup(slug, input, precondition(ifMatch)));
    }

    @GetMapping("/practices/{slug}/release")
    @Operation(operationId = "getCatalogPracticeRelease")
    public PracticeReleaseProposalDTO release(@PathVariable String slug) {
        return catalog.proposal(slug);
    }

    @PutMapping("/practices/{slug}/release")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_PRACTICE")
    @Operation(operationId = "acceptCatalogPracticeRelease")
    public CatalogPracticeDTO accept(
            @PathVariable String slug,
            @RequestBody @Valid AcceptPracticeReleaseRequestDTO input,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        return catalog.practiceView(customization.applyRelease(slug, input.choices(), precondition(ifMatch)));
    }

    @PatchMapping("/practices/{slug}/status")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_PRACTICE")
    @Operation(operationId = "setCatalogPracticeStatus")
    public CatalogPracticeDTO practiceStatus(
            @PathVariable String slug,
            @RequestBody @Valid SetCatalogStatusRequestDTO input,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        customization.setExcluded(CatalogEntryType.PRACTICE, slug, input.excluded(), precondition(ifMatch));
        return catalog.practiceView(catalog.practice(slug));
    }

    @DeleteMapping("/practices/{slug}/override")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_PRACTICE")
    @Operation(operationId = "resetCatalogPractice")
    public CatalogPracticeDTO practiceReset(
            @PathVariable String slug, @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        customization.reset(CatalogEntryType.PRACTICE, slug, precondition(ifMatch));
        return catalog.practiceView(catalog.practice(slug));
    }

    @PutMapping("/practices/{slug}/override/acknowledgement")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_PRACTICE")
    @Operation(operationId = "acknowledgeCatalogPractice")
    public CatalogPracticeDTO practiceAcknowledge(
            @PathVariable String slug, @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        customization.acknowledge(CatalogEntryType.PRACTICE, slug, precondition(ifMatch));
        return catalog.practiceView(catalog.practice(slug));
    }

    @PatchMapping("/groups/{slug}/status")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_GROUP")
    @Operation(operationId = "setCatalogGroupStatus")
    public CatalogDTO groupStatus(
            @PathVariable String slug,
            @RequestBody @Valid SetCatalogStatusRequestDTO input,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        customization.setExcluded(CatalogEntryType.GROUP, slug, input.excluded(), precondition(ifMatch));
        return catalog.view();
    }

    @DeleteMapping("/groups/{slug}/override")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_GROUP")
    @Operation(operationId = "resetCatalogGroup")
    public CatalogGroupDTO groupReset(
            @PathVariable String slug, @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        customization.reset(CatalogEntryType.GROUP, slug, precondition(ifMatch));
        return catalog.groupView(catalog.group(slug));
    }

    @PutMapping("/groups/{slug}/override/acknowledgement")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_GROUP")
    @Operation(operationId = "acknowledgeCatalogGroup")
    public CatalogGroupDTO groupAcknowledge(
            @PathVariable String slug, @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        customization.acknowledge(CatalogEntryType.GROUP, slug, precondition(ifMatch));
        return catalog.groupView(catalog.group(slug));
    }

    @PatchMapping("/groups/reorder")
    @AuditExempt(reason = "Order changes presentation only")
    @Operation(operationId = "reorderCatalogGroups")
    public CatalogDTO reorderGroups(
            @RequestBody @Valid ReorderRequestDTO input,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        customization.reorderGroups(input.slugs(), precondition(ifMatch));
        return catalog.view();
    }

    @PatchMapping("/practices/{slug}/group")
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "CATALOG_PRACTICE")
    @Operation(operationId = "moveCatalogPractice")
    public CatalogDTO move(
            @PathVariable String slug,
            @RequestBody @Valid MovePracticeRequestDTO input,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        customization.move(slug, input.groupSlug(), input.position(), precondition(ifMatch));
        return catalog.view();
    }

    @DeleteMapping("/order")
    @AuditExempt(reason = "Order changes presentation only")
    @Operation(operationId = "resetCatalogOrder")
    public CatalogDTO resetOrder(@RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        customization.resetOrder(precondition(ifMatch));
        return catalog.view();
    }

    private static @Nullable EntityTagPrecondition precondition(@Nullable String value) {
        return value == null ? null : EntityTagPrecondition.parse(value);
    }
}
