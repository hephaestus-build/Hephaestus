package de.tum.cit.aet.hephaestus.integration.scm.gitlab.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.CredentialReaders;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

class GitlabCredentialProviderTest extends BaseUnitTest {

    @Mock
    private ConnectionService connectionService;

    private CredentialBundleConverter converter;
    private GitlabCredentialProvider provider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        converter = new CredentialBundleConverter("0123456789abcdef0123456789abcdef", false);
        provider = new GitlabCredentialProvider(connectionService, CredentialReaders.forTests(converter));
    }

    @Test
    void activeConnection_decryptsPat() {
        long workspaceId = 17L;
        Connection connection = newGitlabConnection(workspaceId);
        connection.setCredentials(new BearerToken("glpat-secret", null), converter);
        connection.setState(IntegrationState.ACTIVE);
        IntegrationRef ref = new IntegrationRef(IntegrationKind.GITLAB, workspaceId, "200");
        when(connectionService.findReferenced(ref)).thenReturn(Optional.of(connection));

        Optional<CredentialBundle> resolved = provider.resolve(ref);

        assertThat(resolved).contains(new BearerToken("glpat-secret", null));
    }

    @Test
    void activeConnection_noBlob_returnsEmpty() {
        long workspaceId = 17L;
        Connection connection = newGitlabConnection(workspaceId);
        connection.setState(IntegrationState.ACTIVE);
        IntegrationRef ref = new IntegrationRef(IntegrationKind.GITLAB, workspaceId, "200");
        when(connectionService.findReferenced(ref)).thenReturn(Optional.of(connection));

        assertThat(provider.resolve(ref)).isEmpty();
    }

    @Test
    void wrongKindRef_returnsEmpty() {
        assertThat(provider.resolve(new IntegrationRef(IntegrationKind.GITHUB, 17L, "100")))
                .isEmpty();
        Mockito.verifyNoInteractions(connectionService);
    }

    private static Connection newGitlabConnection(long workspaceId) {
        Workspace ws = new Workspace();
        ws.setId(workspaceId);
        return new Connection(
                ws,
                IntegrationKind.GITLAB,
                "200",
                new ConnectionConfig.GitLabConfig(
                        "https://gitlab.example.com",
                        200L,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        Set.of()));
    }
}
