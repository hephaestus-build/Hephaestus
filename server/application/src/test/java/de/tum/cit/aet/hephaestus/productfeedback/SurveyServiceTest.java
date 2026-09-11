package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.AnswerDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionType;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SubmitSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyEditDTO;
import de.tum.cit.aet.hephaestus.productfeedback.SurveyParticipation.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Tag("unit")
class SurveyServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");
    private static final QuestionDTO QUESTION =
            new QuestionDTO("q", "Question?", QuestionType.TEXT, List.of(), false, null, null);

    private SurveyRepository surveys;
    private SurveyParticipationRepository participations;
    private SurveyService service;

    @BeforeEach
    void setUp() {
        surveys = mock(SurveyRepository.class);
        participations = mock(SurveyParticipationRepository.class);
        when(participations.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(participations.countBySurvey(anyCollection())).thenReturn(List.of());
        var refs = mock(FeedbackRefs.class);
        when(refs.resolve(any(), any())).thenReturn(new FeedbackRefs.Resolved(java.util.Map.of(), java.util.Map.of()));
        service =
                new SurveyService(surveys, participations, refs, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldMoveAnInvitationToRespondedWhenTheAnswersFit() {
        Survey survey = openSurvey();
        SurveyParticipation invited = new SurveyParticipation(survey.getId(), 42L, 7L);
        when(participations.findBySurveyIdAndAccountId(survey.getId(), 42L)).thenReturn(Optional.of(invited));

        service.respond(
                survey.getId(), 7L, 42L, new SubmitSurveyDTO(List.of(new AnswerDTO("q", "Ship it", null, null))));

        assertThat(invited.getStatus()).isEqualTo(Status.RESPONDED);
        assertThat(invited.getDecidedAt()).isEqualTo(NOW);
        assertThat(invited.getAnswers()).isNotNull();
        verify(participations, never()).saveAndFlush(any());
    }

    @Test
    void shouldCreateTheParticipationWhenRespondingWithoutAPriorInvitation() {
        Survey survey = openSurvey();
        when(participations.findBySurveyIdAndAccountId(survey.getId(), 42L)).thenReturn(Optional.empty());

        service.respond(survey.getId(), 7L, 42L, new SubmitSurveyDTO(List.of()));

        verify(participations)
                .saveAndFlush(argThat(p ->
                        p.getStatus() == Status.RESPONDED && p.getAccountId().equals(42L)));
    }

    @Test
    void shouldRefuseASecondDecisionOnTheSameSurvey() {
        Survey survey = openSurvey();
        SurveyParticipation declined = new SurveyParticipation(survey.getId(), 42L, 7L);
        declined.decline(7L, NOW);
        when(participations.findBySurveyIdAndAccountId(survey.getId(), 42L)).thenReturn(Optional.of(declined));

        assertThatThrownBy(() -> service.respond(survey.getId(), 7L, 42L, new SubmitSurveyDTO(List.of())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        assertThatThrownBy(() -> service.decline(survey.getId(), 7L, 42L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409");
        assertThat(declined.getStatus()).isEqualTo(Status.DECLINED);
    }

    @Test
    void shouldUndoADeclineButNeverAResponse() {
        Survey survey = openSurvey();
        SurveyParticipation declined = new SurveyParticipation(survey.getId(), 42L, 7L);
        declined.decline(7L, NOW);
        when(participations.findBySurveyIdAndAccountId(survey.getId(), 42L)).thenReturn(Optional.of(declined));

        service.restore(survey.getId(), 7L, 42L);
        assertThat(declined.getStatus()).isEqualTo(Status.INVITED);
        assertThat(declined.getDecidedAt()).isNull();

        declined.respond(new ObjectMapper().createArrayNode(), 7L, NOW);
        assertThatThrownBy(() -> service.restore(survey.getId(), 7L, 42L)).isInstanceOf(EntityNotFoundException.class);
        assertThat(declined.getStatus()).isEqualTo(Status.RESPONDED);
    }

    @Test
    void shouldRecordAnInvitationOnceHoweverOftenItIsReported() {
        Survey survey = openSurvey();
        SurveyParticipation existing = new SurveyParticipation(survey.getId(), 42L, 7L);
        when(participations.findBySurveyIdAndAccountId(survey.getId(), 42L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));

        service.markInvited(survey.getId(), 7L, 42L);
        service.markInvited(survey.getId(), 7L, 42L);

        verify(participations, times(1)).saveAndFlush(argThat(p -> p.getStatus() == Status.INVITED));
    }

    @Test
    void shouldHideASurveyThatIsPausedScheduledEndedOrForAnotherWorkspace() {
        Survey survey = openSurvey();
        for (Runnable change : List.<Runnable>of(
                () -> survey.edit("t", "d", NOW.minusSeconds(60), null, false),
                () -> survey.edit("t", "d", NOW.plusSeconds(60), null, true),
                () -> survey.edit("t", "d", NOW.minusSeconds(120), NOW.minusSeconds(60), true))) {
            change.run();
            assertThatThrownBy(() -> service.respond(survey.getId(), 7L, 42L, new SubmitSurveyDTO(List.of())))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> service.decline(survey.getId(), 7L, 42L))
                    .isInstanceOf(EntityNotFoundException.class);
            assertThatThrownBy(() -> service.markInvited(survey.getId(), 7L, 42L))
                    .isInstanceOf(EntityNotFoundException.class);
        }
        survey.edit("t", "d", NOW.minusSeconds(60), null, true);
        assertThatThrownBy(() -> service.respond(survey.getId(), 8L, 42L, new SubmitSurveyDTO(List.of())))
                .isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(participations);
    }

    @Test
    void shouldAcceptResponsesAgainAfterResuming() {
        Survey survey = openSurvey();
        when(participations.findBySurveyIdAndAccountId(survey.getId(), 42L)).thenReturn(Optional.empty());
        service.edit(survey.getId(), new SurveyEditDTO("t", "d", NOW.minusSeconds(60), null, false));
        assertThat(survey.isActive()).isFalse();
        service.edit(survey.getId(), new SurveyEditDTO("t", "d", NOW.minusSeconds(60), null, true));

        service.respond(survey.getId(), 7L, 42L, new SubmitSurveyDTO(List.of()));

        verify(participations).saveAndFlush(argThat(p -> p.getStatus() == Status.RESPONDED));
    }

    @Test
    void shouldRejectAnEndBeforeTheStart() {
        Survey survey = openSurvey();
        assertThatThrownBy(
                        () -> service.edit(survey.getId(), new SurveyEditDTO("t", "d", NOW, NOW.minusSeconds(1), true)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThat(survey.getEndsAt()).isNull();
    }

    private Survey openSurvey() {
        Survey survey = new Survey(
                "Title",
                "Purpose",
                new ObjectMapper().valueToTree(List.of(QUESTION)),
                7L,
                NOW.minusSeconds(1),
                null,
                1L);
        // The database stamps created_at on insert; the mocked repository never inserts.
        ReflectionTestUtils.setField(survey, "createdAt", NOW.minusSeconds(1));
        when(surveys.findById(survey.getId())).thenReturn(Optional.of(survey));
        return survey;
    }
}
