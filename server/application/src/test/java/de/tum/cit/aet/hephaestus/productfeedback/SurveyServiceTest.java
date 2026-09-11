package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.AnswerDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionType;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SubmitSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.SurveyParticipation.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** The participation state machine against a fixed clock; availability itself is {@link SurveyTest}. */
@Tag("unit")
class SurveyServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");
    private static final QuestionDTO QUESTION =
            new QuestionDTO("q", "Question?", QuestionType.TEXT, List.of(), false, null, null);

    private SurveyParticipationRepository participations;
    private SurveyService service;
    private Survey survey;
    private SurveyParticipation participation;

    @BeforeEach
    void setUp() {
        SurveyRepository surveys = mock(SurveyRepository.class);
        participations = mock(SurveyParticipationRepository.class);
        service = new SurveyService(
                surveys,
                participations,
                mock(FeedbackRefs.class),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        survey = new Survey(
                "Title",
                "Purpose",
                new ObjectMapper().valueToTree(List.of(QUESTION)),
                7L,
                NOW.minusSeconds(1),
                null,
                1L);
        when(surveys.findById(survey.getId())).thenReturn(Optional.of(survey));
        participation = new SurveyParticipation(survey.getId(), 42L, 7L);
        when(participations.findBySurveyIdAndAccountId(survey.getId(), 42L)).thenReturn(Optional.of(participation));
    }

    @Test
    void shouldMoveAnInvitationToRespondedWhenTheAnswersFit() {
        service.respond(
                survey.getId(), 7L, 42L, new SubmitSurveyDTO(List.of(new AnswerDTO("q", "Ship it", null, null))));

        assertThat(participation.getStatus()).isEqualTo(Status.RESPONDED);
        assertThat(participation.getDecidedAt()).isEqualTo(NOW);
        assertThat(participation.getAnswers()).isNotNull();
    }

    @Test
    void shouldRefuseASecondDecisionOnTheSameSurvey() {
        participation.decline(7L, NOW);

        assertThatThrownBy(() -> service.respond(survey.getId(), 7L, 42L, new SubmitSurveyDTO(List.of())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        assertThatThrownBy(() -> service.decline(survey.getId(), 7L, 42L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        assertThat(participation.getStatus()).isEqualTo(Status.DECLINED);
    }

    @Test
    void shouldUndoADeclineButNeverAResponse() {
        participation.decline(7L, NOW);

        service.restore(survey.getId(), 7L, 42L);
        assertThat(participation.getStatus()).isEqualTo(Status.INVITED);
        assertThat(participation.getDecidedAt()).isNull();

        participation.respond(new ObjectMapper().createArrayNode(), 7L, NOW);
        assertThatThrownBy(() -> service.restore(survey.getId(), 7L, 42L)).isInstanceOf(EntityNotFoundException.class);
        assertThat(participation.getStatus()).isEqualTo(Status.RESPONDED);
    }

    @Test
    void shouldTreatAClosedSurveyAsAbsentForEveryMemberWrite() {
        survey.edit("t", "d", NOW.minusSeconds(60), null, false);

        assertThatThrownBy(() -> service.respond(survey.getId(), 7L, 42L, new SubmitSurveyDTO(List.of())))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.decline(survey.getId(), 7L, 42L)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.markInvited(survey.getId(), 7L, 42L))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.restore(survey.getId(), 7L, 42L)).isInstanceOf(EntityNotFoundException.class);
        assertThat(participation.getStatus()).isEqualTo(Status.INVITED);
    }
}
