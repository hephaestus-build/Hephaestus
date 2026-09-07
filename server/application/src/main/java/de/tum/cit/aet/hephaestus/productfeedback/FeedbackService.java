package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.exception.DataIntegrityViolationConstraints;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.CreateSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackItemDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.FeedbackRequestDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionType;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SubmissionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SubmitSurveyDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.SurveyDTO;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
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
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
class FeedbackService {
    private final SurveyRepository surveys;
    private final SurveySubmissionRepository submissions;
    private final ProductFeedbackRepository feedback;
    private final ObjectMapper mapper;

    @Transactional
    public SurveyDTO create(CreateSurveyDTO request, Long accountId) {
        if (request.endsAt() != null && !request.endsAt().isAfter(request.startsAt()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endsAt must be after startsAt");
        Set<String> ids = new HashSet<>();
        for (QuestionDTO q : request.questions()) {
            if (!ids.add(q.id()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question ids must be unique");
            if (q.type() == QuestionType.SINGLE_CHOICE
                    && (q.options().size() < 2
                            || new HashSet<>(q.options()).size() != q.options().size()))
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "single-choice questions need at least two unique options");
            if (q.type() != QuestionType.SINGLE_CHOICE && !q.options().isEmpty())
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "only single-choice questions accept options");
        }
        return dto(surveys.save(new Survey(
                request.title(),
                request.description(),
                mapper.valueToTree(request.questions()),
                request.workspaceId(),
                request.startsAt(),
                request.endsAt(),
                accountId)));
    }

    @Transactional(readOnly = true)
    public List<SurveyDTO> available(Long workspaceId, Long accountId) {
        return surveys.findAvailable(workspaceId, accountId, Instant.now()).stream()
                .map(this::dto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<SurveyDTO> allSurveys(Pageable pageable) {
        return surveys.findAll(pageable).map(this::dto);
    }

    @Transactional
    public SurveyDTO setActive(UUID id, boolean active) {
        Survey survey = surveys.findById(id).orElseThrow(() -> new EntityNotFoundException("Survey", id.toString()));
        survey.setActive(active);
        return dto(survey);
    }

    @Transactional
    public void submit(UUID id, Long workspaceId, Long accountId, SubmitSurveyDTO request) {
        Survey survey = targeted(id, workspaceId);
        List<QuestionDTO> questions = readQuestions(survey.getQuestions());
        Set<String> valid = new HashSet<>();
        questions.forEach(q -> valid.add(q.id()));
        if (!valid.containsAll(request.answers().keySet())
                || questions.stream()
                        .anyMatch(q -> q.required()
                                && request.answers().getOrDefault(q.id(), "").isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "answers do not match the survey");
        for (QuestionDTO question : questions) {
            String answer = request.answers().get(question.id());
            if (answer == null) continue;
            if (question.type() == QuestionType.SINGLE_CHOICE
                    && !question.options().contains(answer))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid choice");
            if (question.type() == QuestionType.RATING
                    && !List.of("1", "2", "3", "4", "5").contains(answer))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid rating");
        }
        saveOnce(new SurveySubmission(
                id,
                accountId,
                workspaceId,
                SurveySubmission.Disposition.RESPONDED,
                mapper.valueToTree(request.answers())));
    }

    @Transactional
    public void dismiss(UUID id, Long workspaceId, Long accountId) {
        targeted(id, workspaceId);
        saveOnce(new SurveySubmission(id, accountId, workspaceId, SurveySubmission.Disposition.DISMISSED, null));
    }

    @Transactional
    public void restore(UUID id, Long workspaceId, Long accountId) {
        targeted(id, workspaceId);
        submissions.deleteDismissal(id, accountId);
    }

    @Transactional
    public ProductFeedback addFeedback(FeedbackRequestDTO request, Long accountId, @Nullable Long workspaceId) {
        try {
            return feedback.saveAndFlush(
                    new ProductFeedback(accountId, workspaceId, request.kind(), request.message(), request.pagePath()));
        } catch (DataIntegrityViolationException exception) {
            if (DataIntegrityViolationConstraints.hasName(exception, "uk_product_feedback_rate_limit")) {
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS, "feedback is limited to once per minute", exception);
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public Page<FeedbackItemDTO> feedback(Pageable pageable) {
        return feedback.findAllByOrderByCreatedAtDesc(pageable)
                .map(f -> new FeedbackItemDTO(
                        f.getId(),
                        f.getAccountId(),
                        f.getWorkspaceId(),
                        f.getKind(),
                        f.getMessage(),
                        f.getPagePath(),
                        f.getCreatedAt()));
    }

    @Transactional(readOnly = true)
    public Page<SubmissionDTO> responses(Pageable pageable) {
        Page<SurveySubmission> page = submissions.findAllByOrderByCreatedAtDesc(pageable);
        Map<UUID, Survey> surveysById =
                surveys
                        .findAllById(
                                page.stream().map(SurveySubmission::getSurveyId).collect(Collectors.toSet()))
                        .stream()
                        .collect(Collectors.toMap(Survey::getId, survey -> survey));
        return page.map(s -> {
            Survey survey = surveysById.get(s.getSurveyId());
            if (survey == null)
                throw new EntityNotFoundException("Survey", s.getSurveyId().toString());
            return new SubmissionDTO(
                    s.getId(),
                    s.getSurveyId(),
                    survey.getTitle(),
                    readQuestions(survey.getQuestions()),
                    s.getAccountId(),
                    s.getWorkspaceId(),
                    s.getDisposition(),
                    s.getAnswers() == null ? null : readAnswers(s.getAnswers()),
                    s.getCreatedAt());
        });
    }

    private Survey targeted(UUID id, Long workspaceId) {
        Survey s = surveys.findById(id).orElseThrow(() -> new EntityNotFoundException("Survey", id.toString()));
        if (!s.isActive()
                || s.getStartsAt().isAfter(Instant.now())
                || (s.getEndsAt() != null && !s.getEndsAt().isAfter(Instant.now()))
                || (s.getWorkspaceId() != null && !s.getWorkspaceId().equals(workspaceId)))
            throw new EntityNotFoundException("Survey", id.toString());
        return s;
    }

    private void saveOnce(SurveySubmission s) {
        try {
            submissions.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) {
            if (DataIntegrityViolationConstraints.hasName(e, "uk_survey_submission_account")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "survey already handled", e);
            }
            throw e;
        }
    }

    private SurveyDTO dto(Survey s) {
        return new SurveyDTO(
                s.getId(),
                s.getTitle(),
                s.getDescription(),
                readQuestions(s.getQuestions()),
                s.getWorkspaceId(),
                s.getStartsAt(),
                s.getEndsAt(),
                s.isActive(),
                s.getCreatedAt());
    }

    private List<QuestionDTO> readQuestions(tools.jackson.databind.JsonNode json) {
        return mapper.convertValue(json, new TypeReference<>() {});
    }

    private Map<String, String> readAnswers(tools.jackson.databind.JsonNode json) {
        return mapper.convertValue(json, new TypeReference<>() {});
    }
}
