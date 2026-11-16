package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationService;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption.GitHubInstallationAccountKind;
import de.tum.cit.aet.hephaestus.workspace.exception.InvalidWorkspaceSlugException;
import de.tum.cit.aet.hephaestus.workspace.exception.WorkspaceLifecycleViolationException;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

class WorkspaceServiceIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WorkspaceLifecycleService workspaceLifecycleService;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private OrganizationService organizationService;

    @ParameterizedTest
    @EnumSource(GitHubInstallationAccountKind.class)
    void shouldKeepInstallationsSeparateWhenTheProviderAccountIdentityIsUnproven(GitHubInstallationAccountKind kind) {
        User formerAccount = persistUser("reused-personal-account");
        Workspace existing = createWorkspace(
                "reused-personal-account",
                "Existing",
                "reused-personal-account",
                kind == GitHubInstallationAccountKind.USER ? AccountType.USER : AccountType.ORG,
                formerAccount);
        if (kind == GitHubInstallationAccountKind.ORGANIZATION) {
            existing.setOrganizationId(organizationService
                    .upsertIdentity(
                            998880L,
                            "former-organization-name",
                            Objects.requireNonNull(ensureGitHubProvider().getId()))
                    .getId());
            workspaceRepository.saveAndFlush(existing);
        }

        Workspace incoming = installationLifecycle.createOrUpdateFromInstallation(
                998881L, 998882L, "reused-personal-account", kind, null, RepositorySelection.ALL);

        assertNotNull(incoming);
        assertThat(incoming.getId()).isNotEqualTo(existing.getId());
        assertThat(incoming.getWorkspaceSlug()).isNotEqualTo(existing.getWorkspaceSlug());
        assertThat(connectionService.findActiveGitHubAppConfig(existing.getId()))
                .isEmpty();
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(
                        incoming.getId(), accountId(formerAccount)))
                .isEmpty();

        Workspace renamed = installationLifecycle.createOrUpdateFromInstallation(
                998881L, 998882L, "renamed-installed-account", kind, null, RepositorySelection.ALL);
        assertNotNull(renamed);
        assertThat(renamed.getId()).isEqualTo(incoming.getId());
    }

    @Test
    void shouldGrantInstallationOwnershipToTheProviderSubjectWhenAStoredLoginWasReassigned() {
        User formerLoginOwner = persistUser("reassigned-installation");
        long installedAccountId = 998877L;

        Workspace workspace = installationLifecycle.createOrUpdateFromInstallation(
                998878L,
                installedAccountId,
                "reassigned-installation",
                GitHubInstallationAccountKind.USER,
                null,
                RepositorySelection.ALL);

        assertNotNull(workspace);
        var installedActor = userRepository
                .findByNativeIdAndProviderId(
                        installedAccountId,
                        Objects.requireNonNull(ensureGitHubProvider().getId()))
                .orElseThrow();
        assertThat(installedActor.getId()).isNotEqualTo(formerLoginOwner.getId());
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(
                        workspace.getId(), accountId(installedActor)))
                .hasValueSatisfying(member -> assertThat(member.getRole()).isEqualTo(Membership.WorkspaceRole.OWNER));
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(
                        workspace.getId(), accountId(formerLoginOwner)))
                .isEmpty();
    }

    @Test
    void shouldRejectInvalidSlugWhenCallerBypassesDtoValidation() {
        assertThatThrownBy(
                        () -> workspaceService.createWorkspace("Acme Org", "Acme Org", "acme", AccountType.ORG, null))
                .isInstanceOf(InvalidWorkspaceSlugException.class);
    }

    @Test
    void createWorkspaceAssignsOwnerMembership() {
        User owner = persistUser("OwnerLogin");

        Workspace workspace = createWorkspace("acme-org", "Acme Org", "acme", AccountType.ORG, owner);

        assertThat(workspace.getWorkspaceSlug()).isEqualTo("acme-org");
        assertThat(workspace.getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);
        assertThat(workspace.getAccountType()).isEqualTo(AccountType.ORG);

        var membership = workspaceMembershipRepository
                .findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId(owner))
                .orElseThrow();
        assertThat(membership.getRole()).isEqualTo(Membership.WorkspaceRole.OWNER);
    }

    @Test
    void workspaceLifecycleTransitions() {
        User owner = persistUser("lifecycle-owner");
        Workspace workspace = createWorkspace("lifecycle", "Lifecycle", "lifecycle", AccountType.ORG, owner);

        workspaceLifecycleService.suspendWorkspace(workspace.getWorkspaceSlug());
        Workspace suspended = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(suspended.getStatus()).isEqualTo(Workspace.WorkspaceStatus.SUSPENDED);

        workspaceLifecycleService.resumeWorkspace(workspace.getWorkspaceSlug());
        Workspace resumed = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(resumed.getStatus()).isEqualTo(Workspace.WorkspaceStatus.ACTIVE);

        workspaceLifecycleService.updateStatus(workspace.getWorkspaceSlug(), Workspace.WorkspaceStatus.SUSPENDED);
        Workspace patched = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(patched.getStatus()).isEqualTo(Workspace.WorkspaceStatus.SUSPENDED);

        workspaceLifecycleService.purgeWorkspace(workspace.getWorkspaceSlug());
        Workspace purged = workspaceRepository.findById(workspace.getId()).orElseThrow();
        assertThat(purged.getStatus()).isEqualTo(Workspace.WorkspaceStatus.PURGED);

        assertThatThrownBy(() -> workspaceLifecycleService.resumeWorkspace(workspace.getWorkspaceSlug()))
                .isInstanceOf(WorkspaceLifecycleViolationException.class)
                .hasMessageContaining("purged");
    }

    @Test
    void patWorkspaceWithoutTokenIsPromoted() {
        User owner = persistUser("ls1intum-owner");
        Workspace workspace = createWorkspace("ls1intum", "ls1intum", "ls1intum", AccountType.ORG, owner);
        workspace.setOrganizationId(organizationService
                .upsertIdentity(
                        95711018L,
                        "ls1intum",
                        Objects.requireNonNull(ensureGitHubProvider().getId()))
                .getId());
        workspaceRepository.saveAndFlush(workspace);

        // No PAT Connection on the workspace — this mirrors the legacy
        // "PAT_ORG without token" shape the migration replaces. The lifecycle listener
        // should promote it cleanly to a GitHub App Connection.

        Workspace promoted = installationLifecycle.createOrUpdateFromInstallation(
                95711017L,
                95711018L,
                "ls1intum",
                GitHubInstallationAccountKind.ORGANIZATION,
                null,
                RepositorySelection.ALL);

        // provider mode + installation id live on the Connection
        // registry now, not on Workspace.
        assertNotNull(promoted);
        assertThat(promoted.getId()).isEqualTo(workspace.getId());
        assertThat(connectionService.findActiveProviderKind(promoted.getId())).hasValue(IntegrationKind.GITHUB);
        assertThat(connectionService.findActiveGitHubAppConfig(promoted.getId()))
                .hasValueSatisfying(cfg -> assertThat(cfg.installationId()).isEqualTo(95711017L));
        assertThat(connectionService.findActiveBearerToken(promoted.getId(), IntegrationKind.GITHUB))
                .as("App-mode Connections do not store a bearer credential blob")
                .isEmpty();
    }
}
