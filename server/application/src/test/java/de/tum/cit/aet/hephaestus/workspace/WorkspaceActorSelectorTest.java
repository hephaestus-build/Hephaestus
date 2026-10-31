package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkspaceActorSelectorTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;
    private static final List<Long> LINK_ORDER = List.of(11L, 22L);

    private final ConnectionService connections = mock(ConnectionService.class);
    private final GitProviderRegistry providers = mock(GitProviderRegistry.class);
    private final WorkspaceActorSelector selector =
            new WorkspaceActorSelector(connections, mock(IdentityProviderRepository.class), providers);

    @Test
    void shouldSelectTheFirstLinkedActorWhenTheWorkspaceHasNoActiveScmConnection() {
        when(connections.findActiveProviderKind(WORKSPACE_ID)).thenReturn(Optional.empty());

        assertThat(selector.select(WORKSPACE_ID, LINK_ORDER)).contains(11L);
    }

    @Test
    void shouldSelectTheFirstLinkedActorWhenNoMemberActorIsOnTheConnectedInstance() {
        connectGitLab();

        assertThat(selector.select(WORKSPACE_ID, LINK_ORDER)).contains(11L);
    }

    @Test
    void shouldSelectTheActorOnTheConnectedInstanceWhenItWasLinkedLater() {
        connectGitLab();
        when(providers.actor(22L))
                .thenReturn(Optional.of(new GitProviderRegistry.ActorProfile(
                        22L, 2L, "GITLAB", "https://gitlab.lrz.de", "123", "developer", "Developer", true)));

        assertThat(selector.select(WORKSPACE_ID, LINK_ORDER)).contains(22L);
    }

    private void connectGitLab() {
        when(connections.findActiveProviderKind(WORKSPACE_ID)).thenReturn(Optional.of(IntegrationKind.GITLAB));
        when(connections.findActiveGitLabConfig(WORKSPACE_ID))
                .thenReturn(Optional.of(
                        new ConnectionConfig.GitLabConfig("https://gitlab.lrz.de", null, null, Set.of(), null)));
    }
}
