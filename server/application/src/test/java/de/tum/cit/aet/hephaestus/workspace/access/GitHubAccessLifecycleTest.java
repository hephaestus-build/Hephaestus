package de.tum.cit.aet.hephaestus.workspace.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessFailure;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeBlockedException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class GitHubAccessLifecycleTest {
    private final GitHubAccessTargetRepository targets = mock(GitHubAccessTargetRepository.class);
    private final GitHubAccessMembershipRepository memberships = mock(GitHubAccessMembershipRepository.class);
    private final GitHubAccessActionRepository actions = mock(GitHubAccessActionRepository.class);
    private final GitProviderRegistry providers = mock(GitProviderRegistry.class);
    private final ConfigAuditPort audit = mock(ConfigAuditPort.class);
    private final GitHubAccessLifecycle lifecycle =
            new GitHubAccessLifecycle(targets, memberships, actions, providers, audit);
    private GitHubAccessTarget target = new GitHubAccessTarget();
    private GitHubAccessMembership member = new GitHubAccessMembership();

    @BeforeEach
    void setUp() {
        var workspace = new Workspace();
        workspace.setId(10L);
        target.setId(20L);
        target.setWorkspace(workspace);
        target.setDirectoryProviderId(30L);
        target.setStatus(GitHubAccessTarget.Status.ACTIVE);
        target.setAuthorityHeld(true);
        member.setId(40L);
        member.setWorkspace(workspace);
        member.setTarget(target);
        member.setAccountId(50L);
        member.setGithubUserId(60L);
        member.setDirectorySubject("person-70");
        member.setGithubIdentityLinkId(80L);
        member.setDirectoryIdentityLinkId(90L);
        member.setGithubLogin("developer");
        member.setExternalState(GitHubAccessClient.State.ACTIVE);
        when(memberships.findByAccountId(50L)).thenReturn(List.of(member));
        when(targets.findByWorkspace_IdOrderById(10L)).thenReturn(List.of(target));
        when(memberships.findByWorkspace_IdAndTarget_IdOrderById(10L, 20L)).thenReturn(List.of(member));
        when(providers.findProviderId("GITHUB", "https://github.com")).thenReturn(Optional.of(100L));
    }

    @Test
    void shouldRetainConfirmedAccessWhenManagedIdentityIsUnlinked() {
        member.setManaged(true);
        lifecycle.beforeUnlink(50L, 100L, "60");
        assertThat(member.isRevocationRequested()).isTrue();
        assertThat(member.isManaged()).isTrue();
        assertThat(member.getExternalState()).isEqualTo(GitHubAccessClient.State.ACTIVE);
        assertThat(member.getBlocker()).contains("until GitHub confirms");
        verify(audit).record(any());
    }

    @Test
    void shouldIgnoreOtherProviderOrSubjectWhenIdentityIsUnlinked() {
        member.setManaged(true);
        lifecycle.beforeUnlink(50L, 101L, "60");
        lifecycle.beforeUnlink(50L, 100L, "61");
        lifecycle.beforeUnlink(50L, 31L, "person-70");
        assertThat(member.isRevocationRequested()).isFalse();
        verifyNoInteractions(audit);
    }

    @Test
    void shouldLatchDirectoryDepartureWhenExactSourceIdentityIsUnlinked() {
        member.setManaged(true);
        lifecycle.beforeUnlink(50L, 30L, "person-70");
        assertThat(member.isRevocationRequested()).isTrue();
    }

    @Test
    void shouldNotClaimUnmanagedAccessWhenAccountIsDeleted() {
        lifecycle.beforeDeletion(50L);
        assertThat(member.isManaged()).isFalse();
        assertThat(member.isRevocationRequested()).isFalse();
        assertThat(member.getExternalState()).isEqualTo(GitHubAccessClient.State.ACTIVE);
    }

    @Test
    void shouldRetainUnconfirmedGrantWhenAccountIsErased() {
        var action = new GitHubAccessAction();
        action.setMembership(member);
        action.setType(GitHubAccessAction.Type.GRANT);
        when(actions.findByWorkspace_IdAndTarget_IdAndStatusInOrderById(
                        10L, 20L, Set.of(GitHubAccessAction.Status.PENDING, GitHubAccessAction.Status.MANUAL_RECOVERY)))
                .thenReturn(List.of(action));
        lifecycle.eraseAccount(50L);
        assertThat(member.isRevocationRequested()).isTrue();
        assertThat(member.getGithubUserId()).isEqualTo(60L);
        assertThat(member.getAccountId()).isNull();
        assertThat(member.getDirectorySubject()).isNull();
        assertThat(member.getGithubLogin()).isNull();
        assertThat(member.getGithubIdentityLinkId()).isNull();
        assertThat(member.getDirectoryIdentityLinkId()).isNull();
        verify(memberships, never()).delete(any());
        verify(actions, never()).deleteAllByWorkspace_IdAndMembership_Id(anyLong(), anyLong());
    }

    @Test
    void shouldEraseUnmanagedAttributionWhenNoObligationRemains() {
        lifecycle.eraseAccount(50L);
        verify(actions).deleteAllByWorkspace_IdAndMembership_Id(10L, 40L);
        verify(memberships).delete(member);
    }

    @Test
    void shouldRequireFreshOwnerApprovalWhenAuthorizerUnlinks() {
        target.setAuthorization(
                new GitHubAccessEvidence.Authorization(1, 50, 80, 60, 110, Map.of("members", "write"), Instant.EPOCH));
        target.setHandoffHash("one-use-capability");
        target.setHandoffExpiresAt(Instant.MAX);
        when(targets.authorizedByAccount(50L)).thenReturn(List.of(target));
        lifecycle.beforeUnlink(50L, 100L, "60");
        assertThat(target.getAuthorization()).isNull();
        assertThat(target.getHandoffHash()).isNull();
        assertThat(target.getHandoffExpiresAt()).isNull();
        assertThat(target.getConfigurationVersion()).isEqualTo(2);
        assertThat(target.isAuthorityHeld()).isTrue();
        assertThat(target.getFailureCode()).isEqualTo(GitHubAccessFailure.Reason.AUTHORITY_LOST);
    }

    @Test
    void shouldBlockPurgeWhenPausedTargetStillHoldsAuthority() {
        target.setPaused(true);
        assertThatThrownBy(() -> lifecycle.deleteWorkspaceData(10L)).isInstanceOf(WorkspacePurgeBlockedException.class);
        verify(actions, never()).deleteAllByWorkspace_Id(anyLong());
        verify(targets, never()).deleteAllByWorkspace_Id(anyLong());
    }

    @Test
    void shouldBlockPurgeWhenEndedTargetStillHasUnconfirmedRemoval() {
        target.setStatus(GitHubAccessTarget.Status.ENDED);
        target.setAuthorityHeld(false);
        member.setRevocationRequested(true);
        assertThatThrownBy(() -> lifecycle.verifyQuiescent(10L)).isInstanceOf(WorkspacePurgeBlockedException.class);
    }

    @Test
    void shouldDeleteOnlyRequestedWorkspaceWhenAllObligationsAreResolved() {
        target.setStatus(GitHubAccessTarget.Status.ENDED);
        target.setAuthorityHeld(false);
        lifecycle.deleteWorkspaceData(10L);
        var order = inOrder(actions, memberships, targets);
        order.verify(actions).deleteAllByWorkspace_Id(10L);
        order.verify(memberships).deleteAllByWorkspace_Id(10L);
        order.verify(targets).deleteAllByWorkspace_Id(10L);
        assertThat(lifecycle.getOrder()).isLessThan(-300);
    }
}
