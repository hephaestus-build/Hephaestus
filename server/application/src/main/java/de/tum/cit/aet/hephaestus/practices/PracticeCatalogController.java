package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.RecentSignInExempt;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.PublicApiUrls;
import de.tum.cit.aet.hephaestus.practices.dto.CreatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDefinitionOptionsDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeVersionDTO;
import de.tum.cit.aet.hephaestus.practices.dto.UpdatePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@WorkspaceScopedController
@TeamOnly
@RequestMapping("/practices")
@RequiredArgsConstructor
@ConditionalOnServerRole
public class PracticeCatalogController {
    private final PracticeService practices;
    private final PracticeReleaseService releases;
    private final CatalogOriginPresenter presenter;
    private final PracticeDefinitionOptionsService options;
    private final PublicApiUrls publicApiUrls;

    @GetMapping
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "listPractices")
    public List<PracticeDTO> list(
            WorkspaceContext ctx, @RequestParam(required = false) @Nullable FeedbackMode feedbackMode) {
        return presenter.presentPractices(ctx.id(), practices.listPractices(ctx, feedbackMode));
    }

    @GetMapping("/{practiceSlug}")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "getPractice")
    public PracticeDTO get(WorkspaceContext ctx, @PathVariable String practiceSlug) {
        return presenter.present(ctx.id(), practices.getPractice(ctx, practiceSlug));
    }

    @PostMapping
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(operationId = "createPractice")
    @ApiResponse(responseCode = "201", description = "Practice created")
    public ResponseEntity<PracticeDTO> create(
            WorkspaceContext ctx, @RequestBody @Valid CreatePracticeRequestDTO input) {
        var created = practices.createPractice(ctx, input);
        return ResponseEntity.created(publicApiUrls.uri(
                        "/workspaces/{workspaceSlug}/practices/{slug}", ctx.slug(), created.getSlug()))
                .body(presenter.present(ctx.id(), created));
    }

    @PatchMapping("/{practiceSlug}")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(operationId = "updatePractice")
    public PracticeDTO update(
            WorkspaceContext ctx,
            @PathVariable String practiceSlug,
            @RequestBody @Valid UpdatePracticeRequestDTO input) {
        return presenter.present(ctx.id(), practices.updatePractice(ctx, practiceSlug, input));
    }

    @RecentSignInExempt(reason = "a workspace admin can add the practice again from the catalog")
    @DeleteMapping("/{practiceSlug}")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(operationId = "deletePractice")
    @ApiResponse(responseCode = "204", description = "Practice deleted")
    public ResponseEntity<Void> delete(WorkspaceContext ctx, @PathVariable String practiceSlug) {
        practices.deletePractice(ctx, practiceSlug);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{practiceSlug}/versions")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "listPracticeVersions")
    public List<PracticeVersionDTO> history(WorkspaceContext ctx, @PathVariable String practiceSlug) {
        return presenter.history(practices.getPractice(ctx, practiceSlug));
    }

    @GetMapping("/definition-options")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "getPracticeDefinitionOptions")
    public PracticeDefinitionOptionsDTO options(WorkspaceContext ctx) {
        return options.options(ctx.id());
    }

    @RecentSignInExempt(
            reason = "returns the practice to the catalog version, which a workspace admin can customize again")
    @DeleteMapping("/{practiceSlug}/catalog-customization")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(operationId = "resetPracticeToCatalog")
    public PracticeDTO reset(
            WorkspaceContext ctx,
            @PathVariable String practiceSlug,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        return presenter.present(
                ctx.id(),
                releases.reset(ctx, practiceSlug, ifMatch == null ? null : EntityTagPrecondition.parse(ifMatch)));
    }
}
