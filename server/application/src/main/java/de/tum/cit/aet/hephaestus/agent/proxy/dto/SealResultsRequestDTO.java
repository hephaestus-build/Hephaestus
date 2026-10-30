package de.tum.cit.aet.hephaestus.agent.proxy.dto;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/** Identity of the retained set to seal; candidates can only enter through per-practice staging. */
public record SealResultsRequestDTO(
        int contractVersion,
        @NonNull UUID reviewId,
        @NonNull UUID jobId,
        int attempt) {
    public SealResultsRequestDTO {
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(jobId, "jobId");
        if (contractVersion != SandboxLayout.PRACTICE_CONTRACT_VERSION)
            throw new IllegalArgumentException("Unsupported practice script contract");
        if (attempt < 0) throw new IllegalArgumentException("Attempt must not be negative");
    }
}
