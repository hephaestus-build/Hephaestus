package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.List;
import java.util.UUID;

@RawObservationAccess
public record AdmittedResultSnapshot(UUID id, ResultCandidate candidate, List<AdmittedProblemSnapshot> problems) {
    public AdmittedResultSnapshot {
        problems = List.copyOf(problems);
    }
}
