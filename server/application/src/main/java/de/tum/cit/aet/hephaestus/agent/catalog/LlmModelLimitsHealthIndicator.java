package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Names each enabled instance model whose calls the proxy refuses because it knows no output limit ({@link
 * ModelLimits#bounded()}), and counts the workspaces' own such models. It stays {@code UP} and outside readiness: the
 * server serves its API either way, and a model that cannot run must not take the server out of rotation.
 */
@Component
@WorkspaceAgnostic("Counts every workspace's unbounded models without naming them")
public class LlmModelLimitsHealthIndicator implements HealthIndicator {

    static final String FIX = "Set Max output tokens or Context window in AI providers, or test the model's connection";

    private final LlmModelRepository modelRepository;
    private final WorkspaceLlmModelRepository workspaceModelRepository;

    LlmModelLimitsHealthIndicator(
            LlmModelRepository modelRepository, WorkspaceLlmModelRepository workspaceModelRepository) {
        this.modelRepository = modelRepository;
        this.workspaceModelRepository = workspaceModelRepository;
    }

    @Override
    public Health health() {
        List<String> unbounded = modelRepository.findAllWithConnection().stream()
                .filter(model -> model.isEnabled() && model.getConnection().isEnabled())
                .filter(model -> !model.limits().bounded())
                .map(model -> model.getConnection().getDisplayName() + " / " + model.getDisplayName())
                .toList();
        long workspaceUnbounded = workspaceModelRepository.findAllEnabledWithEnabledConnection().stream()
                .filter(model -> !model.limits().bounded())
                .count();
        var health = Health.up()
                .withDetail("modelsWithoutOutputLimit", unbounded)
                .withDetail("workspaceModelsWithoutOutputLimit", workspaceUnbounded);
        if (!unbounded.isEmpty() || workspaceUnbounded > 0) health.withDetail("fix", FIX);
        return health.build();
    }
}
