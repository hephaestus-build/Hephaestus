package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.QuestionAnswer;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One rule of a practice judgment: when every named question has the stated answer, the rule decides the outcome.
 * Rules are ordered and the first that matches decides.
 *
 * @param id       stable identifier an observation records as the rule that decided it
 * @param when     question key to the answer it requires; empty matches every combination
 * @param outcome  the outcome this rule decides
 * @param severity required exactly for NOT_MET
 * @param reason   one sentence about the work, shown as the observation's headline and used as its warrant
 */
@Schema(
        description = "A rule that decides an outcome from the answers; the first matching rule decides",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PracticeRule(
        @NotBlank @Pattern(regexp = ID_PATTERN) @Schema(pattern = ID_PATTERN, example = "no-why")
        String id,

        @NonNull
        @NotNull
        @Schema(description = "Question key to the answer it requires (YES or NO); empty matches every combination")
        Map<String, QuestionAnswer> when,

        @NonNull @NotNull Outcome outcome,
        @Nullable Severity severity,

        @NotBlank
        @Size(min = MIN_REASON_LENGTH, max = MAX_REASON_LENGTH)
        @Schema(example = "The title and description say what changed but not why.")
        String reason) {
    public static final String ID_PATTERN = "^[a-z][a-z0-9-]{1,39}$";
    public static final int MIN_REASON_LENGTH = 10;
    public static final int MAX_REASON_LENGTH = 200;

    @JsonCreator
    public PracticeRule(
            @JsonProperty("id") String id,
            @JsonProperty("when") @Nullable Map<String, QuestionAnswer> when,
            @JsonProperty("outcome") Outcome outcome,
            @JsonProperty("severity") @Nullable Severity severity,
            @JsonProperty("reason") String reason) {
        this.id = Objects.requireNonNull(id, "id");
        if (!id.matches(ID_PATTERN)) {
            throw new IllegalArgumentException("Rule id “" + id
                    + "” must start with a lowercase letter and use only lowercase letters, digits and hyphens"
                    + " (2–40 characters).");
        }
        Map<String, QuestionAnswer> conditions = new LinkedHashMap<>(when == null ? Map.of() : when);
        conditions.forEach((key, answer) -> {
            if (answer == null || !answer.isDefinite()) {
                throw new IllegalArgumentException(
                        "Rule “" + id + "” must require YES or NO for question “" + key + "”.");
            }
        });
        this.when = Collections.unmodifiableMap(conditions);
        this.outcome = Objects.requireNonNull(outcome, "Rule “" + id + "” needs an outcome.");
        if ((outcome == Outcome.NOT_MET) != (severity != null)) {
            throw new IllegalArgumentException(
                    outcome == Outcome.NOT_MET
                            ? "Rule “" + id + "” decides Not met and needs a severity."
                            : "Rule “" + id + "” gives a severity, but only a Not met rule has one.");
        }
        this.severity = severity;
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Rule “" + id + "” needs a reason: one sentence about the work.");
        }
        this.reason = reason.strip();
        if (this.reason.length() < MIN_REASON_LENGTH || this.reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("The reason of rule “" + id + "” must be " + MIN_REASON_LENGTH + "–"
                    + MAX_REASON_LENGTH + " characters long.");
        }
    }

    public boolean matches(Map<String, QuestionAnswer> answers) {
        return when.entrySet().stream().allMatch(condition -> condition.getValue() == answers.get(condition.getKey()));
    }
}
