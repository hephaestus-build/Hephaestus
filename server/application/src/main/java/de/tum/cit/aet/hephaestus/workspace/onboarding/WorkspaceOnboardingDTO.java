package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record WorkspaceOnboardingDTO(
        @NonNull String workspaceName,
        @NonNull boolean enabled,
        @NonNull boolean needsWelcome,
        @NonNull long revision,
        @NonNull boolean aiChoiceRequired,
        @Nullable MemberAiChoice aiChoice,
        @NonNull boolean completed,
        @NonNull List<WorkspaceAiOptionDTO> aiOptions,
        @NonNull List<WorkspaceOnboardingLinkDTO> links) {
    public record WorkspaceAiOptionDTO(
            @NonNull MemberAiChoice choice,
            @NonNull boolean practiceReviewsReady,
            @NonNull boolean mentorReady,
            @Nullable MemberAiChoice sameModelsAs) {}

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
