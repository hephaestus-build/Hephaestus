package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;

/**
 * A mechanically decidable precondition for evaluating a practice.
 *
 * <p>The clauses are alternatives. Evaluation is skipped only when complete evidence disproves every
 * clause; unresolved clauses make the practice run. This makes the applicability check conservative: it may
 * spend an unnecessary model call, but it cannot hide a potentially relevant observation.
 *
 * @param skipReason a factual explanation shown when every clause is disproved
 * @param anyOf      alternative conditions; at least one
 */
@Schema(description = "What must be in a piece of work for this practice to have anything to judge")
public record PracticePrecondition(
        @NotBlank
        @Size(max = MAX_SENTENCE_LENGTH)
        @Schema(description = "Explanation shown when complete evidence disproves every condition", minLength = 1)
        String skipReason,

        @Size(min = 1, max = MAX_CLAUSES)
        @Schema(description = "Alternative conditions; the practice applies when any condition holds")
        List<PracticePreconditionClause> anyOf) {
    static final int MAX_SENTENCE_LENGTH = 300;
    static final int MAX_CLAUSES = 10;

    @JsonCreator
    public PracticePrecondition(
            @JsonProperty("skipReason") String skipReason,
            @JsonProperty("anyOf") List<PracticePreconditionClause> anyOf) {
        this.skipReason = Objects.requireNonNull(skipReason, "skipReason");
        // Named the way PracticePreconditionClause names its fields: the editor's label, then the key typed.
        if (skipReason.isBlank()) {
            throw new IllegalArgumentException(
                    "“Otherwise skip” (skipReason) needs a sentence saying why the practice does not apply.");
        }
        if (skipReason.length() > MAX_SENTENCE_LENGTH) {
            throw new IllegalArgumentException(
                    "“Otherwise skip” (skipReason) may be at most " + MAX_SENTENCE_LENGTH + " characters long.");
        }
        this.anyOf = List.copyOf(Objects.requireNonNull(anyOf, "anyOf"));
        if (this.anyOf.isEmpty()) {
            throw new IllegalArgumentException("The gate needs at least one clause in anyOf. Add one, or clear "
                    + "“Only review when” to review all work.");
        }
        if (this.anyOf.size() > MAX_CLAUSES) {
            throw new IllegalArgumentException("The gate may list at most " + MAX_CLAUSES + " clauses in anyOf.");
        }
    }
}
