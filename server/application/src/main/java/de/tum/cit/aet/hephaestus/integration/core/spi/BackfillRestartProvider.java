package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider.SyncTarget;
import java.util.Optional;

/** Reopens completed history without changing recent-sync timestamps. */
public interface BackfillRestartProvider {
    int MINIMUM_GAP = 20;

    static boolean hasMaterialGap(long stored, long expected) {
        return expected - stored >= MINIMUM_GAP && stored < expected * 0.8;
    }

    Optional<SyncTarget> restartCompletedBackfill(
            long workspaceId, long syncTargetId, int providerCount, long storedCount);
}
