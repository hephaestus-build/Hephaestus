package de.tum.cit.aet.hephaestus.workspace.spi;

import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import java.util.List;
import java.util.Optional;

/** Repository monitors by their own id, and the mirrored repositories they read. */
public interface RepositoryMonitorSources {
    /** A monitor by the id that a read of its workspace returned. */
    Optional<RepositoryToMonitor> find(long monitorId);

    /** The mirrored repositories that a monitor reads. */
    List<Long> sourceRepositoryIds(long monitorId);
}
