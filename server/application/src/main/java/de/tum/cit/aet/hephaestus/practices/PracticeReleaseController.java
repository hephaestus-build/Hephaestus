package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.practices.dto.AcceptPracticeReleaseRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeReleaseProposalDTO;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

@WorkspaceScopedController
@RequestMapping("/practices/releases")
@Tag(name = "Practice Catalog", description = "Review catalogue changes before they affect a workspace")
@RequiredArgsConstructor
public class PracticeReleaseController {

    private final PracticeReleaseService releases;
    private final CatalogOriginPresenter presenter;

    @GetMapping
    @RequireAtLeastWorkspaceAdmin
    @Operation(summary = "List pending practice releases", operationId = "listPracticeReleases")
    public ResponseEntity<List<PracticeReleaseProposalDTO>> list(WorkspaceContext ctx) {
        return ResponseEntity.ok(releases.list(ctx));
    }

    @GetMapping("/{slug}")
    @RequireAtLeastWorkspaceAdmin
    @Operation(summary = "Compare the adopted, current, and offered practice", operationId = "getPracticeRelease")
    public ResponseEntity<PracticeReleaseProposalDTO> get(WorkspaceContext ctx, @PathVariable String slug) {
        PracticeReleaseProposalDTO proposal = releases.get(ctx, slug);
        return ResponseEntity.ok()
                .eTag(EntityTagPrecondition.format(proposal.etag()))
                .body(proposal);
    }

    @PutMapping("/{slug}")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(summary = "Accept selected fields from a practice release", operationId = "acceptPracticeRelease")
    public ResponseEntity<PracticeDTO> accept(
            WorkspaceContext ctx,
            @PathVariable String slug,
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch,
            @Valid @RequestBody AcceptPracticeReleaseRequestDTO request) {
        Practice practice = releases.accept(ctx, slug, precondition(ifMatch), request.choices());
        return ResponseEntity.ok(presenter.present(ctx.id(), practice));
    }

    @DeleteMapping("/{slug}")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(summary = "Decline this version of a practice release", operationId = "declinePracticeRelease")
    @ApiResponse(responseCode = "204", description = "The offered version was declined")
    public ResponseEntity<Void> decline(
            WorkspaceContext ctx,
            @PathVariable String slug,
            @Parameter(required = true) @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) @Nullable
                    String ifMatch) {
        releases.decline(ctx, slug, precondition(ifMatch));
        return ResponseEntity.noContent().build();
    }

    private static @Nullable EntityTagPrecondition precondition(@Nullable String ifMatch) {
        return ifMatch == null ? null : EntityTagPrecondition.parse(ifMatch);
    }
}
