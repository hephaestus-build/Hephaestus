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

        var connection = connections.save(LlmCatalogTestFixtures.connection("review-delivery"));
        var model = models.save(LlmCatalogTestFixtures.model(connection, "review-delivery", "test-model"));
        var binding = new WorkspaceAgentBinding();
        binding.setWorkspace(workspace);
        binding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        binding.setInstanceModel(model);
        return ConfigSnapshot.from(bindings.saveAndFlush(binding), resolver).toJson(mapper);
    }
}
