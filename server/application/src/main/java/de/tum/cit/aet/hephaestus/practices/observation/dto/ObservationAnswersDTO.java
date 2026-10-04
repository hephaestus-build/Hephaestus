package de.tum.cit.aet.hephaestus.practices.observation.dto;

import de.tum.cit.aet.hephaestus.practices.PracticeJudgment;
import de.tum.cit.aet.hephaestus.practices.PracticeQuestion;
import de.tum.cit.aet.hephaestus.practices.PracticeRule;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationAnswer;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.QuestionAnswer;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * How an observation was decided: the reviewer's answer to each question the practice revision asked, and the
 * rule that turned the answers into the outcome.
 */
@Schema(description = "The answers an observation was decided from, and the rule that decided it")
public record ObservationAnswersDTO(
        @Nullable @Schema(description = "The deciding rule; null when open answers left the outcome undetermined")
        String ruleId,

        @Nullable
        @Schema(description = "The deciding rule's reason: one sentence about the work, the observation's headline")
        String decidedBy,

        @NonNull List<Answer> answers) {

    /** One answer, with its question as the practice revision asked it. */
    @Schema(name = "ObservationAnswerDTO", description = "The reviewer's answer to one practice question")
    public record Answer(
            @NonNull @Schema(description = "The question key")
            String question,

            @NonNull @Schema(description = "The question's title, a statement a YES affirms")
            String title,

            @NonNull QuestionAnswer answer,

            @NonNull @Schema(description = "The fact in the cited lines that decides the answer")
            String because,

            @NonNull @Schema(description = "Whether the deciding rule used this answer")
            Boolean decisive,

            @NonNull @Schema(description = "Indexes into the observation's evidence citations")
            List<Integer> citations,

            @Nullable EvidenceSearchDTO search,

            @Nullable @Schema(description = "For an undetermined answer: the evidence that would decide it")
            String wouldSettleIt) {}

    /** Null for an observation recorded before reviews answered questions, or whose revision is unknown. */
    public static @Nullable ObservationAnswersDTO from(Observation observation) {
        List<ObservationAnswer> answers = observation.getAnswers();
        PracticeRevision revision = observation.getPracticeRevision();
        PracticeJudgment judgment = revision == null ? null : revision.getJudgment();
        if (answers == null || judgment == null) {
            return null;
        }
        PracticeRule rule = observation.getRuleId() == null ? null : judgment.rule(observation.getRuleId());
        return new ObservationAnswersDTO(
                observation.getRuleId(),
                rule == null ? PracticeJudgment.unsettledHeadline(observation.getOutcome()) : rule.reason(),
                answers.stream()
                        .map(answer -> {
                            PracticeQuestion question = judgment.question(answer.question());
                            return new Answer(
                                    answer.question(),
                                    question == null ? answer.question() : question.title(),
                                    answer.answer(),
                                    answer.because(),
                                    answer.decisive(),
                                    answer.citations(),
                                    answer.search() == null
                                            ? null
                                            : new EvidenceSearchDTO(
                                                    answer.search().lookedFor(),
                                                    answer.search().consulted(),
                                                    answer.search().boundary()),
                                    answer.wouldSettleIt());
                        })
                        .toList());
    }
}
