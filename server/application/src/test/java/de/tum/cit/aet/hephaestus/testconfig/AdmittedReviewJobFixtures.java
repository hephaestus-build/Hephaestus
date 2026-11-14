package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.ModelPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignment;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignmentRepository;
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
            WorkspaceModelAssignmentRepository bindings,
            LlmModelResolver resolver,
            ObjectMapper mapper) {
        var existing = bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                workspace.getId(), ModelPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED);
        if (existing.isPresent())
            return ConfigSnapshot.from(existing.get(), resolver).toJson(mapper);

        // Slugs are unique per instance; earlier tests' rows are still there.
        String slug = "review-delivery-" + workspace.getId();
        var connection = connections.save(LlmCatalogTestFixtures.connection(slug));
        var model = models.save(LlmCatalogTestFixtures.model(connection, slug, "test-model"));
        var binding = new WorkspaceModelAssignment();
        binding.setWorkspace(workspace);
        binding.setPurpose(ModelPurpose.PRACTICE_REVIEW);
        binding.setInstanceModel(model);
        return ConfigSnapshot.from(bindings.saveAndFlush(binding), resolver).toJson(mapper);
    }

    /** A conversation-capable fixture reuses the review's configured model through a distinct Conversation binding. */
    public static WorkspaceModelAssignment conversationBinding(
            Workspace workspace, WorkspaceModelAssignmentRepository bindings) {
        var existing = bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                workspace.getId(), ModelPurpose.CONVERSATION, DataHandlingTier.UNDECLARED);
        if (existing.isPresent()) return existing.get();
        var review = bindings.findByWorkspaceIdAndPurposeAndDataHandlingTier(
                        workspace.getId(), ModelPurpose.PRACTICE_REVIEW, DataHandlingTier.UNDECLARED)
                .orElseThrow();
        var conversation = new WorkspaceModelAssignment();
        conversation.setWorkspace(workspace);
        conversation.setPurpose(ModelPurpose.CONVERSATION);
        conversation.setInstanceModel(review.getInstanceModel());
        conversation.setWorkspaceModel(review.getWorkspaceModel());
        return bindings.saveAndFlush(conversation);
    }
}
