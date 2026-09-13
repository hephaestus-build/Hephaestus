package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.AuditLedger;
import de.tum.cit.aet.hephaestus.core.Audited;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireWorkspaceOwner;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@WorkspaceScopedController
@ConditionalOnServerRole
@RequestMapping("/onboarding")
@RequiredArgsConstructor
public class WorkspaceOnboardingController {
    private final WorkspaceOnboardingService service;

    public record MemberAiChoiceRequestDTO(@NonNull @NotNull MemberAiChoice choice) {}

    public record OnboardingCompletionRequestDTO(long revision) {}

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get your first-visit setup and AI choice in this workspace")
    public WorkspaceOnboardingDTO getMemberOnboarding(WorkspaceContext context) {
        return service.state(context, CurrentAccount.requireId());
    }

    @PutMapping("/me/ai-choice")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Change your AI choice without changing workspace membership")
    public WorkspaceOnboardingDTO updateMemberAiChoice(
            WorkspaceContext context, @Valid @RequestBody MemberAiChoiceRequestDTO request) {
        return service.choose(context, CurrentAccount.requireId(), request.choice());
    }

    @PutMapping("/me/completion")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Finish first-visit setup after required account links are complete")
    public WorkspaceOnboardingDTO completeMemberOnboarding(
            WorkspaceContext context, @Valid @RequestBody OnboardingCompletionRequestDTO request) {
        return service.complete(context, CurrentAccount.requireId(), request.revision());
    }

    @PutMapping("/me/dismissal")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Continue to the workspace without treating dismissal as an AI choice or completed setup")
    public WorkspaceOnboardingDTO dismissMemberOnboarding(WorkspaceContext context) {
        return service.dismiss(context, CurrentAccount.requireId());
    }

    // Readable by every workspace admin: the model assignment page previews who the undeclared slot
    // serves, which depends on whether the choice is required. Changing the settings stays with owners.
    @GetMapping("/settings")
    @RequireAtLeastWorkspaceAdmin
    @Operation(summary = "Get this workspace's member-onboarding settings")
    public WorkspaceOnboardingSettingsDTO getMemberOnboardingSettings(WorkspaceContext context) {
        return service.settings(context);
    }

    @GetMapping("/settings/links")
    @RequireWorkspaceOwner
    @Operation(summary = "List workspace account links available for onboarding requirements")
    public java.util.List<WorkspaceOnboardingDTO.WorkspaceOnboardingLinkDTO> getMemberOnboardingLinkOptions(
            WorkspaceContext context) {
        return service.linkOptions(context, CurrentAccount.requireId());
    }

    @PutMapping("/settings")
    @RequireWorkspaceOwner
    @Audited(ledger = AuditLedger.CONFIG_AUDIT, type = "WORKSPACE_FEATURES")
    @Operation(summary = "Configure first-visit setup and required workspace account links")
    public WorkspaceOnboardingSettingsDTO updateMemberOnboardingSettings(
            WorkspaceContext context, @Valid @RequestBody WorkspaceOnboardingSettingsDTO request) {
        return service.configure(context, CurrentAccount.requireId(), request);
    }
}
