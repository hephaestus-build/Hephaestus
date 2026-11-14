package de.tum.cit.aet.hephaestus.architecture;

import java.util.List;
import java.util.Set;

/**
 * Exact repository-access debt measured after the sandbox and inbox cutovers (ADR 0099).
 * Each owner removes its consumer witnesses as it introduces the named interface below.
 * The GitHub and GitLab adapters share the SCM mirror by ADR 0095; no other pair is exempt.
 */
final class RepositoryOwnershipRatchet {
    record Access(String consumingModule, String consumer, String repository) {}

    record Entry(String consumingModule, String repository, String owner, String target, Set<String> consumers) {
        List<Access> accesses() {
            return consumers.stream()
                    .map(consumer -> new Access(consumingModule, consumer, repository))
                    .toList();
        }

        String description() {
            return consumingModule + " → " + repository + " (" + consumers.size() + " consumers); " + owner + ": "
                    + target;
        }
    }

    private RepositoryOwnershipRatchet() {}

    static final List<Entry> ENTRIES = List.of(
            new Entry(
                    "integration.core",
                    "workspace.WorkspaceRepository",
                    "DDD9 (ROLE)",
                    "workspace::spi WorkspaceSummaryQuery.findStateForUpdate; workspace-owned connection initiation",
                    Set.of("integration.core.oauth.state.OAuthStateNonceStore")),
            new Entry(
                    "integration.scm",
                    "integration.core.item.ItemRepository",
                    "DDD12 (integration.core)",
                    "integration.core::spi ItemQueries and existing ItemIndexService commands",
                    Set.of(
                            "integration.scm.domain.item.ScmItemAccess",
                            "integration.scm.domain.item.ScmRepositoryItemLifecycle")),
            new Entry(
                    "integration.scm",
                    "workspace.MembershipRepository",
                    "DDD9",
                    "workspace::spi WorkspaceMemberships",
                    Set.of("integration.scm.workspace.WorkspaceProvisioningService")),
            new Entry(
                    "integration.scm",
                    "workspace.RepositoryToMonitorRepository",
                    "DDD9",
                    "workspace::spi WorkspaceRepositoryMonitors",
                    Set.of(
                            "integration.scm.workspace.ManualRepositoryMonitorService",
                            "integration.scm.workspace.ScmWorkspaceContentEraser",
                            "integration.scm.workspace.WorkspaceProvisioningService",
                            "integration.scm.workspace.WorkspaceRepositoryMonitorService")),
            new Entry(
                    "integration.scm",
                    "workspace.WorkspaceRepository",
                    "DDD9",
                    "workspace::spi WorkspaceQueries and WorkspaceCommands",
                    Set.of(
                            "integration.scm.workspace.GitLabRepositorySelectionService",
                            "integration.scm.workspace.ManualRepositoryMonitorService",
                            "integration.scm.workspace.ScmWorkspaceContentEraser",
                            "integration.scm.workspace.WorkspaceProvisioningService",
                            "integration.scm.workspace.WorkspaceRepositoryMonitorService",
                            "integration.scm.workspace.WorkspaceTeamLabelService",
                            "integration.scm.workspace.adapter.WorkspaceOrganizationMembershipAdapter",
                            "integration.scm.workspace.adapter.WorkspaceTeamMembershipAdapter",
                            "integration.scm.workspace.settings.WorkspaceTeamSettingsService")),
            new Entry(
                    "integration.scm.github",
                    "workspace.MembershipRepository",
                    "DDD9 (ROLE)",
                    "workspace::spi WorkspaceMemberships.isActiveMember(workspaceId, accountId)",
                    Set.of("integration.scm.github.installation.adoption.GitHubInstallationClaimService")),
            new Entry(
                    "integration.scm.github",
                    "workspace.RepositoryToMonitorRepository",
                    "DDD9",
                    "workspace::spi WorkspaceRepositoryMonitors",
                    Set.of(
                            "integration.scm.github.lifecycle.GitHubLifecycleListener",
                            "integration.scm.github.sync.status.GitHubConnectionSyncStateProvider")),
            new Entry(
                    "integration.scm.github",
                    "workspace.WorkspaceRepository",
                    "DDD9",
                    "workspace::spi WorkspaceQueries and WorkspaceCommands",
                    Set.of(
                            "integration.scm.github.installation.adoption.GitHubInstallationClaimService",
                            "integration.scm.github.installation.adoption.GitHubInstallationRequests",
                            "integration.scm.github.lifecycle.GitHubLifecycleListener",
                            "integration.scm.github.workspace.GitHubInstallationReconciler")),
            new Entry(
                    "integration.scm.gitlab",
                    "workspace.RepositoryToMonitorRepository",
                    "DDD9",
                    "workspace::spi WorkspaceRepositoryMonitors",
                    Set.of(
                            "integration.scm.gitlab.organization.GitLabSelectedMembershipProjection",
                            "integration.scm.gitlab.sync.status.GitLabConnectionSyncStateProvider",
                            "integration.scm.gitlab.workspace.GitLabProjectAdmission",
                            "integration.scm.gitlab.workspace.GitLabRepositoryMonitors",
                            "integration.scm.gitlab.workspace.GitLabRouteAdmission",
                            "integration.scm.gitlab.workspace.GitLabWorkspaceInitializationService",
                            "integration.scm.gitlab.workspace.GitLabWorkspaceLinkService")),
            new Entry(
                    "integration.scm.gitlab",
                    "workspace.WorkspaceRepository",
                    "DDD9",
                    "workspace::spi WorkspaceQueries and WorkspaceCommands",
                    Set.of(
                            "integration.scm.gitlab.connect.GitLabConnectionStrategy",
                            "integration.scm.gitlab.credentials.GitLabTokenRotationScheduler",
                            "integration.scm.gitlab.organization.GitLabMemberMessageHandler",
                            "integration.scm.gitlab.organization.GitLabSelectedMembershipProjection",
                            "integration.scm.gitlab.sync.GitLabDataSyncScheduler",
                            "integration.scm.gitlab.workspace.GitLabProjectAdmission",
                            "integration.scm.gitlab.workspace.GitLabRepositoryMonitors",
                            "integration.scm.gitlab.workspace.GitLabWebhookService",
                            "integration.scm.gitlab.workspace.GitLabWorkspaceDataSyncTrigger",
                            "integration.scm.gitlab.workspace.GitLabWorkspaceInitializationService",
                            "integration.scm.gitlab.workspace.GitLabWorkspaceLinkService")));
}
