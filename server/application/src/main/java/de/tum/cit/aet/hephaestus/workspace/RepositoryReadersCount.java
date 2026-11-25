package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.integration.core.spi.ProofReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProofState;
import de.tum.cit.aet.hephaestus.integration.core.spi.VisibilityClass;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * How many monitored repositories hold one visibility class and proof, instance-wide, and since when the oldest of them
 * in an active workspace has waited: a proven one since the provider read that last renewed it, any other one since it
 * last changed.
 */
public record RepositoryReadersCount(
        VisibilityClass visibilityClass,
        ProofState proofState,
        ProofReason proofReason,
        long monitors,
        @Nullable Instant oldestActiveWait) {}
