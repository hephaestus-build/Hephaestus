package de.tum.cit.aet.hephaestus.workspace.spi;

import java.util.List;

/** Monitor identities across workspaces; callers lock and recheck an exclusion before withdrawal. */
public interface RepositoryMonitorQuery {
    List<MonitorRef> findByNameIgnoringCase(String nameWithOwner);

    record MonitorRef(long workspaceId, long monitorId) {}
}
