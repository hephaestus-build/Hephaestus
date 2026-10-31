package de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryIdentityLookup.RepositoryIdentity;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabProjectResponse;
import de.tum.cit.aet.hephaestus.integration.scm.workspace.RepositoryOriginNotSupportedException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitLabRepositoryIdentityLookupTest extends BaseUnitTest {
    private static final long WORKSPACE_ID = 42L;
    private static final String TOKEN = "synthetic-token-stored-with-the-connection";
    private static final String CONFIGURED = "https://gitlab.example.test/";
    private static final String SERVER = "https://gitlab.example.test";
    private static final String PATH = "shared-org/private-project";

    private final GitLabProjectSyncService projects = mock(GitLabProjectSyncService.class);
    private final ConnectionRepository connections = mock(ConnectionRepository.class);
    private final CredentialReader credentials = mock(CredentialReader.class);
    private final GitLabTokenService tokens = mock(GitLabTokenService.class);
    private final Connection connection = mock(Connection.class);
    private GitLabRepositoryIdentityLookup lookup;

    @BeforeEach
    void setUp() {
        lenient()
                .when(connection.getConfig())
                .thenReturn(new ConnectionConfig.GitLabConfig(CONFIGURED, null, null, Set.of(), null));
        lenient()
                .when(connections.findActive(WORKSPACE_ID, IntegrationKind.GITLAB))
                .thenReturn(Optional.of(connection));
        lenient().when(tokens.serverUrlOf(CONFIGURED)).thenReturn(SERVER);
        lookup = new GitLabRepositoryIdentityLookup(projects, connections, credentials, tokens);
    }

    @Test
    void shouldSendTheStoredTokenOnlyToTheServerTheSameConnectionNames() {
        when(credentials.credentialsOf(connection)).thenReturn(Optional.of(new BearerToken(TOKEN, null)));
        GitLabProjectResponse project = mock(GitLabProjectResponse.class);
        when(project.id()).thenReturn("gid://gitlab/Project/7101");
        when(project.fullPath()).thenReturn(PATH);
        when(projects.fetchProject(WORKSPACE_ID, TOKEN, SERVER, PATH)).thenReturn(Optional.of(project));

        assertThat(lookup.resolve(WORKSPACE_ID, PATH)).contains(new RepositoryIdentity(7101L, PATH));
        verify(connections, times(1)).findActive(WORKSPACE_ID, IntegrationKind.GITLAB);
        verify(credentials, times(1)).credentialsOf(connection);
        // The scope's token and server are never resolved again on their own.
        verify(projects, never()).fetchProject(anyLong(), anyString());
    }

    @Test
    void shouldRefuseBeforeReadingTheTokenWhenAConnectionWithoutAServerWouldSendItElsewhere() {
        // Without a URL the connection's mirrors are recorded under gitlab.com, but its token would go to this default.
        when(connection.getConfig()).thenReturn(withoutServer());
        when(tokens.serverUrlOf(null)).thenReturn("https://gitlab.custom-default.example.test");

        assertThatThrownBy(() -> lookup.resolve(WORKSPACE_ID, PATH))
                .isInstanceOf(RepositoryOriginNotSupportedException.class);
        verify(credentials, never()).credentialsOf(any());
        verifyNoInteractions(projects);
    }

    @Test
    void shouldAskGitLabDotComForAConnectionWithoutAServerWhenThatIsTheDefault() {
        when(connection.getConfig()).thenReturn(withoutServer());
        when(tokens.serverUrlOf(null)).thenReturn("https://gitlab.com");
        when(credentials.credentialsOf(connection)).thenReturn(Optional.of(new BearerToken(TOKEN, null)));
        GitLabProjectResponse project = mock(GitLabProjectResponse.class);
        when(project.id()).thenReturn("gid://gitlab/Project/7101");
        when(project.fullPath()).thenReturn(PATH);
        when(projects.fetchProject(WORKSPACE_ID, TOKEN, "https://gitlab.com", PATH))
                .thenReturn(Optional.of(project));

        assertThat(lookup.resolve(WORKSPACE_ID, PATH)).contains(new RepositoryIdentity(7101L, PATH));
    }

    @Test
    void shouldAskNothingWithoutAStoredToken() {
        when(credentials.credentialsOf(connection)).thenReturn(Optional.empty());

        assertThat(lookup.resolve(WORKSPACE_ID, PATH)).isEmpty();
        verify(projects, never()).fetchProject(anyLong(), anyString(), anyString(), anyString());
        verify(projects, never()).fetchProject(anyLong(), anyString());
    }

    @Test
    void shouldConfirmNothingWhenGitLabNamesNoPath() {
        when(credentials.credentialsOf(connection)).thenReturn(Optional.of(new BearerToken(TOKEN, null)));
        GitLabProjectResponse project = mock(GitLabProjectResponse.class);
        lenient().when(project.id()).thenReturn("gid://gitlab/Project/7101");
        when(project.fullPath()).thenReturn(null);
        when(projects.fetchProject(WORKSPACE_ID, TOKEN, SERVER, PATH)).thenReturn(Optional.of(project));

        assertThat(lookup.resolve(WORKSPACE_ID, PATH)).isEmpty();
    }

    private static ConnectionConfig.GitLabConfig withoutServer() {
        return new ConnectionConfig.GitLabConfig(null, null, null, Set.of(), null);
    }
}
