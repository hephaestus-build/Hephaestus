package de.tum.cit.aet.hephaestus.practices.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The reviewer's answer to one practice question, as admitted: what it answered, the fact that decides it, and the
 * observation's citations it rests on.
 *
 * @param question      the question key in the practice revision's judgment
 * @param answer        YES, NO, or UNDETERMINED
 * @param because       one sentence: the fact in the cited lines that decides this answer
 * @param decisive      whether the outcome rests on this answer, as the derivation decided it
 * @param citations     indexes into the observation's {@code evidence.citations}
 * @param search        present when the answer rests on something being absent: where the reviewer looked
 * @param wouldSettleIt present exactly for UNDETERMINED: the existing evidence that would decide the question
 */
@Schema(
        description = "The reviewer's answer to one practice question",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record ObservationAnswer(
        String question,
        QuestionAnswer answer,
        String because,
        boolean decisive,

        @Schema(description = "Indexes into the observation's evidence citations")
        List<Integer> citations,

        @Nullable Search search,
        @Nullable String wouldSettleIt) {
    public ObservationAnswer {
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(because, "because");
        citations = List.copyOf(citations);
        if ((answer == QuestionAnswer.UNDETERMINED) != (wouldSettleIt != null)) {
            throw new IllegalArgumentException("wouldSettleIt is recorded exactly for an UNDETERMINED answer");
        }
    }

    /**
     * The bounded search an answer resting on absence records.
     *
     * @param consulted the source kinds searched
     * @param lookedFor what was looked for
     * @param boundary  what the search did not cover
     */
    @Schema(
            description = "Where an answer resting on absence looked",
            additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Search(List<String> consulted, String lookedFor, String boundary) {
        public Search {
            consulted = List.copyOf(consulted);
        }
    }
}
