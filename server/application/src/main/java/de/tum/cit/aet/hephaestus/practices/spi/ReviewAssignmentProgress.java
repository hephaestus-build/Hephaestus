package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ReviewAssignmentProgress {
    /** Counts ended practices for globally unique job IDs supplied by the shared model-pool scheduler. */
    Map<UUID, Integer> endedPracticeCounts(List<UUID> jobIds);
}
