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
                    "activity",
                    "integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository",
                    "DDD13 (activity/SCM)",
                    "integration.scm::spi ActivityScmFacts",
                    Set.of("activity.ActivityEventListener")),
            new Entry(
                    "activity",
                    "integration.scm.domain.repository.RepositoryRepository",
                    "DDD13 (activity/SCM)",
                    "integration.scm::spi ActivityScmFacts",
                    Set.of("activity.ActivityEventListener", "activity.ActivityFactDelivery")),
            new Entry(
                    "activity",
                    "integration.scm.domain.team.TeamRepository",
                    "DDD13 (activity/SCM)",
                    "integration.scm::spi ActivityScmFacts",
                    Set.of("activity.overview.ActivityScopeResolver")),
            new Entry(
                    "activity",
                    "integration.scm.domain.user.UserRepository",
                    "DDD13 (activity/SCM)",
                    "integration.scm::spi ActivityScmFacts",
                    Set.of(
                            "activity.ActivityEventListener",
                            "activity.ActivityFactDelivery",
                            "activity.ActivityPersonWriteAdmission",
                            "activity.overview.ActivityAutomationService",
                            "activity.overview.ActivityWorkAssembler")),
            new Entry(
                    "activity",
                    "integration.scm.workspace.ScmMemberRepository",
                    "DDD13 (activity/SCM)",
                    "integration.scm::spi ActivityScmFacts",
                    Set.of("activity.overview.ActivityScopeResolver")),
            new Entry(
                    "agent",
                    "mentor.MentorConversationRepository",
                    "DDD10",
                    "mentor::spi MentorTurnAccess and MentorConversationAccess",
                    Set.of("agent.mentor.host.HephTurnConfiguration", "agent.mentor.host.HephTurns")),
            new Entry(
                    "agent",
                    "mentor.MentorEntryRepository",
                    "DDD10",
                    "mentor::spi MentorConversationAccess",
                    Set.of(
                            "agent.context.providers.mentor.WeeklyDigestContentSource",
                            "agent.mentor.weeklydigest.DigestAdmission",
                            "agent.mentor.weeklydigest.DigestDelivery")),
            new Entry(
                    "agent",
                    "mentor.MentorTurnRepository",
                    "DDD10",
                    "mentor::spi MentorTurnAccess and MentorConversationAccess",
                    Set.of(
                            "agent.mentor.host.HephTurnConfiguration",
                            "agent.mentor.host.HephTurns",
                            "agent.mentor.host.TurnSandboxRunKind",
                            "agent.mentor.sandbox.TurnContextLoads",
                            "agent.mentor.turn.MentorTurnQueueSampler",
                            "agent.mentor.turn.MentorTurnStarter",
                            "agent.mentor.weeklydigest.DigestAdmission",
                            "agent.mentor.weeklydigest.DigestDelivery",
                            "agent.mentor.weeklydigest.WeeklyDigestNotes",
                            "agent.proxy.ProxyCallLedger",
                            "agent.proxy.ProxyRequestPolicy",
                            "agent.proxy.ProxyRoutingService")),
            new Entry(
                    "agent",
                    "practices.result.ProblemRepository",
                    "DDD15 (practices)",
                    "practices::spi ResultEvidenceQuery",
                    Set.of("agent.adapter.EvidenceDeliveryAuthorization")),
            new Entry(
                    "integration",
                    "integration.core.connection.ConnectionRepository",
                    "DDD11 (integration.core)",
                    "integration.core::spi ConnectionQueries and ConnectionService commands",
                    Set.of("integration.identity.connect.RegistrationToGitProviderResolver")),
            new Entry(
                    "integration",
                    "integration.core.connection.IdentityProviderRepository",
                    "DDD11 (integration.core)",
                    "integration.core::spi IdentityProviderLookup; SourceProviderNamespaces registration",
                    Set.of("integration.identity.connect.RegistrationToGitProviderResolver")),
            new Entry(
                    "integration",
                    "integration.scm.domain.user.UserRepository",
                    "DDD14 (identity/SCM)",
                    "integration.scm::spi ScmIdentityLookup",
                    Set.of("integration.identity.connect.RegistrationToGitProviderResolver")),
            new Entry(
                    "integration.core",
                    "workspace.WorkspaceRepository",
                    "DDD9 (ROLE)",
                    "workspace::spi WorkspaceSummaryQuery.findStateForUpdate; workspace-owned connection initiation",
                    Set.of("integration.core.oauth.state.OAuthStateNonceStore")),
            new Entry(
                    "integration.scm",
                    "integration.core.connection.ConnectionAuditRepository",
                    "DDD11 (integration.core)",
                    "integration.core::spi ConnectionService audit commands",
                    Set.of(
                            "integration.scm.workspace.GitLabRepositorySelectionService",
                            "integration.scm.workspace.GitLabStoppedProjectService")),
            new Entry(
                    "integration.scm",
                    "integration.core.connection.ConnectionRepository",
                    "DDD11 (integration.core)",
                    "integration.core::spi ConnectionQueries and ConnectionService commands",
                    Set.of(
                            "integration.scm.workspace.GitLabRepositorySelectionService",
                            "integration.scm.workspace.GitLabStoppedProjectService",
                            "integration.scm.workspace.ManualRepositoryMonitorService",
                            "integration.scm.workspace.WorkspaceRepositoryMonitorService",
                            "integration.scm.workspace.adapter.WorkspaceOrganizationMembershipAdapter")),
            new Entry(
                    "integration.scm",
                    "integration.core.connection.IdentityProviderRepository",
                    "DDD11 (integration.core)",
                    "integration.core::spi IdentityProviderLookup; SourceProviderNamespaces registration",
                    Set.of(
                            "integration.scm.identity.AuthenticatedGitProviderUserService",
                            "integration.scm.workspace.ManualRepositoryMonitorService",
                            "integration.scm.workspace.WorkspaceProvisioningService",
                            "integration.scm.workspace.WorkspaceRepositoryMonitorService")),
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
                    "integration.core.connection.ConnectionRepository",
                    "DDD11 (integration.core)",
                    "integration.core::spi ConnectionQueries and ConnectionService commands",
                    Set.of(
                            "integration.scm.github.repository.GitHubRepositoryIdentityLookup",
                            "integration.scm.github.sync.GitHubDataSyncScheduler",
                            "integration.scm.github.sync.status.GitHubConnectionSyncStateProvider",
                            "integration.scm.github.workspace.GitHubWorkspaceDataSyncTrigger")),
            new Entry(
                    "integration.scm.github",
                    "integration.core.connection.IdentityProviderRepository",
                    "DDD11 (integration.core)",
                    "integration.core::spi IdentityProviderLookup; SourceProviderNamespaces registration",
                    Set.of(
                            "integration.scm.github.check.GitHubHeadCheckRunsReread",
                            "integration.scm.github.commit.GitHubPushMessageHandler",
                            "integration.scm.github.common.ProcessingContextFactory",
                            "integration.scm.github.installation.GitHubInstallationMessageHandler",
                            "integration.scm.github.installation.GitHubInstallationTargetMessageHandler",
                            "integration.scm.github.issuedependency.GitHubIssueDependencySyncService",
                            "integration.scm.github.label.GitHubLabelProcessor",
                            "integration.scm.github.lifecycle.GitHubLifecycleListener",
                            "integration.scm.github.organization.GitHubOrganizationMessageHandler",
                            "integration.scm.github.organization.GitHubOrganizationProcessor",
                            "integration.scm.github.organization.GitHubOrganizationSyncService",
                            "integration.scm.github.repository.GitHubRepositoryMessageHandler",
                            "integration.scm.github.subissue.GitHubSubIssueSyncService",
                            "integration.scm.github.sync.GitHubDataSyncService",
                            "integration.scm.github.team.GitHubMembershipMessageHandler",
                            "integration.scm.github.team.GitHubTeamMessageHandler")),
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
                    "integration.core.connection.ConnectionRepository",
                    "DDD11 (integration.core)",
                    "integration.core::spi ConnectionQueries and ConnectionService commands",
                    Set.of(
                            "integration.scm.gitlab.connect.GitLabConnectionStrategy",
                            "integration.scm.gitlab.credentials.GitLabTokenLifecycleService",
                            "integration.scm.gitlab.organization.GitLabSelectedMembershipProjection",
                            "integration.scm.gitlab.repository.GitLabRepositoryIdentityLookup",
                            "integration.scm.gitlab.sync.GitLabDataSyncScheduler",
                            "integration.scm.gitlab.workspace.GitLabProjectAdmission",
                            "integration.scm.gitlab.workspace.GitLabRepositoryMonitors",
                            "integration.scm.gitlab.workspace.GitLabRouteAdmission",
                            "integration.scm.gitlab.workspace.GitLabSuspendedWebhookCleanup",
                            "integration.scm.gitlab.workspace.GitLabWorkspaceDataSyncTrigger")),
            new Entry(
                    "integration.scm.gitlab",
                    "integration.core.connection.IdentityProviderRepository",
                    "DDD11 (integration.core)",
                    "integration.core::spi IdentityProviderLookup; SourceProviderNamespaces registration",
                    Set.of(
                            "integration.scm.gitlab.organization.GitLabGroupProcessor",
                            "integration.scm.gitlab.organization.GitLabGroupSyncService",
                            "integration.scm.gitlab.repository.GitLabProjectSyncService",
                            "integration.scm.gitlab.team.GitLabSubgroupMessageHandler",
                            "integration.scm.gitlab.workspace.GitLabRouteAdmission",
                            "integration.scm.gitlab.workspace.GitLabWorkspaceLinkService")),
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
                            "integration.scm.gitlab.workspace.GitLabWorkspaceLinkService")),
            new Entry(
                    "workspace",
                    "integration.core.connection.ConnectionRepository",
                    "DDD11 (integration.core)",
                    "integration.core::spi ConnectionQueries and ConnectionService commands",
                    Set.of(
                            "workspace.adapter.WorkspaceEventSubscriptions",
                            "workspace.onboarding.WorkspaceOnboardingLinks")),
            new Entry(
                    "workspace",
                    "integration.core.connection.IdentityProviderRepository",
                    "DDD11 (integration.core)",
                    "integration.core::spi IdentityProviderLookup; SourceProviderNamespaces registration",
                    Set.of("workspace.WorkspaceActorSelector")));
}
