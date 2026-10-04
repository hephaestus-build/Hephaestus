package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;

/**
 * One yes/no question a practice review answers from evidence.
 *
 * @param key      stable identifier that rules and recorded answers refer to
 * @param title    short statement a YES affirms, shown wherever the answer is shown
 * @param question the question as the reviewer reads it: what counts and what does not
 * @param yes      what a YES means for the reviewed work
 * @param no       what a NO means for the reviewed work
 */
@Schema(
        description = "One yes/no question a practice review answers from evidence",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PracticeQuestion(
        @NotBlank @Pattern(regexp = KEY_PATTERN) @Schema(pattern = KEY_PATTERN, example = "states_why")
        String key,

        @NotBlank @Size(min = 1, max = MAX_TITLE_LENGTH) @Schema(example = "States why the change exists")
        String title,

        @NotBlank @Size(min = 1, max = MAX_QUESTION_LENGTH) String question,
        @NotBlank @Size(min = 1, max = MAX_MEANING_LENGTH) String yes,
        @NotBlank @Size(min = 1, max = MAX_MEANING_LENGTH) String no) {
    public static final String KEY_PATTERN = "^[a-z][a-z0-9_]{1,39}$";
    public static final int MAX_TITLE_LENGTH = 60;
    public static final int MAX_QUESTION_LENGTH = 2400;
    public static final int MAX_MEANING_LENGTH = 600;

    @JsonCreator
    public PracticeQuestion(
            @JsonProperty("key") String key,
            @JsonProperty("title") String title,
            @JsonProperty("question") String question,
            @JsonProperty("yes") String yes,
            @JsonProperty("no") String no) {
        this.key = Objects.requireNonNull(key, "key");
        if (!key.matches(KEY_PATTERN)) {
            throw new IllegalArgumentException("Question key “" + key
                    + "” must start with a lowercase letter and use only lowercase letters, digits and underscores"
                    + " (2–40 characters).");
        }
        this.title = text(title, MAX_TITLE_LENGTH, "Question “" + key + "” needs a title");
        if (this.title.endsWith(".")) {
            throw new IllegalArgumentException(
                    "Question “" + key + "”: write the title as a statement without a final period.");
        }
        this.question = text(question, MAX_QUESTION_LENGTH, "Question “" + key + "” needs the question text");
        this.yes = text(yes, MAX_MEANING_LENGTH, "Question “" + key + "” needs to say what a yes means");
        this.no = text(no, MAX_MEANING_LENGTH, "Question “" + key + "” needs to say what a no means");
    }

    private static String text(String value, int max, String missing) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(missing + ".");
        }
        String trimmed = value.strip();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(
                    missing.replace(" needs ", ": ") + " may be at most " + max + " characters long.");
        }
        return trimmed;
    }
}
