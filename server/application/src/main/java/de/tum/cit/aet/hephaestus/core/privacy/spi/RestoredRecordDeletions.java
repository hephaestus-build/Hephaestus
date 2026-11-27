package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.Set;
import java.util.UUID;

/** Reapplies exact record deletions under the given account's owner policy. */
public interface RestoredRecordDeletions {
    void deleteAgain(long accountId, Set<UUID> recordIds);
}
