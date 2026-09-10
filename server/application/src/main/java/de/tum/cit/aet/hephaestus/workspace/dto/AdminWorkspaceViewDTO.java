package de.tum.cit.aet.hephaestus.workspace.dto;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Metadata-only view of a workspace for the instance-admin overview. DELIBERATELY excludes any tenant
 * content (member lists, repo names, etc.) — an instance admin sees the shape of a workspace, and
 * reaches its content only via audited impersonation. Owner is the oldest active account with the OWNER role; SCM identities are not required.
 */
@Schema(description = "Metadata-only workspace summary for the instance-admin overview")
public record AdminWorkspaceViewDTO(
        @NonNull Long id,
        @NonNull String workspaceSlug,
        @NonNull String displayName,
        @NonNull String status,
        @NonNull String accountLogin,
        @Nullable IdentityProviderType providerType,
        @Nullable String ownerDisplayName,
        @Nullable Long ownerAccountId,
        @NonNull Long memberCount,
        @NonNull Instant createdAt) {}
