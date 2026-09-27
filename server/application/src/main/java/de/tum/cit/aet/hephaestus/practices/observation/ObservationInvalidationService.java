package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationInvalidation;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * A workspace admin's correction of one observation, and its reversal. Neither touches the observation, and
 * restoring does not re-send feedback the invalidation stopped: that feedback was withheld for a reason that
 * was true when it was recorded.
 */
@Service
@RequiredArgsConstructor
public class ObservationInvalidationService {

    private final ObservationRepository observationRepository;
    private final ObservationInvalidationRepository invalidationRepository;
    private final FeedbackRepository feedbackRepository;
    private final FeedbackDispatchRepository dispatchRepository;
    private final Clock clock;

    @Transactional
    public void setValidity(long workspaceId, UUID observationId, long accountId, boolean valid, String reason) {
        Observation observation = observationRepository
                .lockByIdAndWorkspaceId(observationId, workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Observation", observationId.toString()));
        if (!valid
                && dispatchRepository.existsInFlightCiting(workspaceId, observationId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Hephaestus is delivering feedback that cites this observation right now. Try again in a moment.");
        }
        var active = invalidationRepository.findActive(workspaceId, observationId);
        if (valid) {
            active.orElseThrow(() ->
                            new ResponseStatusException(HttpStatus.CONFLICT, "This observation is not invalidated"))
                    .restore(accountId, reason.strip(), clock.instant());
            return;
        }
        if (active.isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This observation is already invalidated");
        }
        invalidationRepository.save(
                new ObservationInvalidation(observation, accountId, reason.strip(), clock.instant()));
        feedbackRepository.suppressUndeliveredCiting(
                workspaceId, observationId, FeedbackSuppressionReason.OBSERVATION_INVALIDATED.name());
    }
}
