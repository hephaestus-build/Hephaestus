package de.tum.cit.aet.hephaestus.agent.proxy.dto;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public record AdmissionRequestDTO(
        int contractVersion,
        UUID reviewId,
        UUID jobId,
        int attempt,
        @Size(max = 1) List<SubmittedResultDTO> results,
        @NonNull @Size(max = 1) List<RuntimeNonResultDTO> nonResults) {
    public AdmissionRequestDTO {
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(jobId, "jobId");
        results = List.copyOf(results);
        nonResults = List.copyOf(nonResults);
        if (contractVersion != SandboxLayout.PRACTICE_CONTRACT_VERSION)
            throw new IllegalArgumentException("Unsupported practice script contract");
        if (attempt < 0) throw new IllegalArgumentException("Attempt must not be negative");
    }
}
