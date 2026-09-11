package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.AnswerDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.OptionCountDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionSummaryDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionType;
import java.util.*;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Every rejection here is a 400: well-formed JSON describing a survey or a response that cannot exist. */
final class SurveyQuestions {
    static final int RATING_MIN = 1;
    static final int RATING_MAX = 5;
    static final int NPS_MIN = 0;
    static final int NPS_MAX = 10;
    /** A free-text choice answer fits the same limit as the option it stands beside. */
    static final int OTHER_MAX_LENGTH = 200;

    private SurveyQuestions() {}

    static void validateDefinition(List<QuestionDTO> questions) {
        Set<String> ids = new HashSet<>();
        for (QuestionDTO q : questions) {
            if (!ids.add(q.id())) throw bad("question ids must be unique");
            boolean choice = q.type() == QuestionType.SINGLE_CHOICE || q.type() == QuestionType.MULTIPLE_CHOICE;
            if (choice
                    && (q.options().size() < 2
                            || new HashSet<>(q.options()).size() != q.options().size()))
                throw bad("choice questions need at least two unique options");
            if (!choice && !q.options().isEmpty()) throw bad("only choice questions accept options");
            if (!choice && q.allowOther()) throw bad("only choice questions accept another answer");
            boolean labelled = q.lowLabel() != null
                    && !q.lowLabel().isBlank()
                    && q.highLabel() != null
                    && !q.highLabel().isBlank();
            if (q.type() == QuestionType.RATING && !labelled)
                throw bad("rating questions need labels for both ends of the scale");
            if (q.type() != QuestionType.RATING && (q.lowLabel() != null || q.highLabel() != null))
                throw bad("only rating questions accept scale labels");
        }
    }

    /** Returns the answers in question order with blank text dropped: what is stored is what the summary reads back. */
    static List<AnswerDTO> validateAnswers(List<QuestionDTO> questions, List<AnswerDTO> answers) {
        Map<String, AnswerDTO> byQuestion = new HashMap<>();
        for (AnswerDTO answer : answers) {
            if (byQuestion.put(answer.questionId(), answer) != null) throw bad("a question was answered twice");
        }
        List<AnswerDTO> normalized = new ArrayList<>();
        for (QuestionDTO question : questions) {
            AnswerDTO answer = byQuestion.remove(question.id());
            AnswerDTO checked = answer == null ? null : check(question, answer);
            if (checked == null) {
                if (question.required()) throw bad("a required question was not answered");
                continue;
            }
            normalized.add(checked);
        }
        if (!byQuestion.isEmpty()) throw bad("an answer names a question this survey does not ask");
        return normalized;
    }

    private static @Nullable AnswerDTO check(QuestionDTO question, AnswerDTO answer) {
        int fields = (answer.text() != null ? 1 : 0)
                + (answer.choices() != null ? 1 : 0)
                + (answer.rating() != null ? 1 : 0);
        if (fields > 1) throw bad("an answer carries more than one value");
        return switch (question.type()) {
            case TEXT -> {
                if (answer.text() == null) {
                    if (fields > 0) throw bad("a text question expects text");
                    yield null;
                }
                String text = answer.text().strip();
                yield text.isEmpty() ? null : new AnswerDTO(question.id(), text, null, null);
            }
            case SINGLE_CHOICE, MULTIPLE_CHOICE -> {
                if (answer.choices() == null) {
                    if (fields > 0) throw bad("a choice question expects choices");
                    yield null;
                }
                if (answer.choices().isEmpty()) yield null;
                if (question.type() == QuestionType.SINGLE_CHOICE
                        && answer.choices().size() > 1) throw bad("a single-choice question takes one choice");
                List<String> choices = new ArrayList<>(answer.choices().size());
                boolean hasOther = false;
                for (String choice : answer.choices()) {
                    if (question.options().contains(choice)) {
                        choices.add(choice);
                        continue;
                    }
                    if (!question.allowOther() || hasOther) throw bad("a choice is not one of the question's options");
                    hasOther = true;
                    String other = choice.strip();
                    if (other.isEmpty() || other.length() > OTHER_MAX_LENGTH)
                        throw bad("another answer must fit an option");
                    choices.add(other);
                }
                if (new HashSet<>(choices).size() != choices.size()) throw bad("a choice was given twice");
                yield new AnswerDTO(question.id(), null, List.copyOf(choices), null);
            }
            case RATING, NPS -> {
                if (answer.rating() == null) {
                    if (fields > 0) throw bad("a scale question expects a rating");
                    yield null;
                }
                int min = question.type() == QuestionType.NPS ? NPS_MIN : RATING_MIN;
                int max = question.type() == QuestionType.NPS ? NPS_MAX : RATING_MAX;
                if (answer.rating() < min || answer.rating() > max) throw bad("rating is outside the scale");
                yield new AnswerDTO(question.id(), null, null, answer.rating());
            }
        };
    }

    static List<QuestionSummaryDTO> summarize(List<QuestionDTO> questions, List<List<AnswerDTO>> responses) {
        return questions.stream()
                .map(question -> summarize(question, responses))
                .toList();
    }

    private static QuestionSummaryDTO summarize(QuestionDTO question, List<List<AnswerDTO>> responses) {
        List<AnswerDTO> answers = responses.stream()
                .flatMap(List::stream)
                .filter(answer -> answer.questionId().equals(question.id()))
                .toList();
        return switch (question.type()) {
            case TEXT -> new QuestionSummaryDTO(question.id(), answers.size(), List.of(), null, null, null);
            case SINGLE_CHOICE, MULTIPLE_CHOICE -> {
                Map<String, Long> counts = new LinkedHashMap<>();
                question.options().forEach(option -> counts.put(option, 0L));
                long other = 0;
                for (AnswerDTO answer : answers) {
                    List<String> choices = Objects.requireNonNullElse(answer.choices(), List.of());
                    choices.stream().filter(counts::containsKey).forEach(choice -> counts.merge(choice, 1L, Long::sum));
                    if (!counts.keySet().containsAll(choices)) other++;
                }
                yield new QuestionSummaryDTO(question.id(), answers.size(), counts(counts), other, null, null);
            }
            case RATING, NPS -> {
                int min = question.type() == QuestionType.NPS ? NPS_MIN : RATING_MIN;
                int max = question.type() == QuestionType.NPS ? NPS_MAX : RATING_MAX;
                Map<String, Long> counts = new LinkedHashMap<>();
                IntStream.rangeClosed(min, max).forEach(value -> counts.put(Integer.toString(value), 0L));
                List<Integer> ratings = answers.stream()
                        .map(AnswerDTO::rating)
                        .filter(Objects::nonNull)
                        .toList();
                ratings.forEach(rating -> counts.merge(Integer.toString(rating), 1L, Long::sum));
                Double average = ratings.isEmpty()
                        ? null
                        : ratings.stream().mapToInt(Integer::intValue).average().orElseThrow();
                Integer score = question.type() == QuestionType.NPS && !ratings.isEmpty() ? nps(ratings) : null;
                yield new QuestionSummaryDTO(question.id(), answers.size(), counts(counts), null, average, score);
            }
        };
    }

    private static int nps(List<Integer> ratings) {
        long promoters = ratings.stream().filter(r -> r >= 9).count();
        long detractors = ratings.stream().filter(r -> r <= 6).count();
        return Math.round(100f * (promoters - detractors) / ratings.size());
    }

    private static List<OptionCountDTO> counts(Map<String, Long> counts) {
        return counts.entrySet().stream()
                .map(entry -> new OptionCountDTO(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
