package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkspaceActorSelectorTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;
    private static final List<Long> LINK_ORDER = List.of(11L, 22L);

    private final ConnectionService connections = mock(ConnectionService.class);
    private final ScmTokenSource gitlab = mock(ScmTokenSource.class);
    private final WorkspaceMembershipRepository memberships = mock(WorkspaceMembershipRepository.class);
    private final WorkspaceActorSelector selector =
            new WorkspaceActorSelector(connections, List.of(gitlab), memberships);

    @Test
    void shouldSelectTheFirstLinkedActorWhenTheWorkspaceHasNoActiveScmConnection() {
        when(connections.findActiveProviderKind(WORKSPACE_ID)).thenReturn(Optional.empty());

        assertThat(selector.select(WORKSPACE_ID, LINK_ORDER)).contains(11L);
    }

    @Test
    void shouldSelectTheFirstLinkedActorWhenNoMemberActorIsOnTheConnectedInstance() {
        connectGitLab();
        when(memberships.findMemberUserIdsByWorkspaceIdAndProvider(
                        WORKSPACE_ID, LINK_ORDER, IdentityProviderType.GITLAB, "https://gitlab.lrz.de"))
                .thenReturn(Set.of());

        assertThat(selector.select(WORKSPACE_ID, LINK_ORDER)).contains(11L);
    }

    @Test
    void shouldSelectTheActorOnTheConnectedInstanceWhenItWasLinkedLater() {
        connectGitLab();
        when(memberships.findMemberUserIdsByWorkspaceIdAndProvider(
                        WORKSPACE_ID, LINK_ORDER, IdentityProviderType.GITLAB, "https://gitlab.lrz.de"))
                .thenReturn(Set.of(22L));

        assertThat(selector.select(WORKSPACE_ID, LINK_ORDER)).contains(22L);
    }

    private void connectGitLab() {
        when(connections.findActiveProviderKind(WORKSPACE_ID)).thenReturn(Optional.of(IntegrationKind.GITLAB));
        when(gitlab.kind()).thenReturn(IntegrationKind.GITLAB);
        when(gitlab.serverUrl(WORKSPACE_ID)).thenReturn(Optional.of("https://gitlab.lrz.de"));
    }
}
