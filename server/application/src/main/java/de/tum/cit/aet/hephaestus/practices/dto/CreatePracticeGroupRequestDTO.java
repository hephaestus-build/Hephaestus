package de.tum.cit.aet.hephaestus.practices.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import de.tum.cit.aet.hephaestus.practices.ClosedPracticeInput;
import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CreatePracticeGroupRequestDTO(
        @JsonProperty(required = true) @ValidDefinitionSlug String slug,

        @JsonProperty(required = true) @NotBlank @Size(max = GroupDefinition.NAME_MAX_LENGTH)
        String name,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonProperty(required = true)
        @Nullable
        @Size(max = GroupDefinition.DESCRIPTION_MAX_LENGTH)
        String description,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonProperty(required = true)
        @Nullable
        @Size(max = GroupDefinition.ICON_MAX_LENGTH)
        String icon,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonProperty(required = true)
        @Nullable
        @Size(max = GroupDefinition.COLOR_MAX_LENGTH)
        String color,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonProperty(required = true)
        @Nullable
        @PositiveOrZero
        Integer position,

        @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL)
        boolean shownToDevelopers)
        implements ClosedPracticeInput {}
