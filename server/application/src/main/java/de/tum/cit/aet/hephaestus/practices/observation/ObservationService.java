package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.ObservationFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.dto.DeveloperPracticeSummaryProjection;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Developer-scoped observation reads and workspace-scoped supporting queries.
 * Pull-request reads intentionally include observations about other developers.
 */
@Service
@RequiredArgsConstructor
public class ObservationService {

    private final ObservationRepository observationRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final UserRepository userRepository;
    private final ReviewRunTargetLookup reviewRunTargetLookup;

    /** Feed ordering: by observation time or by severity (direction applies to both). */
    public enum ObservationSort {
        DATE,
        SEVERITY,
    }

    /**
     * Paginated observations for the current user in a workspace, with optional filters.
     *
     * @return empty page if user is not a synced developer
     */
    @Transactional(readOnly = true)
    public Page<Observation> getObservations(Long workspaceId, ObservationFeedQuery query, Pageable pageable) {
        Optional<User> currentUser = userRepository.getCurrentUser();
        if (currentUser.isEmpty()) {
            return Page.empty(pageable);
        }
        // An IN () over an empty list is invalid SQL. The flags disable empty filters, while the placeholder
        // values keep the query parseable.
        boolean hasArtifactKinds =
                query.artifactKinds() != null && !query.artifactKinds().isEmpty();
        boolean hasSeverities =
                query.severities() != null && !query.severities().isEmpty();
        List<ArtifactKind> artifactKinds =
                hasArtifactKinds ? Objects.requireNonNull(query.artifactKinds()) : List.of(ArtifactKinds.PULL_REQUEST);
        List<Severity> severities = hasSeverities ? Objects.requireNonNull(query.severities()) : List.of(Severity.INFO);
        if (query.sort() == ObservationSort.SEVERITY) {
            return observationRepository.findByAboutUserAndWorkspaceSeverityFirst(
                    currentUser.get().getId(),
                    workspaceId,
                    query.practiceSlug(),
                    query.groupSlug(),
                    query.presence(),
                    hasArtifactKinds,
                    artifactKinds,
                    hasSeverities,
                    severities,
                    query.displayableOnly(),
                    query.mostSevereFirst() ? 1 : -1,
                    pageable);
        }
        return observationRepository.findByAboutUserAndWorkspace(
                currentUser.get().getId(),
                workspaceId,
                query.practiceSlug(),
                query.groupSlug(),
                query.presence(),
                hasArtifactKinds,
                artifactKinds,
                hasSeverities,
                severities,
                query.displayableOnly(),
                pageable);
    }

    /**
     * Link to the reviewed artifact behind an observation. Empty when the run target is unknown, for example
     * after the artifact was deleted.
     */
    @Transactional(readOnly = true)
    public Optional<String> getArtifactUrl(Long workspaceId, Observation observation) {
        return Optional.ofNullable(reviewRunTargetLookup
                        .findByJobIds(workspaceId, List.of(observation.getAgentJobId()))
                        .get(observation.getAgentJobId()))
                .map(ReviewRunTargetLookup.Target::url);
    }

    /** Per-practice observation counts for the current user in a workspace. */
    @Transactional(readOnly = true)
    public List<DeveloperPracticeSummaryProjection> getSummary(Long workspaceId) {
        Optional<User> currentUser = userRepository.getCurrentUser();
        if (currentUser.isEmpty()) {
            return List.of();
        }
        return observationRepository.findSummaryByDeveloperAndWorkspace(
                currentUser.get().getId(), workspaceId);
    }

    /**
     * The lanes whose text this read model's {@code guidance} means: the ones that speak about the one
     * observation they are bound to. A {@code IN_APP} unit is excluded because it is a message about a
     * habit across several pieces of work — it binds every problem behind it as evidence, so it would
     * answer "what did you tell me about this observation" with a paragraph that is explicitly not about it.
     * Named here rather than defaulted in the query so a fourth lane has to be admitted deliberately.
     */
    private static final List<String> FEEDBACK_CHANNELS =
            List.of(FeedbackChannel.IN_CONTEXT.name(), FeedbackChannel.IN_CHAT.name());

    /**
     * Missing and unowned observations both raise not-found to avoid disclosing their existence.
     *
     * @return the observation if it exists and belongs to the current user
     * @throws EntityNotFoundException if no user, or observation not found/not owned
     */
    @Transactional(readOnly = true)
    public Observation getObservation(Long workspaceId, UUID observationId) {
        Optional<User> currentUser = userRepository.getCurrentUser();
        if (currentUser.isEmpty()) {
            throw new EntityNotFoundException("Observation", observationId.toString());
        }
        return observationRepository
                .findByIdAndDeveloperAndWorkspace(
                        observationId, currentUser.get().getId(), workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Observation", observationId.toString()));
    }

    /**
     * The delivered feedback body for a single observation — the developer's advice source for the detail view
     * (ADR 0021: advice lives on the delivered {@code Feedback}, not the immutable observation). Null when the
     * observation was never delivered. Callers pass this into {@code ObservationDetailDTO.from}.
     *
     * <p>Takes the workspace even though the observation id alone identifies a row: the body it returns
     * belongs to a feedback unit, and feedback is tenant-scoped whatever the observation is.
     */
    @Transactional(readOnly = true)
    public Optional<String> getDeliveredGuidance(Long workspaceId, UUID observationId) {
        return Optional.ofNullable(deliveredFeedbackByObservation(workspaceId, Set.of(observationId))
                .get(observationId));
    }

    private Map<UUID, String> deliveredFeedbackByObservation(Long workspaceId, Set<UUID> observationIds) {
        if (observationIds.isEmpty()) {
            return Map.of();
        }
        return feedbackObservationRepository
                .findLatestFeedbackBodiesByObservationIds(workspaceId, observationIds, FEEDBACK_CHANNELS)
                .stream()
                .collect(Collectors.toMap(ObservationFeedbackBody::getObservationId, ObservationFeedbackBody::getBody));
    }

    /**
     * All observations for a specific pull request within a workspace.
     * Any workspace member can view PR observations (not restricted to the PR author).
     */
    @Transactional(readOnly = true)
    public List<Observation> getObservationsForPullRequest(Long workspaceId, Long pullRequestId) {
        return observationRepository.findByPullRequestAndWorkspace(
                ArtifactKinds.PULL_REQUEST, pullRequestId, workspaceId);
    }
}
