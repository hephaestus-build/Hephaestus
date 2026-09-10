package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmProcessingLocation;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceAiAvailability;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberAiRoutingAdapter implements WorkspaceAiAvailability {
    private final WorkspaceAgentBindingRepository bindings;
    private final MemberAiPreferences preferences;
    private final LlmModelResolver models;
    private final WorkspaceRepository workspaces;

    @Transactional(readOnly = true)
    public Optional<WorkspaceAgentBinding> binding(long workspaceId, AgentPurpose purpose, @Nullable Long developerId) {
        var decision = preferences.forDeveloper(workspaceId, developerId);
        if (!decision.permitsAi()) return Optional.empty();
        var location = decision.choice() == null ? LlmProcessingLocation.UNCLASSIFIED : location(decision.choice());
        return bindings.findByWorkspaceIdAndPurposeAndProcessingLocation(workspaceId, purpose, location)
                .filter(WorkspaceAgentBinding::isEnabled)
                .filter(models::isAvailable);
    }

    @Transactional(readOnly = true)
    public boolean allows(long workspaceId, @Nullable Long developerId, LlmModelResolver.ConnectionRef model) {
        var decision = preferences.forDeveloper(workspaceId, developerId);
        return decision.permitsAi()
                && (decision.choice() == null || location(decision.choice()) == models.processingLocation(model));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Option> options(long workspaceId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        return List.of(MemberAiChoice.ON_PREMISES, MemberAiChoice.PRIVATE_CLOUD).stream()
                .map(choice -> new Option(
                        choice,
                        Boolean.TRUE.equals(workspace.getFeatures().getPracticesEnabled())
                                && ready(workspaceId, AgentPurpose.PRACTICE_REVIEW, choice),
                        Boolean.TRUE.equals(workspace.getFeatures().getMentorEnabled())
                                && ready(workspaceId, AgentPurpose.MENTOR, choice)))
                .toList();
    }

    private boolean ready(long workspaceId, AgentPurpose purpose, MemberAiChoice choice) {
        return bindings.findByWorkspaceIdAndPurposeAndProcessingLocation(workspaceId, purpose, location(choice))
                .filter(WorkspaceAgentBinding::isEnabled)
                .filter(models::isAvailable)
                .isPresent();
    }

    private static LlmProcessingLocation location(MemberAiChoice choice) {
        return switch (choice) {
            case ON_PREMISES -> LlmProcessingLocation.ON_PREMISES;
            case PRIVATE_CLOUD -> LlmProcessingLocation.PRIVATE_CLOUD;
            case NO_AI -> throw new IllegalArgumentException("No AI has no model route");
        };
    }
}
