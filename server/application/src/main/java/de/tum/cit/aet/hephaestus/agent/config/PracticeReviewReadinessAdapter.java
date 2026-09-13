package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeReviewReadiness;
import org.springframework.stereotype.Component;

/** {@link PracticeReviewReadiness} backed by the per-purpose binding table. */
@Component
public class PracticeReviewReadinessAdapter implements PracticeReviewReadiness {

    private final WorkspaceAgentBindingRepository bindingRepository;
    private final LlmModelResolver llmModelResolver;

    public PracticeReviewReadinessAdapter(
            WorkspaceAgentBindingRepository bindingRepository, LlmModelResolver llmModelResolver) {
        this.bindingRepository = bindingRepository;
        this.llmModelResolver = llmModelResolver;
    }

    // The graph-fetching lookup is what lets the availability check run outside a transaction.
    @Override
    public boolean hasRunnableAgent(Long workspaceId) {
        return bindingRepository.findByWorkspaceIdWithModels(workspaceId).stream()
                .filter(binding -> binding.getPurpose() == AgentPurpose.PRACTICE_REVIEW)
                .filter(WorkspaceAgentBinding::isEnabled)
                .anyMatch(llmModelResolver::isAvailable);
    }
}
