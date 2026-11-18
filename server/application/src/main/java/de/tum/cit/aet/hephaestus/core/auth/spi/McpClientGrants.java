package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The MCP client grants of a workspace, as its admins list and revoke them (ADR 0110 decision 17). */
public interface McpClientGrants {

    /**
     * One active grant. {@code clientName} is the client's self-declared name: plain text. {@code expiresAt} is when its
     * client session lapses: {@code IDLE_LAPSE} after its last refresh, so each use moves it.
     */
    record Grant(
            UUID id,
            String clientHost,
            String clientName,
            Long accountId,
            List<String> scopes,
            @Nullable Instant approvedAt,
            boolean passkeyBacked,
            @Nullable Instant lastUsedAt,
            Instant expiresAt) {
        public Grant {
            scopes = List.copyOf(scopes);
        }
    }

    List<Grant> activeGrants(Long workspaceId);

    /**
     * Records that a client's tool call succeeded, at most once in each {@code LAST_USED_PRECISION}. A call that the
     * policy or the tool refused is not a use, so it never keeps a refused client looking busy.
     */
    void recordUse(long workspaceId, UUID grantId);

    /** Revokes a grant of this workspace and ends its session; false when the workspace has no such open grant. */
    boolean revoke(Long workspaceId, UUID grantId, Long actingAccountId);
}
