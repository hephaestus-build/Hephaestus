package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.TransactionCallbacks;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseRequestDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponse;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponseRepository;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeTrustMeters;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackUsefulness;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class FeedbackResponseService {

    private final FeedbackResponseRepository responses;
    private final FeedbackRepository feedbackRepository;
    private final FeedbackVisibilityService visibility;
    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final Clock clock;
    private final PersonDataWriteFence privacy;
    private final PracticeTrustMeters trust;

    public FeedbackResponseDTO replaceResponse(
            WorkspaceContext workspace, UUID feedbackId, FeedbackResponseRequestDTO request) {
        requireActualDeveloper();
        return respond(
                workspace.id(),
                feedbackId,
                currentDeveloperLookup.currentDeveloperIdElseThrow(),
                request.resolution(),
                request.usefulness(),
                request.comment());
    }

    public FeedbackResponseDTO respond(
            long workspaceId,
            UUID feedbackId,
            long developerId,
            @Nullable FeedbackResolution resolution,
            @Nullable FeedbackUsefulness usefulness,
            @Nullable String comment) {
        validate(resolution, usefulness, comment);
        requireWritable(developerId);
        requireShown(workspaceId, feedbackId, developerId, true);
        if (visibility.withdrawn(workspaceId, feedbackId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This feedback was withdrawn.");
        }
        var current = responses.findCurrent(workspaceId, feedbackId);
        if (current.filter(row -> row.getResolution() == resolution
                        && row.getUsefulness() == usefulness
                        && Objects.equals(row.getComment(), comment))
                .isPresent()) {
            return FeedbackResponseDTO.from(current.get());
        }
        // A new comment or usefulness on the same resolution is no new answer.
        if (resolution != null && current.map(FeedbackResponse::getResolution).orElse(null) != resolution) {
            TransactionCallbacks.afterCommit(() -> trust.responded(resolution));
        }
        return append(workspaceId, feedbackId, resolution, usefulness, comment);
    }

    public FeedbackResponseDTO deleteResponse(WorkspaceContext workspace, UUID feedbackId) {
        requireActualDeveloper();
        long developerId = currentDeveloperLookup.currentDeveloperIdElseThrow();
        requireWritable(developerId);
        requireShown(workspace.id(), feedbackId, developerId, true);
        var current = responses.findCurrent(workspace.id(), feedbackId);
        if (current.filter(
                        row -> row.getResolution() == null && row.getUsefulness() == null && row.getComment() == null)
                .isPresent()) {
            return FeedbackResponseDTO.from(current.get());
        }
        return append(workspace.id(), feedbackId, null, null, null);
    }

    @Transactional(readOnly = true)
    public FeedbackResponseDTO getResponse(WorkspaceContext workspace, UUID feedbackId) {
        requireShown(workspace.id(), feedbackId, currentDeveloperLookup.currentDeveloperIdElseThrow(), false);
        return responses
                .findCurrent(workspace.id(), feedbackId)
                .map(FeedbackResponseDTO::from)
                .orElseGet(FeedbackResponseDTO::empty);
    }

    private FeedbackResponseDTO append(
            long workspaceId,
            UUID feedbackId,
            @Nullable FeedbackResolution resolution,
            @Nullable FeedbackUsefulness usefulness,
            @Nullable String comment) {
        return FeedbackResponseDTO.from(responses.save(FeedbackResponse.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .feedbackId(feedbackId)
                .resolution(resolution)
                .usefulness(usefulness)
                .comment(comment)
                .createdAt(clock.instant())
                .build()));
    }

    private Feedback requireShown(long workspaceId, UUID feedbackId, long developerId, boolean lock) {
        Feedback feedback = (lock
                        ? feedbackRepository.lockByIdAndWorkspaceId(feedbackId, workspaceId)
                        : feedbackRepository.findByIdAndWorkspaceId(feedbackId, workspaceId))
                .filter(row -> row.getDeveloperId() == developerId && row.getDeliveredAt() != null)
                .orElseThrow(() -> new EntityNotFoundException("Feedback", feedbackId.toString()));
        return feedback;
    }

    private void requireWritable(long developerId) {
        if (!privacy.holdForAccountWrite(developerId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Your account cannot change feedback responses.");
        }
    }

    private static void validate(
            @Nullable FeedbackResolution resolution,
            @Nullable FeedbackUsefulness usefulness,
            @Nullable String comment) {
        if (resolution == null && usefulness == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a resolution or a usefulness rating.");
        }
        if (comment != null && comment.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Keep your comment within 2,000 characters.");
        }
        if (resolution == FeedbackResolution.DISAGREE && (comment == null || comment.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Add a comment to explain your disagreement.");
        }
    }

    private static void requireActualDeveloper() {
        if (UserViewContextHolder.get() != null)
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "You cannot change feedback while viewing another account.");
    }
}
