package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnServerRole
@RequestMapping("/user/public-activity/workspaces/{slug}/onboarding")
@WorkspaceAgnostic("Public-workspace visitors need no membership; the account always comes from authentication")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class PublicActivityOnboardingController {
    private final PublicActivityOnboardingService service;

    public record PublicActivityOnboardingRequestDTO(
            @NonNull @NotNull Boolean visible) {}

    public record PublicActivityOnboardingDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean seen,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean visible) {}

    @GetMapping
    @Operation(operationId = "getPublicActivityOnboarding", summary = "Check your public-workspace onboarding")
    public PublicActivityOnboardingDTO get(@PathVariable String slug) {
        return service.get(CurrentAccount.requireId(), slug);
    }

    @PutMapping
    @Operation(
            operationId = "answerPublicActivityOnboarding",
            summary = "Show or hide yourself and finish this workspace's onboarding")
    public PublicActivityOnboardingDTO answer(
            @PathVariable String slug, @Valid @RequestBody PublicActivityOnboardingRequestDTO body) {
        return service.answer(CurrentAccount.requireId(), slug, body.visible());
    }
}
