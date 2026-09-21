package de.tum.cit.aet.hephaestus.practices.observation;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.DeliveredFeedbackBinding;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.ObservationFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.dto.DeveloperPracticeSummaryProjection;
import de.tum.cit.aet.hephaestus.practices.observation.dto.ObservationDetailDTO;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunNarrativeLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunNarrativeLookup.ReviewRunNarrative;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for reading practice observations scoped to the authenticated developer.
 *
 * <p>All methods resolve the current user from the security context via
 * {@link UserRepository#getCurrentUser()}. If the user is not yet synced as a
 * developer (e.g., first login before any PR activity), list/summary endpoints
 * return empty results rather than failing.
 *
 * <p>For single-observation access, developer ownership is enforced in SQL — a
 * non-owner receives 404 (not 403) to avoid leaking observation existence.
 */
@Service
@RequiredArgsConstructor
public class ObservationService {

    private final ObservationRepository observationRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final UserRepository userRepository;
    private final ReviewRunTargetLookup reviewRunTargetLookup;
    private final ReviewRunNarrativeLookup reviewRunNarrativeLookup;
    private final EvidenceAuthorization evidenceAuthorization;

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
     * Single observation detail. Ownership is enforced in the SQL query itself —
     * an observation belonging to another developer simply won't be returned.
     *
     * @return the observation if it exists and belongs to the current user
     * @throws EntityNotFoundException if no user, or observation not found/not owned
     */
    @Transactional(readOnly = true)
    public ObservationDetailDTO getObservationDetail(Long workspaceId, UUID observationId) {
        Optional<User> currentUser = userRepository.getCurrentUser();
        if (currentUser.isEmpty()) {
            throw new EntityNotFoundException("Observation", observationId.toString());
        }
        Observation observation = observationRepository
                .findByIdAndDeveloperAndWorkspace(
                        observationId, currentUser.get().getId(), workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Observation", observationId.toString()));
        Set<UUID> evidencePermitted = evidenceAuthorization.permitsAll(
                workspaceId, List.of(observation), SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        List<UUID> jobIds = List.of(observation.getAgentJobId());
        Map<UUID, ReviewRunTargetLookup.Target> targets = reviewRunTargetLookup.findByJobIds(workspaceId, jobIds);
        Map<UUID, ReviewRunNarrative> narratives = reviewRunNarrativeLookup.findByJobIds(workspaceId, jobIds);
        return details(
                        workspaceId,
                        currentUser.get().getId(),
                        List.of(observation),
                        evidencePermitted,
                        targets,
                        narratives)
                .getFirst();
    }

    /**
     * The detail read model of each observation, in the given order, from one batched query per collaborator:
     * the guidance said about each observation (ADR 0021: advice lives on the delivered {@code Feedback}, not
     * the immutable observation) and the newest delivered feedback that carried it to this developer, whose
     * response the developer may edit. A caller hands in observations it has already loaded and gated: evidence
     * is included only for the ids in {@code evidencePermitted}, which is {@link EvidenceAuthorization}'s answer
     * for the delivery purpose, and the artifact link comes from the run target of the observation's job, absent
     * when the target is no longer resolvable.
     *
     * <p>Takes the workspace even though observation ids alone identify the rows: the bodies and bindings it
     * loads belong to feedback units, and feedback is tenant-scoped whatever the observation is.
     */
    @Transactional(readOnly = true)
    public List<ObservationDetailDTO> toDetails(
            Long workspaceId,
            Long developerId,
            List<Observation> observations,
            Set<UUID> evidencePermitted,
            Map<UUID, ReviewRunTargetLookup.Target> targets,
            Map<UUID, ReviewRunNarrative> narratives) {
        return details(workspaceId, developerId, observations, evidencePermitted, targets, narratives);
    }

    private List<ObservationDetailDTO> details(
            Long workspaceId,
            Long developerId,
            List<Observation> observations,
            Set<UUID> evidencePermitted,
            Map<UUID, ReviewRunTargetLookup.Target> targets,
            Map<UUID, ReviewRunNarrative> narratives) {
        if (observations.isEmpty()) {
            return List.of();
        }
        List<UUID> observationIds =
                observations.stream().map(Observation::getId).toList();
        Map<UUID, String> guidance = deliveredGuidanceByObservation(workspaceId, observationIds);
        Map<UUID, DeliveredFeedbackBinding> bindings =
                deliveredFeedbackByObservation(workspaceId, developerId, observationIds);
        return observations.stream()
                .map(observation -> {
                    ReviewRunTargetLookup.Target target = targets.get(observation.getAgentJobId());
                    ReviewRunNarrative narrative = narratives.get(observation.getAgentJobId());
                    return ObservationDetailDTO.from(
                            observation,
                            guidance.get(observation.getId()),
                            narrative == null ? null : narrative.nextStepFor(observation.getId()),
                            bindings.get(observation.getId()),
                            target == null ? null : target.url(),
                            evidencePermitted.contains(observation.getId()));
                })
                .toList();
    }

    private Map<UUID, String> deliveredGuidanceByObservation(Long workspaceId, Collection<UUID> observationIds) {
        return feedbackObservationRepository
                .findLatestFeedbackBodiesByObservationIds(workspaceId, observationIds, FEEDBACK_CHANNELS)
                .stream()
                .collect(Collectors.toMap(ObservationFeedbackBody::getObservationId, ObservationFeedbackBody::getBody));
    }

    private Map<UUID, DeliveredFeedbackBinding> deliveredFeedbackByObservation(
            Long workspaceId, Long recipientUserId, Collection<UUID> observationIds) {
        return feedbackObservationRepository
                .findDeliveredFeedbackBindings(workspaceId, recipientUserId, observationIds)
                .stream()
                .collect(Collectors.toMap(DeliveredFeedbackBinding::getObservationId, Function.identity()));
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
