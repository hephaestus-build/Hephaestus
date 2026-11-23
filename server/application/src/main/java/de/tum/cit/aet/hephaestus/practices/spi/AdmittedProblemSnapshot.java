package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;

@RawObservationAccess
public record AdmittedProblemSnapshot(UUID id, ProblemCandidate candidate) {}
