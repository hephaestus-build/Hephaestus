package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.dto.FeedbackModeRollupDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDTO;
import de.tum.cit.aet.hephaestus.practices.dto.SetFeedbackModeRequestDTO;
import de.tum.cit.aet.hephaestus.practices.review.feedbackmode.FeedbackModeRollupService;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.context.TeamOnly;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

/** How the workspace's practices deliver feedback: the rollup of their feedback modes, and one practice's own mode. */
@WorkspaceScopedController
@TeamOnly
@RequestMapping("/practices")
@RequiredArgsConstructor
@ConditionalOnServerRole
@Tag(name = "practice-catalog")
public class PracticeFeedbackModeController {
    private final PracticeService practices;
    private final FeedbackModeRollupService modes;
    private final CatalogOriginPresenter presenter;

    @GetMapping("/feedback-modes")
    @RequireAtLeastWorkspaceAdmin
    @Operation(operationId = "getFeedbackModeRollup")
    public FeedbackModeRollupDTO modes(WorkspaceContext ctx) {
        return modes.rollup(ctx.id());
    }

    @PatchMapping("/{practiceSlug}/feedback-mode")
    @RequireAtLeastWorkspaceAdmin
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "PRACTICE_USAGE")
    @Operation(operationId = "setPracticeFeedbackMode")
    public PracticeDTO mode(
            WorkspaceContext ctx,
            @PathVariable String practiceSlug,
            @RequestBody @Valid SetFeedbackModeRequestDTO input) {
        return presenter.present(ctx.id(), practices.setFeedbackMode(ctx, practiceSlug, input.feedbackMode()));
    }
}
