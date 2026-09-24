package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.workspace.spi.AiModelBrand;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
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
        @NonNull boolean enabled,
        @NonNull boolean needsSetup,
        @NonNull boolean aiChoiceRequired,
        @Nullable MemberAiChoice aiChoice,
        @NonNull List<WorkspaceAiOptionDTO> aiOptions,
        @NonNull List<WorkspaceOnboardingLinkDTO> links) {
    public record WorkspaceAiOptionDTO(
            @NonNull MemberAiChoice choice,
            @NonNull boolean practiceReviewsReady,
            @NonNull boolean mentorReady,
            @NonNull List<WorkspaceAiModelDTO> models) {}

    /** A model this answer would use here, with its declared brand when known. */
    public record WorkspaceAiModelDTO(
            @NonNull String name, @Nullable AiModelBrand brand) {}

    public record WorkspaceOnboardingLinkDTO(
            @NonNull long connectionId,
            @NonNull String displayName,
            @NonNull String providerType,
            @Nullable String registrationId,
            @Nullable String teamName,
            @NonNull boolean required,
            @NonNull boolean available,
            @NonNull boolean linked) {}
}
