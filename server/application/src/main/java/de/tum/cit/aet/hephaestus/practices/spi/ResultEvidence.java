package de.tum.cit.aet.hephaestus.practices.spi;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
@RawObservationAccess
public record ResultEvidence(
        List<EvidenceCitation> citations,
        @Nullable SearchWarrant search,
        @Nullable InapplicabilityWarrant inapplicability,
        List<OpenQuestion> openQuestions) {
    public ResultEvidence {
        citations = List.copyOf(citations);
        openQuestions = List.copyOf(openQuestions);
    }
}
