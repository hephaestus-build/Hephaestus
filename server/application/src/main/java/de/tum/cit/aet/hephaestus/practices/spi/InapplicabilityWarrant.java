package de.tum.cit.aet.hephaestus.practices.spi;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record InapplicabilityWarrant(
        List<SourceKind> consulted,
        String prerequisite,
        String ruledOutBy,
        @Nullable String hits) {
    public InapplicabilityWarrant {
        consulted = List.copyOf(consulted);
    }
}
