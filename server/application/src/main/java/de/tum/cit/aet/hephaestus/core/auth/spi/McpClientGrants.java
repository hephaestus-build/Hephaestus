package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The MCP client grants of a workspace, as its admins list and revoke them (ADR 0110 decision 17). */
public interface McpClientGrants {

    /** One active grant. {@code clientName} is the client's self-declared name: plain text. */
    record Grant(
            UUID id, String clientHost, String clientName, Long accountId, List<String> scopes, @Nullable Instant approvedAt) {
        public Grant {
            scopes = List.copyOf(scopes);
        }
    }

    List<Grant> activeGrants(Long workspaceId);

    /** Revokes a grant of this workspace and ends its session; false when the workspace has no such open grant. */
    boolean revoke(Long workspaceId, UUID grantId, Long actingAccountId);
}
