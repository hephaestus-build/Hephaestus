package de.tum.cit.aet.hephaestus.practices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

@Schema(description = "The files of one practice folder, by path relative to the folder")
public record PracticeFileCheckRequestDTO(
        @NotNull
        @Size(min = 1, max = 16)
        @Schema(
                description = "File contents by path, such as practice.md, guide.md, visual.svg, script.ts and"
                        + " figures/<name>.svg")
        Map<@Size(max = 128) String, @NotNull @Size(max = 262_144) String> files) {}
