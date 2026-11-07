package de.tum.cit.aet.hephaestus.practices.spi;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** @param hits what the matches of the search are, and why they do not show what the result calls absent */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchWarrant(
        List<SourceKind> consulted,
        String lookedFor,
        String boundary,
        @Nullable String hits) {
    public SearchWarrant {
        consulted = List.copyOf(consulted);
    }
}
