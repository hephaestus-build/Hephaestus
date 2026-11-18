package de.tum.cit.aet.hephaestus.practices.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.core.auth.dto.AccountRefDTO;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionField;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PracticeVersionDTO(
        @Schema(pattern = "^[1-9][0-9]*$", requiredMode = Schema.RequiredMode.REQUIRED)
        String id,

        int number,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable
        String name,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable
        PracticeDefinition definition,

        String reviewDigest,
        Instant createdAt,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) @Nullable
        AccountRefDTO createdBy,

        List<PracticeDefinitionField> changed,
        boolean reviewChanged,

        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = true,
                description = "The commit of the practice repository that this version was read at; null for a"
                        + " version edited in Hephaestus")
        @Nullable
        String sourceCommit) {
    public PracticeVersionDTO {
        changed = List.copyOf(changed);
    }
}
