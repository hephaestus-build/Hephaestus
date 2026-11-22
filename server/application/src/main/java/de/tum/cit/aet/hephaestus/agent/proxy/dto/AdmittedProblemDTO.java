package de.tum.cit.aet.hephaestus.agent.proxy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.practices.spi.ClaimBasis;
import de.tum.cit.aet.hephaestus.practices.spi.Severity;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdmittedProblemDTO(
        UUID id,
        Severity severity,
        String summary,
        UUID resultId,
        int position,
        @Nullable String key,
        @Nullable String explanation,
        List<ProjectedCitationDTO> citations,
        @Nullable ClaimBasis basis,
        @Nullable String nextStep,
        boolean publicEligible,
        // The identity a comparison matches the problem by; none for a problem sealed before identities were recorded,
        // and for one without a key and without a witness citation.
        @Nullable String identity) {
    public AdmittedProblemDTO {
        citations = List.copyOf(citations);
    }
}
