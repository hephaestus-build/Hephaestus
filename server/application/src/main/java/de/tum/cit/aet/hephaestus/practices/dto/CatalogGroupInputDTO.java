package de.tum.cit.aet.hephaestus.practices.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import de.tum.cit.aet.hephaestus.practices.ClosedPracticeInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CatalogGroupInputDTO(
        @JsonProperty(required = true) @ValidDefinitionSlug String slug,
        @JsonUnwrapped @NotNull @Valid CatalogGroupUpdateDTO content)
        implements ClosedPracticeInput {}
