package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Account access, independent of whether this developer has an SCM profile. */
public record WorkspaceAccountMembershipDTO(
        @NotNull Long accountId,
        @NotNull String displayName,
        @NotNull WorkspaceRole role,
        WorkspaceAccountMembership.@Nullable Source source,
        @NotNull boolean suspended,
        @Nullable Instant createdAt,
        @Nullable Instant expiresAt,
        @Nullable String scmUserLogin) {}
