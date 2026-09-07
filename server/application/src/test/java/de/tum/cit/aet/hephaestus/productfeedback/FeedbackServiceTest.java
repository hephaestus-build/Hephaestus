package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionType;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SubmitSurveyDTO;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class FeedbackServiceTest {
    private SurveyRepository surveys;
    private SurveySubmissionRepository submissions;
    private FeedbackService service;

    @BeforeEach
    void setUp() {
        surveys = mock(SurveyRepository.class);
        submissions = mock(SurveySubmissionRepository.class);
        service = new FeedbackService(surveys, submissions, mock(ProductFeedbackRepository.class), new ObjectMapper());
    }

    @Test
    void shouldPersistDismissalWhenSurveyTargetsWorkspace() {
        Survey survey = survey(List.of(new QuestionDTO("q", "Question?", QuestionType.TEXT, List.of(), true)));
        when(surveys.findById(survey.getId())).thenReturn(Optional.of(survey));
        when(submissions.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.dismiss(survey.getId(), 7L, 42L);

        verify(submissions)
                .saveAndFlush(argThat(value -> value.getDisposition() == SurveySubmission.Disposition.DISMISSED));
    }

    @Test
    void shouldRejectChoiceOutsideAuthoredOptions() {
        Survey survey = survey(List.of(new QuestionDTO("q", "Choose", QuestionType.SINGLE_CHOICE, List.of("A"), true)));
        when(surveys.findById(survey.getId())).thenReturn(Optional.of(survey));

        assertThatThrownBy(() -> service.submit(survey.getId(), 7L, 42L, new SubmitSurveyDTO(Map.of("q", "B"))))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(submissions);
    }

    @Test
    void shouldRejectResponsesAndDismissalsWhilePausedAndAcceptAfterResume() {
        Survey survey = survey(List.of(new QuestionDTO("q", "Question?", QuestionType.TEXT, List.of(), false)));
        when(surveys.findById(survey.getId())).thenReturn(Optional.of(survey));
        assertThat(service.setActive(survey.getId(), false).active()).isFalse();
        assertThatThrownBy(() -> service.submit(survey.getId(), 7L, 42L, new SubmitSurveyDTO(Map.of())))
                .isInstanceOf(de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException.class);
        assertThatThrownBy(() -> service.dismiss(survey.getId(), 7L, 42L))
                .isInstanceOf(de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException.class);
        verifyNoInteractions(submissions);
        assertThat(service.setActive(survey.getId(), true).active()).isTrue();
        service.submit(survey.getId(), 7L, 42L, new SubmitSurveyDTO(Map.of()));
        verify(submissions).saveAndFlush(argThat(s -> s.getDisposition() == SurveySubmission.Disposition.RESPONDED));
    }

    @Test
    void shouldRejectAnotherWorkspaceBeforeWritingAResponse() {
        Survey survey = survey(List.of(new QuestionDTO("q", "Question?", QuestionType.TEXT, List.of(), false)));
        when(surveys.findById(survey.getId())).thenReturn(Optional.of(survey));
        assertThatThrownBy(() -> service.submit(survey.getId(), 8L, 42L, new SubmitSurveyDTO(Map.of())))
                .isInstanceOf(de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException.class);
        verifyNoInteractions(submissions);
    }

    @Test
    void shouldRejectUnknownAnswersAndOutOfRangeRatings() {
        Survey survey = survey(List.of(new QuestionDTO("q", "Question?", QuestionType.RATING, List.of(), true)));
        when(surveys.findById(survey.getId())).thenReturn(Optional.of(survey));
        for (Map<String, String> answers : List.of(Map.of("q", "6"), Map.of("other", "3"), Map.of("q", " "))) {
            assertThatThrownBy(() -> service.submit(survey.getId(), 7L, 42L, new SubmitSurveyDTO(answers)))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("400");
        }
        verifyNoInteractions(submissions);
    }

    private static Survey survey(List<QuestionDTO> questions) {
        return new Survey(
                "Title",
                "Purpose",
                new ObjectMapper().valueToTree(questions),
                7L,
                Instant.now().minusSeconds(1),
                null,
                1L);
    }
}
