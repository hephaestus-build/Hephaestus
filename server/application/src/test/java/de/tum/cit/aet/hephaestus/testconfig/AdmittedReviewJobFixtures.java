package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A real legacy-slot route for delivery tests whose member has not made an optional AI choice. */
public final class AdmittedReviewJobFixtures {

    private AdmittedReviewJobFixtures() {}

    public static JsonNode snapshot(
            Workspace workspace,
            LlmConnectionRepository connections,
            LlmModelRepository models,
            WorkspaceAgentBindingRepository bindings,
            LlmModelResolver resolver,
            ObjectMapper mapper) {
        var existing = bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                workspace.getId(), AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED);
        if (existing.isPresent())
            return ConfigSnapshot.from(existing.get(), resolver).toJson(mapper);

        // Slugs are unique per instance; earlier tests' rows are still there.
        String slug = "review-delivery-" + workspace.getId();
        var connection = connections.save(LlmCatalogTestFixtures.connection(slug));
        var model = models.save(LlmCatalogTestFixtures.model(connection, slug, "test-model"));
        var binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setInstanceModel(model);
        return ConfigSnapshot.from(bindings.saveAndFlush(binding), resolver).toJson(mapper);
    }

    /** A conversation-capable fixture reuses the review's configured model through a distinct Mentor binding. */
    public static WorkspaceAgentBinding mentorBinding(Workspace workspace, WorkspaceAgentBindingRepository bindings) {
        var existing = bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                workspace.getId(), AgentPurpose.MENTOR, DataHandlingTier.UNDECLARED);
        if (existing.isPresent()) return existing.get();
        var review = bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        workspace.getId(), AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED)
                .orElseThrow();
        var mentor = new WorkspaceAgentBinding();
        mentor.setWorkspace(workspace);
        mentor.setPurpose(AgentPurpose.MENTOR);
        mentor.setInstanceModel(review.getInstanceModel());
        mentor.setWorkspaceModel(review.getWorkspaceModel());
        return bindings.saveAndFlush(mentor);
    }
}
