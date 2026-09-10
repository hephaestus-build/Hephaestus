package de.tum.cit.aet.hephaestus.workspace.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class DirectoryMembershipAdapterTest {
    @Test
    void shouldReplaceRequestProvenanceWhenOwnerAdoptsAccessIntoDirectoryManagement() {
        var adapter = new DirectoryMembershipAdapter(
                mock(WorkspaceAccountMembershipRepository.class),
                mock(AccountIdentityQuery.class),
                mock(ConfigAuditPort.class),
                mock(DirectoryPolicyRepository.class),
                java.util.List.of());
        var workspace = new Workspace();
        workspace.setId(1L);
        var membership = new WorkspaceAccountMembership();
        membership.setWorkspace(workspace);
        membership.setAccountId(2L);
        membership.setRole(WorkspaceRole.MEMBER);
        membership.setSource(WorkspaceAccountMembership.Source.REQUEST);
        membership.setExpiresAt(Instant.parse("2026-01-01T00:00:00Z"));
        membership.setAccessRequestId(3L);
        membership.setSuspended(true);

        adapter.adopt(membership, "directory-subject");

        assertThat(membership.getSource()).isEqualTo(WorkspaceAccountMembership.Source.DIRECTORY);
        assertThat(membership.getDirectorySubject()).isEqualTo("directory-subject");
        assertThat(membership.getExpiresAt()).isNull();
        assertThat(membership.getAccessRequestId()).isNull();
        assertThat(membership.isSuspended()).isTrue();
    }
}
