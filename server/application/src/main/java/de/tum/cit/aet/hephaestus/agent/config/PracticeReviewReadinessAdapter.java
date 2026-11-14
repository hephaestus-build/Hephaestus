package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeReviewReadiness;
import org.springframework.stereotype.Component;

/** {@link PracticeReviewReadiness} backed by the per-purpose binding table. */
@Component
public class PracticeReviewReadinessAdapter implements PracticeReviewReadiness {

    private final WorkspaceModelAssignmentRepository bindingRepository;
    private final LlmModelResolver llmModelResolver;

    public PracticeReviewReadinessAdapter(
            WorkspaceModelAssignmentRepository bindingRepository, LlmModelResolver llmModelResolver) {
        this.bindingRepository = bindingRepository;
        this.llmModelResolver = llmModelResolver;
    }

    // The graph-fetching lookup is what lets the availability check run outside a transaction.
    @Override
    public boolean hasRunnableAgent(Long workspaceId) {
        return bindingRepository.findByWorkspaceIdWithModels(workspaceId).stream()
                .filter(binding -> binding.getPurpose() == ModelPurpose.PRACTICE_REVIEW)
                .filter(WorkspaceModelAssignment::isEnabled)
                .anyMatch(llmModelResolver::isAvailable);
    }
}
