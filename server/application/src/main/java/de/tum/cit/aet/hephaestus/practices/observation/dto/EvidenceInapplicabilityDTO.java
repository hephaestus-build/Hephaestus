package de.tum.cit.aet.hephaestus.practices.observation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Why the practice had nothing to judge in this work, recorded when the review found it did not apply.
 *
 * <p>Read from {@code evidence.inapplicability}, and absent unless all three parts are there — the same
 * rule as {@link EvidenceSearchDTO}, for the same reason.
 */
@Schema(description = "Why this practice had nothing to judge in this work")
public record EvidenceInapplicabilityDTO(
        @NonNull @Schema(description = "What this practice looks for")
        String subject,

        @NonNull @Schema(description = "The sources the review read to conclude this, by source kind")
        List<String> consulted,

        @NonNull @Schema(description = "The fact about this work that means there was nothing to look at")
        String ruledOutBy) {
    public EvidenceInapplicabilityDTO {
        consulted = List.copyOf(consulted);
    }

    static @Nullable EvidenceInapplicabilityDTO from(JsonNode inapplicability) {
        if (!inapplicability.isObject()) return null;
        String subject = EvidenceWarrants.text(inapplicability, "subject");
        String ruledOutBy = EvidenceWarrants.text(inapplicability, "ruledOutBy");
        List<String> consulted = EvidenceWarrants.sourceKinds(inapplicability);
        return subject == null || ruledOutBy == null || consulted.isEmpty()
                ? null
                : new EvidenceInapplicabilityDTO(subject, consulted, ruledOutBy);
    }
}
