package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.AnswerDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.OptionCountDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionType;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class SurveyQuestionsTest {
    private static final QuestionDTO TEXT =
            new QuestionDTO("text", "Why?", QuestionType.TEXT, List.of(), false, null, null);
    private static final QuestionDTO SINGLE =
            new QuestionDTO("single", "Pick one", QuestionType.SINGLE_CHOICE, List.of("A", "B"), true, null, null);
    private static final QuestionDTO MULTI = new QuestionDTO(
            "multi", "Pick any", QuestionType.MULTIPLE_CHOICE, List.of("X", "Y", "Z"), false, null, null);
    private static final QuestionDTO RATING =
            new QuestionDTO("rating", "How useful?", QuestionType.RATING, List.of(), true, "Not at all", "Very");
    private static final QuestionDTO NPS =
            new QuestionDTO("nps", "Recommend?", QuestionType.NPS, List.of(), false, null, null);
    private static final List<QuestionDTO> ALL = List.of(TEXT, SINGLE, MULTI, RATING, NPS);

    static List<List<QuestionDTO>> invalidDefinitions() {
        return List.of(
                List.of(TEXT, TEXT),
                List.of(new QuestionDTO(
                        "q", "One option", QuestionType.SINGLE_CHOICE, List.of("A"), false, null, null)),
                List.of(new QuestionDTO(
                        "q", "Duplicate", QuestionType.MULTIPLE_CHOICE, List.of("A", "A"), false, null, null)),
                List.of(new QuestionDTO(
                        "q", "Options on text", QuestionType.TEXT, List.of("A", "B"), false, null, null)),
                List.of(new QuestionDTO("q", "Unlabelled", QuestionType.RATING, List.of(), false, "Low", null)),
                List.of(new QuestionDTO("q", "Blank label", QuestionType.RATING, List.of(), false, " ", "High")),
                List.of(new QuestionDTO("q", "Labels on NPS", QuestionType.NPS, List.of(), false, "Low", "High")));
    }

    @ParameterizedTest
    @MethodSource("invalidDefinitions")
    void shouldRejectDefinitionsThatCannotBeAnswered(List<QuestionDTO> questions) {
        assertThatThrownBy(() -> SurveyQuestions.validateDefinition(questions))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void shouldAcceptEveryQuestionTypeTogether() {
        SurveyQuestions.validateDefinition(ALL);
    }

    @Test
    void shouldNormaliseAnswersIntoQuestionOrderAndDropBlankText() {
        List<AnswerDTO> stored = SurveyQuestions.validateAnswers(
                ALL,
                List.of(
                        new AnswerDTO("nps", null, null, 9),
                        new AnswerDTO("rating", null, null, 4),
                        new AnswerDTO("text", "  ", null, null),
                        new AnswerDTO("multi", null, List.of("Z", "X"), null),
                        new AnswerDTO("single", null, List.of("B"), null)));

        assertThat(stored).extracting(AnswerDTO::questionId).containsExactly("single", "multi", "rating", "nps");
        assertThat(stored.get(1).choices()).containsExactly("Z", "X");
    }

    static List<List<AnswerDTO>> invalidAnswers() {
        List<AnswerDTO> required =
                List.of(new AnswerDTO("single", null, List.of("A"), null), new AnswerDTO("rating", null, null, 3));
        return List.of(
                List.of(new AnswerDTO("rating", null, null, 3)),
                List.of(new AnswerDTO("single", null, List.of("A"), null), new AnswerDTO("rating", null, null, 6)),
                List.of(new AnswerDTO("single", null, List.of("C"), null), new AnswerDTO("rating", null, null, 3)),
                List.of(new AnswerDTO("single", null, List.of("A", "B"), null), new AnswerDTO("rating", null, null, 3)),
                List.of(new AnswerDTO("single", "A", null, null), new AnswerDTO("rating", null, null, 3)),
                List.of(new AnswerDTO("single", "A", List.of("A"), null), new AnswerDTO("rating", null, null, 3)),
                List.of(required.get(0), required.get(1), new AnswerDTO("other", "x", null, null)),
                List.of(required.get(0), required.get(1), new AnswerDTO("nps", null, null, 11)),
                List.of(required.get(0), required.get(1), new AnswerDTO("multi", null, List.of("X", "X"), null)),
                List.of(required.get(0), required.get(1), required.get(1)));
    }

    @ParameterizedTest
    @MethodSource("invalidAnswers")
    void shouldRejectAnswersThatDoNotFitTheQuestions(List<AnswerDTO> answers) {
        assertThatThrownBy(() -> SurveyQuestions.validateAnswers(ALL, answers))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void shouldTreatAnEmptyOptionalAnswerAsSkipped() {
        List<AnswerDTO> stored = SurveyQuestions.validateAnswers(
                List.of(TEXT, MULTI, NPS),
                List.of(new AnswerDTO("text", "", null, null), new AnswerDTO("multi", null, List.of(), null)));
        assertThat(stored).isEmpty();
    }

    @Test
    void shouldSummariseCountsAveragesAndNetPromoterScore() {
        var responses = List.of(
                List.of(
                        new AnswerDTO("single", null, List.of("A"), null),
                        new AnswerDTO("multi", null, List.of("X", "Z"), null),
                        new AnswerDTO("rating", null, null, 5),
                        new AnswerDTO("nps", null, null, 10),
                        new AnswerDTO("text", "Great", null, null)),
                List.of(
                        new AnswerDTO("single", null, List.of("A"), null),
                        new AnswerDTO("rating", null, null, 2),
                        new AnswerDTO("nps", null, null, 3)),
                List.of(new AnswerDTO("single", null, List.of("B"), null), new AnswerDTO("nps", null, null, 8)));

        var summary = SurveyQuestions.summarize(ALL, responses);

        assertThat(summary).extracting("questionId").containsExactly("text", "single", "multi", "rating", "nps");
        assertThat(summary.get(0).answered()).isEqualTo(1);
        assertThat(summary.get(1).counts()).containsExactly(new OptionCountDTO("A", 2), new OptionCountDTO("B", 1));
        assertThat(summary.get(2).counts())
                .containsExactly(new OptionCountDTO("X", 1), new OptionCountDTO("Y", 0), new OptionCountDTO("Z", 1));
        assertThat(summary.get(3).average()).isEqualTo(3.5);
        assertThat(summary.get(3).counts())
                .hasSize(5)
                .extracting(OptionCountDTO::value)
                .containsExactly("1", "2", "3", "4", "5");
        assertThat(summary.get(4).counts()).hasSize(11);
        // One promoter, one detractor, one passive: (1 - 1) / 3 = 0.
        assertThat(summary.get(4).score()).isZero();
        assertThat(summary.get(4).average()).isEqualTo(7.0);
    }

    @Test
    void shouldLeaveAveragesAbsentWithoutRatings() {
        var summary = SurveyQuestions.summarize(List.of(RATING, NPS), List.of());
        assertThat(summary).allSatisfy(question -> {
            assertThat(question.average()).isNull();
            assertThat(question.score()).isNull();
            assertThat(question.answered()).isZero();
        });
    }
}
