package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.List;
import java.util.UUID;

@RawObservationAccess
public interface AdmittedResultQuery {
    List<AdmittedResultSnapshot> forAttempt(long workspaceId, UUID reviewId, UUID jobId, int attempt);
}
