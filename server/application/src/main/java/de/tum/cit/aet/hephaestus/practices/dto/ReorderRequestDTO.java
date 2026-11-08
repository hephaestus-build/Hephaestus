package de.tum.cit.aet.hephaestus.practices.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.practices.ClosedPracticeInput;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReorderRequestDTO(
        @JsonProperty(required = true) @NotNull @Size(max = 10_000)
        List<@NotNull @ValidDefinitionSlug String> slugs) implements ClosedPracticeInput {
    public ReorderRequestDTO {
        slugs = List.copyOf(slugs);
    }
}
