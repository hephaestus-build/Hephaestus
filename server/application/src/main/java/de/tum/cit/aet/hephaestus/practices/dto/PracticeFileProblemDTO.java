package de.tum.cit.aet.hephaestus.practices.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** One reason why a practice folder cannot become a practice, placed on its file and, where known, its line. */
@Schema(description = "One reason why a practice folder cannot become a practice")
public record PracticeFileProblemDTO(
        @Schema(description = "The file, relative to the practice folder, such as practice.md")
        String file,

        @Schema(description = "The line, counting from 1, when the problem has one") @Nullable
        Integer line,

        @Schema(description = "The column, counting from 1, when the problem has one") @Nullable
        Integer column,

        @Schema(
                description = "Extensible enum: a client must accept other values.",
                examples = {
                    "PRACTICE_MISSING",
                    "UNKNOWN_FILE",
                    "FILE_TOO_LARGE",
                    "FOLDER_TOO_LARGE",
                    "SLUG_INVALID",
                    "FRONT_MATTER_MISSING",
                    "FRONT_MATTER_INVALID",
                    "YAML_INVALID",
                    "KEY_MISSING",
                    "UNKNOWN_KEY",
                    "FORMAT_UNSUPPORTED",
                    "FIELD_INVALID",
                    "VISUAL_MISSING",
                    "VISUAL_ALT_MISSING",
                    "FILE_INVALID",
                    "FIGURE_UNUSED",
                    "DEFINITION_INVALID"
                })
        String code,

        @Schema(description = "What to change, in plain words")
        String message) {
    public static final String PRACTICE_MISSING = "PRACTICE_MISSING";
    public static final String UNKNOWN_FILE = "UNKNOWN_FILE";
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String FOLDER_TOO_LARGE = "FOLDER_TOO_LARGE";
    public static final String SLUG_INVALID = "SLUG_INVALID";
    public static final String FRONT_MATTER_MISSING = "FRONT_MATTER_MISSING";
    public static final String FRONT_MATTER_INVALID = "FRONT_MATTER_INVALID";
    public static final String YAML_INVALID = "YAML_INVALID";
    public static final String KEY_MISSING = "KEY_MISSING";
    public static final String UNKNOWN_KEY = "UNKNOWN_KEY";
    public static final String FORMAT_UNSUPPORTED = "FORMAT_UNSUPPORTED";
    public static final String FIELD_INVALID = "FIELD_INVALID";
    public static final String VISUAL_MISSING = "VISUAL_MISSING";
    public static final String VISUAL_ALT_MISSING = "VISUAL_ALT_MISSING";
    public static final String FILE_INVALID = "FILE_INVALID";
    public static final String FIGURE_UNUSED = "FIGURE_UNUSED";
    public static final String DEFINITION_INVALID = "DEFINITION_INVALID";
}
