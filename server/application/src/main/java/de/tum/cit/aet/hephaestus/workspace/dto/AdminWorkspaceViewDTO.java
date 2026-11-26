package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.core.ShownCount;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.workspace.Workspace.WorkspaceStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Metadata-only view of a workspace for the instance-admin overview. DELIBERATELY excludes any tenant
 * content (member lists, repo names, etc.): an instance admin sees the shape of a workspace, and never a person's
 * private content (ADR 0164). Owner is the oldest OWNER-role member.
 */
@Schema(description = "Metadata-only workspace summary for the instance-admin overview")
public record AdminWorkspaceViewDTO(
        @NonNull Long id,
        @NonNull String workspaceSlug,
        @NonNull String displayName,
        @NonNull WorkspaceStatus status,
        @NonNull String accountLogin,
        @Nullable IdentityProviderType providerType,
        @Nullable String ownerLogin,

        @Schema(
                implementation = Integer.class,
                description = "Members the workspace's member list shows. Absent for 1 to 4")
        @Nullable
        ShownCount memberCount,

        @NonNull Instant createdAt) {}
