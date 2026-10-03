package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.workspace.spi.AiModelBrand;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import de.tum.cit.aet.hephaestus.workspace.spi.LlmConnectionPlatform;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One member's view of setup in one workspace. {@code aiChoice} is the account's answer, the same in
 * every workspace; {@code aiOptions} says what this workspace has set up under each answer, which is
 * what differs between workspaces.
 */
public record WorkspaceOnboardingDTO(
        @NonNull String workspaceName,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean enabled,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean needsSetup,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean aiChoiceRequired,
        @Nullable MemberAiChoice aiChoice,
        @NonNull List<WorkspaceAiOptionDTO> aiOptions,
        @NonNull List<WorkspaceOnboardingLinkDTO> links) {
    public record WorkspaceAiOptionDTO(
            @NonNull MemberAiChoice choice,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean practiceReviewsReady,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean mentorReady,
            @NonNull List<WorkspaceAiModelDTO> models) {}

    /** A model this answer would use here. */
    public record WorkspaceAiModelDTO(
            @NonNull String name,
            @Nullable AiModelBrand brand,
            @Nullable LlmConnectionPlatform connectionPlatform,
            @NonNull DataHandlingTier dataHandlingTier) {}

    public record WorkspaceOnboardingLinkDTO(
            @Schema(requiredMode = RequiredMode.REQUIRED) long connectionId,
            @NonNull String displayName,
            @NonNull String providerType,
            @Nullable String registrationId,
            @Nullable String teamName,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean required,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean available,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean linked) {}
}
