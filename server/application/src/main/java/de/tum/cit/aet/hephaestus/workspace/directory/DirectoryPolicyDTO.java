package de.tum.cit.aet.hephaestus.workspace.directory;

import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Administrative view; raw directory subjects, access tokens and client secrets never cross HTTP. */
public record DirectoryPolicyDTO(
        @NonNull Long connectionId,
        @NonNull String registrationId,
        @NonNull String issuer,
        DirectoryPolicy.@NonNull Status status,
        DirectoryPolicy.@NonNull Health health,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long configurationVersion,
        @NonNull Set<String> draftGroupIds,
        @NonNull Set<String> approvedGroupIds,
        @Nullable Instant approvedAt,
        @Nullable Instant lastAttemptAt,
        @Nullable String failureReason,
        @NonNull List<String> blockers,
        @Nullable DirectoryEvidenceDTO approvedEvidence,
        @Nullable DirectoryEvidenceDTO previewEvidence,
        @NonNull List<DirectoryMemberDTO> members) {

    public record DirectoryEvidenceDTO(
            @NonNull Instant startedAt,
            @NonNull Instant completedAt,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean fresh,

            @NonNull Map<String, String> groupNames,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int eligiblePeople,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int awaitingIdentity,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int additions,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            int removals) {}

    public record DirectoryMemberDTO(
            @NonNull Long accountId,
            @NonNull String displayName,
            @Nullable WorkspaceRole role,
            WorkspaceAccountMembership.@Nullable Source source,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean suspended,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean eligible,

            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            boolean adoptable,

            @NonNull Change change) {}

    public enum Change {
        ADD,
        REMOVE,
        PROTECTED,
        UNCHANGED
    }
}
