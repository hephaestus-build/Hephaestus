package de.tum.cit.aet.hephaestus.workspace.access;

import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessFailure;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Administrative status contains no credentials, handoff hashes or raw directory subjects. */
public record GitHubAccessTargetDTO(
        @NonNull Long id,
        @NonNull Long connectionId,
        @NonNull String organization,
        @Nullable String team,
        @NonNull Long installationId,
        @Nullable Long organizationId,
        @Nullable Long scopeId,
        GitHubAccessTarget.@NonNull Status status,
        @NonNull Boolean paused,
        @NonNull Boolean authorityHeld,
        @NonNull Long configurationVersion,
        @NonNull Set<String> draftGroupIds,
        @NonNull Set<String> approvedGroupIds,
        @NonNull Boolean authorized,
        @Nullable Instant approvedAt,
        @Nullable Instant lastAttemptAt,
        @Nullable Instant lastConfirmedAt,
        GitHubAccessFailure.@Nullable Reason failureCode,
        @Nullable String failureReason,
        @Nullable Instant retryAt,
        @Nullable GitHubAccessPreviewDTO preview,
        @NonNull List<GitHubAccessMemberDTO> members,
        @NonNull List<GitHubAccessActionDTO> actions) {
    public record GitHubAccessMemberDTO(
            @NonNull Long githubUserId,
            @Nullable Long accountId,
            @NonNull String displayName,
            @Nullable String githubLogin,
            @NonNull Boolean enrolled,
            @NonNull Boolean managed,
            @NonNull Boolean manualException,
            @Nullable String exceptionReason,
            @NonNull Boolean revocationRequested,
            GitHubAccessClient.@Nullable State externalState,
            @Nullable Instant confirmedAt,
            @Nullable String blocker) {}

    public record GitHubAccessActionDTO(
            @NonNull Long id,
            @NonNull Long githubUserId,
            GitHubAccessAction.@NonNull Type type,
            GitHubAccessAction.@NonNull Status status,
            @NonNull Long configurationVersion,
            @NonNull Instant createdAt,
            @Nullable Instant confirmedAt,
            @Nullable String failureReason,
            @Nullable Instant retryAt) {}

    public record GitHubAccessPreviewDTO(
            @NonNull Instant capturedAt,
            @NonNull Integer eligiblePeople,
            @NonNull Integer awaitingIdentity,
            @NonNull Integer unlinkedInvitations,
            @NonNull List<GitHubAccessInventoryMemberDTO> inventory) {}

    public record GitHubAccessInventoryMemberDTO(
            @NonNull Long githubUserId,
            @NonNull String login,
            GitHubAccessClient.@NonNull State state,
            @Nullable String explanation) {}
}
