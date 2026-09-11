package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.exception.DataIntegrityViolationConstraints;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
class SurveyService {
    private final SurveyRepository surveys;
    private final SurveyParticipationRepository participations;
    private final FeedbackRefs refs;
    private final ObjectMapper mapper;
    private final Clock clock;

    // --- authoring -------------------------------------------------------------------------------

    @Transactional
    public SurveyDTO create(CreateSurveyDTO request, Long accountId) {
        requireSchedule(request.startsAt(), request.endsAt());
        SurveyQuestions.validateDefinition(request.questions());
        Survey survey = surveys.saveAndFlush(new Survey(
                request.title(),
                request.description(),
                mapper.valueToTree(request.questions()),
                request.workspaceId(),
                request.startsAt(),
                request.endsAt(),
                accountId));
        return dtos(List.of(survey)).getFirst();
    }

    @Transactional
    public SurveyDTO edit(UUID id, SurveyEditDTO request) {
        requireSchedule(request.startsAt(), request.endsAt());
        Survey survey = require(id);
        survey.edit(request.title(), request.description(), request.startsAt(), request.endsAt(), request.active());
        return dtos(List.of(survey)).getFirst();
    }

    @Transactional
    public void delete(UUID id) {
        Survey survey = require(id);
        participations.deleteAllBySurveyId(id);
        surveys.delete(survey);
    }

    @Transactional(readOnly = true)
    public Page<SurveyDTO> all(Pageable pageable) {
        Page<Survey> page = surveys.findAll(pageable);
        Map<UUID, SurveyDTO> content = new HashMap<>();
        dtos(page.getContent()).forEach(dto -> content.put(dto.id(), dto));
        return page.map(survey -> Objects.requireNonNull(content.get(survey.getId())));
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
        FeedbackRefs.Resolved resolved = refs.resolve(
                page.stream().map(SurveyParticipation::getAccountId),
                page.stream().map(SurveyParticipation::getWorkspaceId));
        return page.map(p -> new SurveyResponseDTO(
                p.getId(),
                resolved.account(p.getAccountId()),
                resolved.workspace(p.getWorkspaceId()),
                p.getStatus(),
                p.getAnswers() == null ? null : readAnswers(p.getAnswers()),
                Objects.requireNonNull(p.getDecidedAt())));
    }

    /** Every response and decline as RFC 4180 CSV, one column per question, newest first. */
    @Transactional(readOnly = true)
    public String exportCsv(UUID id) {
        Survey survey = require(id);
        List<QuestionDTO> questions = readQuestions(survey.getQuestions());
        List<SurveyParticipation> rows = participations
                .findAllBySurveyIdAndStatusNotOrderByDecidedAtDesc(id, Status.INVITED, Pageable.unpaged())
                .getContent();
        FeedbackRefs.Resolved resolved = refs.resolve(
                rows.stream().map(SurveyParticipation::getAccountId),
                rows.stream().map(SurveyParticipation::getWorkspaceId));
        StringBuilder csv = new StringBuilder();
        List<String> header =
                new ArrayList<>(List.of("decided_at_utc", "status", "account_name", "account_email", "workspace"));
        questions.forEach(question -> header.add(question.prompt()));
        appendCsvRow(csv, header);
        for (SurveyParticipation row : rows) {
            var account = resolved.account(row.getAccountId());
            var workspace = resolved.workspace(row.getWorkspaceId());
            List<String> cells = new ArrayList<>(List.of(
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
            appendCsvRow(csv, cells);
        }
        return csv.toString();
    }

    // --- participation --------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SurveyInvitationDTO> invitations(Long workspaceId, Long accountId) {
        List<Survey> open = surveys.findOpenFor(workspaceId, accountId, clock.instant());
        Set<UUID> seen = new HashSet<>();
        participations
                .findAllBySurveyIdInAndAccountId(
                        open.stream().map(Survey::getId).toList(), accountId)
                .forEach(participation -> seen.add(participation.getSurveyId()));
        return open.stream()
                .map(survey -> new SurveyInvitationDTO(
                        survey.getId(),
                        survey.getTitle(),
                        survey.getDescription(),
                        readQuestions(survey.getQuestions()),
                        survey.getEndsAt(),
                        seen.contains(survey.getId())))
                .toList();
    }

    /** Records that the invitation reached the account; idempotent, so every tab may report it. */
    @Transactional
    public void markInvited(UUID id, Long workspaceId, Long accountId) {
        open(id, workspaceId);
        if (participations.findBySurveyIdAndAccountId(id, accountId).isEmpty())
            saveNew(new SurveyParticipation(id, accountId, workspaceId), true);
    }

    @Transactional
    public void respond(UUID id, Long workspaceId, Long accountId, SubmitSurveyDTO request) {
        Survey survey = open(id, workspaceId);
        List<AnswerDTO> answers =
                SurveyQuestions.validateAnswers(readQuestions(survey.getQuestions()), request.answers());
        SurveyParticipation participation = participations
                .findBySurveyIdAndAccountId(id, accountId)
                .orElseGet(() -> saveNew(new SurveyParticipation(id, accountId, workspaceId), false));
        if (participation.getStatus() != Status.INVITED) throw handled();
        participation.respond(mapper.valueToTree(answers), workspaceId, clock.instant());
    }

    @Transactional
    public void decline(UUID id, Long workspaceId, Long accountId) {
        open(id, workspaceId);
        SurveyParticipation participation = participations
                .findBySurveyIdAndAccountId(id, accountId)
                .orElseGet(() -> saveNew(new SurveyParticipation(id, accountId, workspaceId), false));
        if (participation.getStatus() != Status.INVITED) throw handled();
        participation.decline(workspaceId, clock.instant());
    }

    /** Undoes a decline. A response is never touched: the undo exists for the accidental "No thanks". */
    @Transactional
    public void restore(UUID id, Long workspaceId, Long accountId) {
        open(id, workspaceId);
        participations
                .findBySurveyIdAndAccountId(id, accountId)
                .filter(participation -> participation.getStatus() == Status.DECLINED)
                .orElseThrow(() -> new EntityNotFoundException("Survey dismissal", id.toString()))
                .reinvite();
    }

    // --- helpers --------------------------------------------------------------------------------

    private Survey require(UUID id) {
        return surveys.findById(id).orElseThrow(() -> new EntityNotFoundException("Survey", id.toString()));
    }

    /** The survey as the workspace member may see it: a paused, unscheduled or foreign survey does not exist for them. */
    private Survey open(UUID id, Long workspaceId) {
        Survey survey = require(id);
        if (!survey.isOpenFor(workspaceId, clock.instant())) throw new EntityNotFoundException("Survey", id.toString());
        return survey;
    }

    private static void requireSchedule(Instant startsAt, @Nullable Instant endsAt) {
        if (endsAt != null && !endsAt.isAfter(startsAt))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endsAt must be after startsAt");
    }

    private static ResponseStatusException handled() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "survey already answered or declined");
    }

    /**
     * Two tabs can race to create the same participation row; the unique constraint decides. The loser
     * either reports the row as already invited (harmless) or as already handled (a conflict).
     */
    private SurveyParticipation saveNew(SurveyParticipation participation, boolean tolerateExisting) {
        try {
            return participations.saveAndFlush(participation);
        } catch (DataIntegrityViolationException e) {
            if (!DataIntegrityViolationConstraints.hasName(e, "uk_survey_participation_account")) throw e;
            if (tolerateExisting) return participation;
            throw new ResponseStatusException(HttpStatus.CONFLICT, "survey already handled", e);
        }
    }

    private List<SurveyDTO> dtos(List<Survey> list) {
        Map<UUID, ParticipationCountsDTO> counts =
                counts(list.stream().map(Survey::getId).toList());
        FeedbackRefs.Resolved resolved = refs.resolve(
                list.stream().map(Survey::getCreatedByAccountId), list.stream().map(Survey::getWorkspaceId));
        return list.stream()
                .map(s -> new SurveyDTO(
                        s.getId(),
                        s.getTitle(),
                        s.getDescription(),
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

    private Map<UUID, ParticipationCountsDTO> counts(List<UUID> ids) {
        Map<UUID, long[]> tallies = new HashMap<>();
        ids.forEach(id -> tallies.put(id, new long[3]));
        if (!ids.isEmpty())
            participations
                    .countBySurvey(ids)
                    .forEach(count -> Objects.requireNonNull(tallies.get(count.getSurveyId()))[
                                    count.getStatus().ordinal()] =
                            count.getCount());
        Map<UUID, ParticipationCountsDTO> result = new HashMap<>();
        tallies.forEach((id, tally) -> {
            long responded = tally[Status.RESPONDED.ordinal()];
            long declined = tally[Status.DECLINED.ordinal()];
            // Every row was an invitation once, whatever it became.
            result.put(
                    id,
                    new ParticipationCountsDTO(
                            tally[Status.INVITED.ordinal()] + responded + declined, responded, declined));
        });
        return result;
    }

    private List<QuestionDTO> readQuestions(JsonNode json) {
        return mapper.convertValue(json, new TypeReference<>() {});
    }

    private List<AnswerDTO> readAnswers(@Nullable JsonNode json) {
        return json == null ? List.of() : mapper.convertValue(json, new TypeReference<>() {});
    }

    private static void appendCsvRow(StringBuilder csv, List<String> cells) {
        csv.append(String.join(",", cells.stream().map(SurveyService::csvCell).toList()))
                .append('\n');
    }

    /** Quote every cell, double embedded quotes and normalise newlines, so any spreadsheet reads it back. */
    private static String csvCell(String value) {
        return '"' + value.replace("\"", "\"\"").replace("\r\n", "\n").replace('\r', '\n') + '"';
    }
}
