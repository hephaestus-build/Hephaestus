package de.tum.cit.aet.hephaestus.core.audit.spi;

import de.tum.cit.aet.hephaestus.core.web.CursorPageDTO;
import org.jspecify.annotations.Nullable;

/**
 * Read side of the config audit trail. A port because the workspace-scoped viewer lives in the
 * {@code workspace} module (it needs {@code WorkspaceContext}), and {@code core} must not depend on
 * {@code workspace}.
 */
public interface ConfigAuditQuery {
    /**
     * History for one workspace, newest first. The workspace is a required argument, not a filter:
     * callers cannot widen the result by omitting it.
     */
    CursorPageDTO<ConfigAuditEntryViewDTO> listForWorkspace(
            Long workspaceId, ConfigAuditFilter filter, @Nullable String cursor, int size);

    /** How many of one workspace's changes the filter matches. */
    long countForWorkspace(Long workspaceId, ConfigAuditFilter filter);

    /**
     * Cross-workspace history for instance admins, newest first. Callers MUST gate this on the
     * {@code app_admin} authority.
     *
     * @param workspaceId optional narrowing; null spans every workspace
     */
    CursorPageDTO<ConfigAuditEntryViewDTO> listForAdmin(
            @Nullable Long workspaceId, ConfigAuditFilter filter, @Nullable String cursor, int size);

    /** How many changes the filter matches. Callers MUST gate this on the {@code app_admin} authority. */
    long countForAdmin(@Nullable Long workspaceId, ConfigAuditFilter filter);
}
