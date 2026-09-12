package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationQuery;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.AnswerDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.CreateSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionType;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SubmitSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyInvitationDTO;
import de.tum.cit.aet.hephaestus.productfeedback.SurveyParticipation.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
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
            new QuestionDTO("q", "Question?", QuestionType.TEXT, List.of(), false, false, null, null);

    private SurveyRepository surveys;
    private SurveyParticipationRepository participations;
    private ResearchParticipationQuery research;
    private SurveyService service;
    private Survey survey;
    private SurveyParticipation participation;

    @BeforeEach
    void setUp() {
        surveys = mock(SurveyRepository.class);
        participations = mock(SurveyParticipationRepository.class);
        research = mock(ResearchParticipationQuery.class);
        when(research.researchOrganization()).thenReturn(Optional.empty());
        service = new SurveyService(
                surveys,
                participations,
                mock(FeedbackRefs.class),
                research,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        survey = survey(null);
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

        service.undoDecline(survey.getId(), 7L, 42L);
        assertThat(participation.getStatus()).isEqualTo(Status.INVITED);
        assertThat(participation.getDecidedAt()).isNull();

        participation.respond(new ObjectMapper().createArrayNode(), 7L, NOW);
        assertThatThrownBy(() -> service.undoDecline(survey.getId(), 7L, 42L))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(participation.getStatus()).isEqualTo(Status.RESPONDED);
    }

    @Test
    void shouldOfferAResearchSurveyOnlyToParticipantsOfTheStudyItWasPublishedFor() {
        Survey study = survey("TUM");
        when(surveys.findById(study.getId())).thenReturn(Optional.of(study));
        when(surveys.findAllByActiveTrueOrderByStartsAtAscCreatedAtAsc()).thenReturn(List.of(survey, study));
        when(participations.findAllBySurveyIdInAndAccountId(List.of(survey.getId()), 42L))
                .thenReturn(List.of());
        when(participations.findAllBySurveyIdInAndAccountId(List.of(survey.getId(), study.getId()), 42L))
                .thenReturn(List.of());
        Runnable expectHidden = () -> {
            assertThat(service.invitations(7L, 42L))
                    .extracting(SurveyInvitationDTO::id)
                    .containsExactly(survey.getId());
            assertThatThrownBy(() -> service.respond(study.getId(), 7L, 42L, new SubmitSurveyDTO(List.of())))
                    .isInstanceOf(EntityNotFoundException.class);
        };

        // A grant for the study as it stands reaches no survey published for no study or another one.
        when(research.participates(42L)).thenReturn(true);
        when(research.researchOrganization()).thenReturn(Optional.empty());
        expectHidden.run();
        when(research.researchOrganization()).thenReturn(Optional.of("Other"));
        expectHidden.run();
        verify(research, never()).participates(42L);

        // The right study without a grant is hidden too; with one, it is offered and answered.
        when(research.researchOrganization()).thenReturn(Optional.of("TUM"));
        when(research.participates(42L)).thenReturn(false);
        expectHidden.run();
        when(research.participates(42L)).thenReturn(true);
        assertThat(service.invitations(7L, 42L))
                .extracting(SurveyInvitationDTO::id)
                .containsExactly(survey.getId(), study.getId());
        assertThat(service.invitations(7L, 42L).getLast().researchOrganization())
                .isEqualTo("TUM");
        SurveyParticipation studyParticipation = new SurveyParticipation(study.getId(), 42L, 7L);
        when(participations.findBySurveyIdAndAccountId(study.getId(), 42L)).thenReturn(Optional.of(studyParticipation));
        service.respond(study.getId(), 7L, 42L, new SubmitSurveyDTO(List.of()));
        assertThat(studyParticipation.getStatus()).isEqualTo(Status.RESPONDED);
    }

    @Test
    void shouldRefuseAResearchSurveyWhenTheInstanceRunsNoProgramme() {
        CreateSurveyDTO request =
                new CreateSurveyDTO("Title", "Intro", Survey.Purpose.RESEARCH, List.of(QUESTION), null, NOW, null);

        assertThatThrownBy(() -> service.create(request, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        verifyNoInteractions(surveys);
    }

    @Test
    void shouldTreatAClosedSurveyAsAbsentForEveryMemberWrite() {
        survey.edit("t", "d", NOW.minusSeconds(60), null, false);

        assertThatThrownBy(() -> service.respond(survey.getId(), 7L, 42L, new SubmitSurveyDTO(List.of())))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.decline(survey.getId(), 7L, 42L)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.markInvited(survey.getId(), 7L, 42L))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.undoDecline(survey.getId(), 7L, 42L))
                .isInstanceOf(EntityNotFoundException.class);
        assertThat(participation.getStatus()).isEqualTo(Status.INVITED);
    }

    private Survey survey(@Nullable String researchOrganization) {
        return new Survey(
                "Title",
                "Intro",
                researchOrganization,
                new ObjectMapper().valueToTree(List.of(QUESTION)),
                7L,
                NOW.minusSeconds(1),
                null,
                1L);
    }
}
