package de.tum.cit.aet.hephaestus.workspace.adapter;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceQueryService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeRepositoryRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceProviderAccounts;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkspaceQueriesAdapter implements WorkspaceQueries, WorkspaceProviderAccounts {
    private final WorkspaceRepository workspaces;
    private final RepositoryToMonitorRepository monitors;
    private final ReviewScopeRepositoryRepository targets;
    private final WorkspaceQueryService metadata;

    @Override
    public Optional<ReviewWorkspace> findById(long workspaceId) {
        return workspaces.findById(workspaceId).map(WorkspaceQueriesAdapter::snapshot);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ReviewWorkspace> findByIdForUpdate(long workspaceId) {
        return workspaces.findByIdForUpdate(workspaceId).map(WorkspaceQueriesAdapter::snapshot);
    }

    @Override
    public List<RepositorySettings> repositorySettings(long workspaceId) {
        return monitors.findByWorkspaceId(workspaceId).stream()
                .map(row -> new RepositorySettings(row.getId(), row.getGeneratedPaths()))
                .toList();
    }

    @Override
    public List<RepositoryTarget> reviewRepositoryTargets(long workspaceId) {
        return targets.findByWorkspaceId(workspaceId).stream()
                .map(row -> new RepositoryTarget(row.getRepositoryMonitorId(), row.getBaseBranches()))
                .toList();
    }

    @Override
    public Optional<LlmBudgets> llmBudgets(long workspaceId) {
        return workspaces
                .findById(workspaceId)
                .map(workspace -> new LlmBudgets(
                        workspace.getId(), workspace.getMonthlyLlmBudgetUsd(), workspace.getMonthlyByoLlmBudgetUsd()));
    }

    @Override
    public @Nullable IdentityProviderType scmProviderType(long workspaceId) {
        return workspaces.findById(workspaceId).map(metadata::scmProviderType).orElse(null);
    }

    @Override
    public Optional<PublishedWorkspace> publicActivityWorkspace(String slug) {
        return workspaces
                .findPublicActivityWorkspace(slug)
                .map(workspace -> new PublishedWorkspace(
                        workspace.getId(),
                        workspace.getDisplayName(),
                        workspace.isPublicActivityContributorsWithoutAccount(),
                        workspace.isPublicActivitySearchEngines()));
    }

    @Override
    public Optional<OrganizationScope> organizationScope(long workspaceId) {
        return workspaces
                .findById(workspaceId)
                .map(workspace -> new OrganizationScope(workspace.getOrganizationId(), workspace.getAccountLogin()));
    }

    @Override
    public Optional<ProviderAccount> providerAccount(long workspaceId) {
        return workspaces
                .findMonitorPolicy(workspaceId)
                .map(policy -> new ProviderAccount(policy.getAccountLogin(), policy.getRepositorySelection()));
    }

    @Override
    public boolean organizationBound(long organizationId) {
        return workspaces.existsByOrganizationId(organizationId);
    }

    @Override
    public boolean bindsOrganization(long workspaceId, long organizationId) {
        return workspaces.existsByIdAndOrganizationId(workspaceId, organizationId);
    }

    private static ReviewWorkspace snapshot(Workspace workspace) {
        var features = workspace.getFeatures();
        var settings = workspace.getReviewSettings();
        return new ReviewWorkspace(
                WorkspaceContext.fromWorkspace(workspace, Set.of(), null),
                workspace.getStatus() == Workspace.WorkspaceStatus.ACTIVE,
                new ReviewFeatures(
                        Boolean.TRUE.equals(features.getPracticesEnabled()),
                        Boolean.TRUE.equals(features.getPracticeReviewAutoTriggerEnabled()),
                        Boolean.TRUE.equals(features.getPracticeReviewManualTriggerEnabled())),
                settings.snapshot(),
                workspace.getPracticeCatalogInstalledAt());
    }
}
