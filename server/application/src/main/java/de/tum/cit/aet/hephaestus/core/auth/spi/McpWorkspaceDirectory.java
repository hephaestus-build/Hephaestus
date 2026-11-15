package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.Optional;

/** What the MCP authorization server needs to know about a workspace; implemented by the workspace module. */
public interface McpWorkspaceDirectory {

    /** An active workspace, addressed by its current slug. */
    record McpWorkspace(long id, String slug, String displayName, boolean adminPasskeyRequired) {}

    /** The active workspace whose current slug this is; a former slug finds nothing. */
    Optional<McpWorkspace> findActiveBySlug(String slug);

    /** Whether the account holds any current membership of the workspace; elevation never counts. */
    boolean hasMembership(long accountId, long workspaceId);

    /** Whether the account holds a real ADMIN or OWNER membership of the workspace; elevation never counts. */
    boolean hasAdminMembership(long accountId, long workspaceId);
}
