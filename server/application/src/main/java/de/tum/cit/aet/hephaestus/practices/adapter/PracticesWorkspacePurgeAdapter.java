package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.DeliveryPolicyEvaluationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeContributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter that handles practices cleanup when a workspace is purged.
 *
 * <p>This contributor deletes, in dependency order:
 * <ul>
 *   <li>Feedback (CASCADE cleans feedback_observation/placement/reaction/withdrawal)</li>
 *   <li>Observations (via native query through practice.workspace_id)</li>
 *   <li>Practice definitions for the workspace</li>
 *   <li>Practice groups (unreferenced once practices are gone)</li>
 * </ul>
 */
@Component
public class PracticesWorkspacePurgeAdapter implements WorkspacePurgeContributor {

    private static final Logger log = LoggerFactory.getLogger(PracticesWorkspacePurgeAdapter.class);

    private final DeliveryPolicyEvaluationRepository evaluationRepository;
    private final FeedbackDispatchRepository dispatchRepository;
    private final FeedbackRepository feedbackRepository;
    private final ObservationRepository observationRepository;
    private final PracticeRepository practiceRepository;
    private final PracticeGroupRepository practiceGroupRepository;

    public PracticesWorkspacePurgeAdapter(
            DeliveryPolicyEvaluationRepository evaluationRepository,
            FeedbackDispatchRepository dispatchRepository,
            FeedbackRepository feedbackRepository,
            ObservationRepository observationRepository,
            PracticeRepository practiceRepository,
            PracticeGroupRepository practiceGroupRepository) {
        this.evaluationRepository = evaluationRepository;
        this.dispatchRepository = dispatchRepository;
        this.feedbackRepository = feedbackRepository;
        this.observationRepository = observationRepository;
        this.practiceRepository = practiceRepository;
        this.practiceGroupRepository = practiceGroupRepository;
    }

    @Override
    public void deleteWorkspaceData(Long workspaceId) {
        evaluationRepository.deleteAllByWorkspaceId(workspaceId);
        dispatchRepository.deleteAllByWorkspaceId(workspaceId);
        // Delete feedback first (CASCADE cleans feedback_observation/placement/reaction/withdrawal). The purge is a
        // soft-delete, so the RESTRICT FK on feedback never fires — these rows must be removed explicitly.
        feedbackRepository.deleteAllByWorkspaceId(workspaceId);
        // Delete observations explicitly (defense-in-depth; CASCADE would also handle this).
        observationRepository.deleteAllByPracticeWorkspaceId(workspaceId);
        // Delete practice definitions (CASCADE cleans up any remaining observations); this also clears the
        // practice → practice_group references, so groups can be removed next.
        practiceRepository.deleteAllByWorkspaceId(workspaceId);
        // Delete practice groups (unreferenced once practices are gone).
        practiceGroupRepository.deleteAllByWorkspaceId(workspaceId);

        log.info("Deleted practice-review data for workspace: workspaceId={}", workspaceId);
    }

    @Override
    public int getOrder() {
        // Run early, before repository monitors are deleted (order 0 is default).
        return -100;
    }
}
