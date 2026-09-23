package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The signed-in account's AI choice, absent until the person has answered, and the models each
 * answer would use across all their workspaces, so User settings can show the same vendor marks as
 * a workspace's setup page.
 */
public record AccountAiChoiceDTO(
        @Nullable MemberAiChoice choice,
        @Nullable Instant updatedAt,
        @NonNull List<AccountAiOptionDTO> options) {
    public record AccountAiOptionDTO(
            @NonNull MemberAiChoice choice, @NonNull List<WorkspaceOnboardingDTO.WorkspaceAiModelDTO> models) {}
}
