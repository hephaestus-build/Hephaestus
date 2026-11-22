package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.dto.BindPracticeGroupRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.MovePracticeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** Where a practice sits: the group it belongs to, and its position in that group. */
@WorkspaceScopedController
@RequestMapping("/practices")
@RequiredArgsConstructor
@ConditionalOnServerRole
@Tag(name = "practice-catalog")
public class PracticeGroupPlacementController {
    private final PracticeService practices;
    private final PracticeGroupService groups;
    private final CatalogOriginPresenter presenter;

    @PutMapping("/{practiceSlug}/group")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(operationId = "bindPracticeGroup")
    public PracticeDTO bind(
            WorkspaceContext ctx,
            @PathVariable String practiceSlug,
            @RequestBody @Valid BindPracticeGroupRequestDTO input) {
        return presenter.present(ctx.id(), groups.bindPractice(ctx, practiceSlug, input.groupSlug()));
    }

    @PatchMapping("/{practiceSlug}/group")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_DEFINITION")
    @Operation(operationId = "movePractice")
    public List<PracticeDTO> move(
            WorkspaceContext ctx, @PathVariable String practiceSlug, @RequestBody @Valid MovePracticeRequestDTO input) {
        return presenter.presentPractices(
                ctx.id(), practices.movePractice(ctx, practiceSlug, input.groupSlug(), input.position()));
    }
}
