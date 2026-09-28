package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResolutionCountsDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackResponseRequestDTO;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.Reaction;
import de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
@RequiredArgsConstructor
public class FeedbackResponseService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackResponseService.class);

    private final ReactionRepository reactionRepository;
    private final FeedbackRepository feedbackRepository;
    private final CurrentDeveloperLookup currentDeveloperLookup;
    private final FeedbackWithdrawalRepository withdrawalRepository;

    public FeedbackResponseDTO replaceResponse(
            WorkspaceContext workspaceContext, UUID feedbackId, FeedbackResponseRequestDTO request) {
        long recipientId = currentDeveloperLookup.currentDeveloperIdElseThrow();
        // A withdrawn card takes no new answer, whatever a stale page still shows; the answers given before stay
        // readable and removable. The row lock is the one the withdrawal takes, so the two cannot interleave. It is
        // the first load of the row: Hibernate refuses to lock a read-only entity already in the session.
        feedbackRepository.lockByIdAndWorkspaceId(feedbackId, workspaceContext.id());
        Feedback feedback = requireDeliveredFeedback(workspaceContext.id(), feedbackId, recipientId);
        validate(request);
        if (withdrawalRepository.findActive(workspaceContext.id(), feedbackId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This feedback was withdrawn");
        }

        Optional<FeedbackResponseDTO> current = currentResponse(feedbackId, recipientId);
        if (current.filter(response -> sameResponse(response, request)).isPresent()) {
            return current.get();
        }

        Reaction response = Reaction.builder()
                .feedback(feedback)
                .reactorUserId(recipientId)
                .usefulness(request.usefulness())
                .resolution(request.resolution())
                .explanation(request.comment())
                .build();
        reactionRepository.save(response);

        log.info(
                "Replaced feedback response: feedbackId={}, usefulness={}, resolution={}, recipientUserId={}",
                feedbackId,
                response.getUsefulness(),
                response.getResolution(),
                recipientId);
        return currentResponse(feedbackId, recipientId).orElseGet(() -> FeedbackResponseDTO.none(feedbackId));
    }

    public void deleteResponse(WorkspaceContext workspaceContext, UUID feedbackId) {
        long recipientId = currentDeveloperLookup.currentDeveloperIdElseThrow();
        Feedback feedback = requireDeliveredFeedback(workspaceContext.id(), feedbackId, recipientId);
        if (currentResponse(feedbackId, recipientId).isEmpty()) {
            return;
        }
        reactionRepository.save(
                Reaction.builder().feedback(feedback).reactorUserId(recipientId).build());
        log.info("Deleted feedback response: feedbackId={}, recipientUserId={}", feedbackId, recipientId);
    }

    private boolean sameResponse(FeedbackResponseDTO current, FeedbackResponseRequestDTO replacement) {
        return (current.usefulness() == replacement.usefulness()
                && current.resolution() == replacement.resolution()
                && java.util.Objects.equals(current.comment(), replacement.comment()));
    }

    @Transactional(readOnly = true)
    public Optional<FeedbackResponseDTO> getResponse(WorkspaceContext workspaceContext, UUID feedbackId) {
        Optional<Long> recipient = currentDeveloperLookup.currentDeveloperId();
        if (recipient.isEmpty()) {
            return Optional.empty();
        }
        long recipientId = recipient.get();
        requireDeliveredFeedback(workspaceContext.id(), feedbackId, recipientId);
        return currentResponse(feedbackId, recipientId);
    }

    @Transactional(readOnly = true)
    public FeedbackResolutionCountsDTO getResolutionCounts(WorkspaceContext workspaceContext) {
        Optional<Long> recipient = currentDeveloperLookup.currentDeveloperId();
        if (recipient.isEmpty()) {
            return new FeedbackResolutionCountsDTO(0L, 0L, 0L);
        }
        long recipientId = recipient.get();
        Map<FeedbackResolution, Long> counts = new EnumMap<>(FeedbackResolution.class);
        reactionRepository
                .countByReactorAndWorkspaceGroupByAction(recipientId, workspaceContext.id())
                .forEach(projection ->
                        counts.put(FeedbackResolution.valueOf(projection.getAction()), projection.getCount()));
        return new FeedbackResolutionCountsDTO(
                counts.getOrDefault(FeedbackResolution.ADDRESSED, 0L),
                counts.getOrDefault(FeedbackResolution.DISPUTED, 0L),
                counts.getOrDefault(FeedbackResolution.NOT_APPLICABLE, 0L));
    }

    private Optional<FeedbackResponseDTO> currentResponse(UUID feedbackId, long recipientId) {
        return reactionRepository
                .findCurrentResponse(feedbackId, recipientId)
                .filter(current -> current.getUsefulness() != null || current.getResolution() != null)
                .map(current -> FeedbackResponseDTO.from(feedbackId, current));
    }

    private Feedback requireDeliveredFeedback(long workspaceId, UUID feedbackId, long recipientId) {
        return feedbackRepository
                .findByIdAndWorkspaceIdAndRecipientUserIdAndDeliveryState(
                        feedbackId, workspaceId, recipientId, FeedbackDeliveryState.DELIVERED)
                .orElseThrow(() -> new EntityNotFoundException("Feedback", feedbackId.toString()));
    }

    private void validate(FeedbackResponseRequestDTO request) {
        if (request.usefulness() == null && request.resolution() == null) {
            throw new IllegalArgumentException("A feedback response requires usefulness or resolution");
        }
        if (request.resolution() == FeedbackResolution.DISPUTED
                && (request.comment() == null || request.comment().isBlank())) {
            throw new IllegalArgumentException("A comment is required when disputing feedback");
        }
    }
}
