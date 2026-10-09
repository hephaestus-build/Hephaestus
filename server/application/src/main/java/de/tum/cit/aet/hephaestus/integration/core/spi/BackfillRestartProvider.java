package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import java.util.Optional;

/** Reopens completed history without changing recent-sync timestamps. */
public interface BackfillRestartProvider {
    Optional<SyncTarget> restartCompletedBackfill(long workspaceId, long syncTargetId);
}
