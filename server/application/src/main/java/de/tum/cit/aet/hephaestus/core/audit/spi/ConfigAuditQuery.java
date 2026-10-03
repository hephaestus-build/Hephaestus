package de.tum.cit.aet.hephaestus.core.audit.spi;

import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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
    Page<ConfigAuditEntryViewDTO> listForWorkspace(Long workspaceId, ConfigAuditFilter filter, Pageable pageable);

    /**
     * Cross-workspace history for instance admins, newest first. Callers MUST gate this on the
     * {@code app_admin} authority.
     *
     * @param workspaceId optional narrowing; null spans every workspace
     */
    Page<ConfigAuditEntryViewDTO> listForAdmin(@Nullable Long workspaceId, ConfigAuditFilter filter, Pageable pageable);
}
