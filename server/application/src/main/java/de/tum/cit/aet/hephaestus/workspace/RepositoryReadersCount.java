package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.integration.core.spi.ProofReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProofState;
import de.tum.cit.aet.hephaestus.integration.core.spi.VisibilityClass;

/** How many monitored repositories hold one visibility class and proof, instance-wide. */
public record RepositoryReadersCount(
        VisibilityClass visibilityClass, ProofState proofState, ProofReason proofReason, long monitors) {}
