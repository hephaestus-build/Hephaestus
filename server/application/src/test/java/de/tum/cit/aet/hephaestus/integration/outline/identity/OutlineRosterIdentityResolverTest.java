package de.tum.cit.aet.hephaestus.integration.outline.identity;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipRoster;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OutlineRosterIdentityResolverTest extends BaseUnitTest {
    @Test
    void creatorsAndEditorsResolveOnceOnTheirOwningServerAndTeamWithoutChangingMembershipControls() {
        var connections = mock(ConnectionService.class);
        var providers = mock(GitProviderRegistry.class);
        var people = mock(PeopleRegistry.class);
        var memberships = mock(MembershipRoster.class);
        var workspace = new Workspace();
        workspace.setId(7L);
        var connection = new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                "team-a",
                new ConnectionConfig.OutlineConfig("https://wiki.example.com", null, null, Set.of()));
        ReflectionTestUtils.setField(connection, "id", 21L);
        when(connections.findInWorkspace(7L, 21L)).thenReturn(Optional.of(connection));
        when(providers.resolveProviderId("OUTLINE", "https://wiki.example.com")).thenReturn(3L);
        when(people.ensureHuman(new PersonIdentity(3L, "creator", "team-a"), "Ada"))
                .thenReturn(55L);
        when(people.ensureHuman(new PersonIdentity(3L, "editor", "team-a"), "Grace"))
                .thenReturn(56L);
        var document = new OutlineDocument();
        document.setWorkspaceId(7L);
        document.setConnectionId(21L);
        document.setCreatedBySubject("creator");
        document.setCreatedByName("Ada");
        document.setUpdatedBySubject("editor");
        document.setUpdatedByName("Grace");
        document.setCollaboratorSubjects(List.of("creator", "editor", "unknown-profile"));
        new OutlineRosterIdentityResolver(connections, providers, people, memberships).recordAuthors(document);
        verify(memberships).ensureMember(7L, 55L);
        verify(memberships).ensureMember(7L, 56L);
        verify(people, never()).ensureHuman(new PersonIdentity(3L, "unknown-profile", "team-a"), "unknown-profile");
    }
}
