package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.PracticeReviewPolicy;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeMode;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepositoryRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceCommands;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class WorkspaceCommandsAdapter implements WorkspaceCommands {
    private final WorkspaceRepository workspaces;
    private final RepositoryToMonitorRepository monitors;
    private final ReviewScopeRepositoryRepository targets;

    @Override
    public void markPracticeCatalogInstalled(long workspaceId, Instant installedAt) {
        var workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow();
        workspace.setPracticeCatalogInstalledAt(installedAt);
        workspaces.save(workspace);
    }

    @Override
    public PracticeReviewPolicy saveReviewPolicy(
            long workspaceId, PracticeReviewPolicy policy, boolean rolloutChanged) {
        var workspace = workspaces.findByIdForUpdate(workspaceId).orElseThrow();
        var settings = workspace.getReviewSettings();
        settings.replaceOverrides(policy);
        if (rolloutChanged) settings.incrementRolloutRevision();
        settings.incrementConfigVersion();
        workspaces.save(workspace);
        return settings.snapshot();
    }

    @Override
    public void replaceReviewScope(
            long workspaceId, ReviewScopeMode repositories, ReviewScopeMode people, Map<Long, List<String>> requested) {
        var desired = new HashMap<>(requested);
        for (var target : targets.findByWorkspaceId(workspaceId)) {
            var branches = desired.remove(target.getRepositoryMonitorId());
            if (branches == null) targets.delete(target);
            else target.setBaseBranches(branches);
        }
        desired.forEach((id, branches) -> targets.save(new ReviewScopeRepository(workspaceId, id, branches)));
        workspaces.findById(workspaceId).orElseThrow().getReviewSettings().applyRollout(repositories, people, null);
    }

    @Override
    public void patchGeneratedPaths(long workspaceId, Map<Long, List<String>> paths) {
        for (var monitor : monitors.findByWorkspaceId(workspaceId)) {
            var patterns = paths.get(monitor.getId());
            if (patterns != null) monitor.setGeneratedPaths(patterns);
        }
    }
    // Both caps share one row. Its lock prevents Hibernate's all-column UPDATE from reverting the other cap.
    @Override
    public BudgetChange updateInstanceLlmBudget(String workspaceSlug, @Nullable BigDecimal monthlyBudgetUsd) {
        var workspace = workspaces
                .findByWorkspaceSlugForUpdate(workspaceSlug)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceSlug));
        var before = workspace.getMonthlyLlmBudgetUsd();
        workspace.setMonthlyLlmBudgetUsd(monthlyBudgetUsd);
        workspaces.save(workspace);
        return new BudgetChange(workspace.getId(), before);
    }

    @Override
    public BudgetChange updateOwnProviderLlmBudget(long workspaceId, @Nullable BigDecimal monthlyBudgetUsd) {
        var workspace = workspaces
                .findByIdForUpdate(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", Long.toString(workspaceId)));
        var before = workspace.getMonthlyByoLlmBudgetUsd();
        workspace.setMonthlyByoLlmBudgetUsd(monthlyBudgetUsd);
        workspaces.save(workspace);
        return new BudgetChange(workspace.getId(), before);
    }
}
