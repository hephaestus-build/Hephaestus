package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ReviewScriptQuery {
    /** The script runs of each review, keyed by review id; a review without runs is absent. */
    Map<UUID, List<PracticeScriptRunDTO>> forReviews(long workspaceId, Collection<UUID> reviewIds);
}
