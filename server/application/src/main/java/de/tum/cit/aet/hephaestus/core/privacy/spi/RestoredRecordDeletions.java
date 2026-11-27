package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.Set;
import java.util.UUID;

/** Reapplies exact record deletions from the private recovery manifest. */
public interface RestoredRecordDeletions {
    void deleteAgain(Set<UUID> recordIds);
}
