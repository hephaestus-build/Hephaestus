package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationQuery;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.web.Csv;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.AnswerDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.CreateSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.ParticipationCountsDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SubmitSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyEditDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyInvitationDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyResponseDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveySummaryDTO;
import de.tum.cit.aet.hephaestus.productfeedback.SurveyParticipation.Status;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class SurveyService {
    static final int EXPORT_MAX_ROWS = 10_000;

    private final SurveyRepository surveys;
    private final SurveyParticipationRepository participations;
    private final SurveyEmailInvitationRepository emailInvitations;
    private final FeedbackRefs refs;
    private final ResearchParticipationQuery research;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Transactional
    public SurveyDTO create(CreateSurveyDTO request, Long accountId) {
        SurveyQuestions.validateDefinition(request.questions());
        String organisation = null;
        if (request.purpose() == Survey.Purpose.RESEARCH) {
            organisation = research.researchOrganization()
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "This instance runs no research programme"));
        }
        Survey survey = surveys.saveAndFlush(new Survey(
                request.title(),
                request.description(),
                organisation,
                mapper.valueToTree(request.questions()),
                request.workspaceId(),
                request.startsAt(),
                request.endsAt(),
                accountId));
        return dtos(List.of(survey)).getFirst();
    }

    @Transactional
    public SurveyDTO edit(UUID id, SurveyEditDTO request) {
        Survey survey = require(id);
        survey.edit(request.title(), request.description(), request.startsAt(), request.endsAt(), request.active());
        if (!request.active()) {
            emailInvitations.cancelPendingForSurvey(id, clock.instant());
        }
        return dtos(List.of(survey)).getFirst();
    }

    @Transactional
    public void delete(UUID id) {
        Survey survey = require(id);
        participations.deleteAllBySurveyId(id);
        emailInvitations.deleteAllBySurveyId(id);
        surveys.delete(survey);
    }

    @Transactional(readOnly = true)
    public Page<SurveyDTO> all(Pageable pageable) {
        Page<Survey> page = surveys.findAll(pageable);
        return new PageImpl<>(dtos(page.getContent()), pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public SurveyDTO get(UUID id) {
        return dtos(List.of(require(id))).getFirst();
    }

    @Transactional(readOnly = true)
    public SurveySummaryDTO summary(UUID id) {
        Survey survey = require(id);
        List<QuestionDTO> questions = readQuestions(survey.getQuestions());
        List<List<AnswerDTO>> responses = participations.findAllBySurveyIdAndStatus(id, Status.RESPONDED).stream()
                .map(participation -> readAnswers(participation.getAnswers()))
                .toList();
        return new SurveySummaryDTO(
                Objects.requireNonNull(counts(List.of(id)).get(id)), SurveyQuestions.summarize(questions, responses));
    }

    @Transactional(readOnly = true)
    public Page<SurveyResponseDTO> responses(UUID id, Pageable pageable) {
        require(id);
        Page<SurveyParticipation> page =
                participations.findAllBySurveyIdAndStatusNotOrderByDecidedAtDesc(id, Status.INVITED, pageable);
        FeedbackRefs.Resolved resolved = resolve(page.getContent());
        return page.map(p -> new SurveyResponseDTO(
                p.getId(),
                resolved.account(p.getAccountId()),
                resolved.workspace(p.getWorkspaceId()),
                p.getStatus(),
                p.getAnswers() == null ? null : readAnswers(p.getAnswers()),
                Objects.requireNonNull(p.getDecidedAt())));
    }

    @Transactional(readOnly = true)
    public String exportCsv(UUID id) {
        Survey survey = require(id);
        List<QuestionDTO> questions = readQuestions(survey.getQuestions());
        List<SurveyParticipation> rows = participations
                .findAllBySurveyIdAndStatusNotOrderByDecidedAtDesc(
                        id, Status.INVITED, PageRequest.of(0, EXPORT_MAX_ROWS))
                .getContent();
        FeedbackRefs.Resolved resolved = resolve(rows);
        StringBuilder csv = new StringBuilder();
        List<@Nullable String> header =
                new ArrayList<>(List.of("decided_at_utc", "status", "account_name", "account_email", "workspace"));
        questions.forEach(question -> header.add(question.prompt()));
        Csv.appendRow(csv, header);
        for (SurveyParticipation row : rows) {
            var account = resolved.account(row.getAccountId());
            var workspace = resolved.workspace(row.getWorkspaceId());
            List<@Nullable String> cells = new ArrayList<>(List.of(
                    String.valueOf(row.getDecidedAt()),
                    row.getStatus().name(),
                    account == null ? "" : account.displayName(),
                    account == null || account.email() == null ? "" : account.email(),
                    workspace == null ? "" : workspace.slug()));
            Map<String, AnswerDTO> answers = new HashMap<>();
            if (row.getAnswers() != null) readAnswers(row.getAnswers()).forEach(a -> answers.put(a.questionId(), a));
            for (QuestionDTO question : questions) {
                AnswerDTO answer = answers.get(question.id());
                cells.add(
                        answer == null
                                ? ""
                                : answer.text() != null
                                        ? answer.text()
                                        : answer.rating() != null
                                                ? answer.rating().toString()
                                                : String.join(
                                                        "; ", Objects.requireNonNullElse(answer.choices(), List.of())));
            }
            Csv.appendRow(csv, cells);
        }
        return csv.toString();
    }

    @Transactional(readOnly = true)
    public boolean isEligibleForEmail(UUID surveyId, long workspaceId, long accountId) {
        Survey survey = surveys.findById(surveyId).orElse(null);
        return survey != null
                && survey.isOpenFor(workspaceId, clock.instant())
                && participant(accountId).isOffered(survey)
                && participations
                        .findBySurveyIdAndAccountId(surveyId, accountId)
                        .map(participation -> participation.getStatus() == Status.INVITED)
                        .orElse(true);
    }

    @Transactional(readOnly = true)
    public List<SurveyInvitationDTO> invitations(Long workspaceId, Long accountId) {
        Instant now = clock.instant();
        Participant participant = participant(accountId);
        List<Survey> open = surveys.findAllByActiveTrueOrderByStartsAtAscCreatedAtAsc().stream()
                .filter(survey -> survey.isOpenFor(workspaceId, now) && participant.isOffered(survey))
                .toList();
        Map<UUID, Status> handled = new HashMap<>();
        participations
                .findAllBySurveyIdInAndAccountId(
                        open.stream().map(Survey::getId).toList(), accountId)
                .forEach(participation -> handled.put(participation.getSurveyId(), participation.getStatus()));
        return open.stream()
                .filter(survey -> handled.getOrDefault(survey.getId(), Status.INVITED) == Status.INVITED)
                .map(survey -> new SurveyInvitationDTO(
                        survey.getId(),
                        survey.getTitle(),
                        survey.getDescription(),
                        survey.getPurpose(),
                        survey.getResearchOrganization(),
                        readQuestions(survey.getQuestions()),
                        survey.getEndsAt(),
                        handled.containsKey(survey.getId())))
                .toList();
    }

    @Transactional
    public void markInvited(UUID id, Long workspaceId, Long accountId) {
        open(id, workspaceId, accountId);
        participations.insertIfAbsent(UUID.randomUUID(), id, accountId, workspaceId);
    }

    @Transactional
    public void respond(UUID id, Long workspaceId, Long accountId, SubmitSurveyDTO request) {
        Survey survey = open(id, workspaceId, accountId);
        List<AnswerDTO> answers =
                SurveyQuestions.validateAnswers(readQuestions(survey.getQuestions()), request.answers());
        invitation(id, workspaceId, accountId).respond(mapper.valueToTree(answers), workspaceId, clock.instant());
    }

    @Transactional
    public void decline(UUID id, Long workspaceId, Long accountId) {
        open(id, workspaceId, accountId);
        invitation(id, workspaceId, accountId).decline(workspaceId, clock.instant());
    }

    @Transactional
    public void undoDecline(UUID id, Long workspaceId, Long accountId) {
        open(id, workspaceId, accountId);
        participations
                .findBySurveyIdAndAccountId(id, accountId)
                .filter(participation -> participation.getStatus() == Status.DECLINED)
                .orElseThrow(() -> new EntityNotFoundException("Survey decline", id.toString()))
                .reinvite();
    }

    private Survey require(UUID id) {
        return surveys.findById(id).orElseThrow(() -> new EntityNotFoundException("Survey", id.toString()));
    }

    /**
     * A paused, unscheduled or foreign survey does not exist for a member, and neither does a research
     * survey for an account that has not agreed to the study: 404, never 403.
     */
    private Survey open(UUID id, Long workspaceId, Long accountId) {
        Survey survey = require(id);
        if (!survey.isOpenFor(workspaceId, clock.instant())
                || !participant(accountId).isOffered(survey))
            throw new EntityNotFoundException("Survey", id.toString());
        return survey;
    }

    private Participant participant(Long accountId) {
        return new Participant(research.researchOrganization().orElse(null), accountId);
    }

    /** One account's view of research surveys; consent is read at most once however many are open. */
    private final class Participant {
        private final @Nullable String organisation;
        private final Long accountId;
        private @Nullable Boolean participates;

        Participant(@Nullable String organisation, Long accountId) {
            this.organisation = organisation;
            this.accountId = accountId;
        }

        /** A research survey is offered only while the organisation it names is the one running the study. */
        boolean isOffered(Survey survey) {
            String studyOf = survey.getResearchOrganization();
            if (studyOf == null) return true;
            if (!studyOf.equals(organisation)) return false;
            if (participates == null) participates = research.participates(accountId);
            return participates;
        }
    }

    private SurveyParticipation invitation(UUID surveyId, Long workspaceId, Long accountId) {
        participations.insertIfAbsent(UUID.randomUUID(), surveyId, accountId, workspaceId);
        SurveyParticipation participation =
                participations.findBySurveyIdAndAccountId(surveyId, accountId).orElseThrow();
        if (participation.getStatus() != Status.INVITED)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "survey already answered or declined");
        return participation;
    }

    private List<SurveyDTO> dtos(List<Survey> list) {
        Map<UUID, ParticipationCountsDTO> counts =
                counts(list.stream().map(Survey::getId).toList());
        FeedbackRefs.Resolved resolved = refs.resolve(
                list.stream().map(Survey::getCreatedByAccountId).toList(),
                list.stream().map(Survey::getWorkspaceId).toList());
        return list.stream()
                .map(s -> new SurveyDTO(
                        s.getId(),
                        s.getTitle(),
                        s.getDescription(),
                        s.getPurpose(),
                        s.getResearchOrganization(),
                        readQuestions(s.getQuestions()),
                        resolved.workspace(s.getWorkspaceId()),
                        s.getStartsAt(),
                        s.getEndsAt(),
                        s.isActive(),
                        resolved.account(s.getCreatedByAccountId()),
                        Objects.requireNonNull(s.getCreatedAt()),
                        Objects.requireNonNull(counts.get(s.getId()))))
                .toList();
    }

    private FeedbackRefs.Resolved resolve(List<SurveyParticipation> rows) {
        return refs.resolve(
                rows.stream().map(SurveyParticipation::getAccountId).toList(),
                rows.stream().map(SurveyParticipation::getWorkspaceId).toList());
    }

    private Map<UUID, ParticipationCountsDTO> counts(List<UUID> ids) {
        Map<UUID, Map<Status, Long>> tallies = new HashMap<>();
        if (!ids.isEmpty())
            participations
                    .countBySurvey(ids)
                    .forEach(count -> tallies.computeIfAbsent(count.getSurveyId(), id -> new EnumMap<>(Status.class))
                            .put(count.getStatus(), count.getCount()));
        Map<UUID, ParticipationCountsDTO> result = new HashMap<>();
        for (UUID id : ids) {
            Map<Status, Long> tally = tallies.getOrDefault(id, Map.of());
            long responded = tally.getOrDefault(Status.RESPONDED, 0L);
            long declined = tally.getOrDefault(Status.DECLINED, 0L);
            result.put(
                    id,
                    new ParticipationCountsDTO(
                            tally.getOrDefault(Status.INVITED, 0L) + responded + declined, responded, declined));
        }
        return result;
    }

    private List<QuestionDTO> readQuestions(JsonNode json) {
        return mapper.convertValue(json, new TypeReference<>() {});
    }

    private List<AnswerDTO> readAnswers(@Nullable JsonNode json) {
        return json == null ? List.of() : mapper.convertValue(json, new TypeReference<>() {});
    }
}
