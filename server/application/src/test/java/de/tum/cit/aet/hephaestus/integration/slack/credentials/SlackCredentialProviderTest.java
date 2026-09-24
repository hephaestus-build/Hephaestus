package de.tum.cit.aet.hephaestus.integration.slack.credentials;

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
import org.mockito.MockitoAnnotations;

class SlackCredentialProviderTest extends BaseUnitTest {

    @Mock
    private ConnectionService connectionService;

    private CredentialBundleConverter converter;
    private SlackCredentialProvider provider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        converter = new CredentialBundleConverter("0123456789abcdef0123456789abcdef", false);
        provider = new SlackCredentialProvider(connectionService, CredentialReaders.forTests(converter));
    }

    @Test
    void activeConnection_decryptsBotToken() {
        long workspaceId = 17L;
        Workspace ws = new Workspace();
        ws.setId(workspaceId);
        Connection connection = new Connection(
                ws,
                IntegrationKind.SLACK,
                "T12345",
                new ConnectionConfig.SlackConfig("T12345", "Acme", null, null, null, Set.of()));
        connection.setCredentials(new BearerToken("xoxb-secret-bot-token", null), converter);
        connection.setState(IntegrationState.ACTIVE);
        IntegrationRef ref = new IntegrationRef(IntegrationKind.SLACK, workspaceId, "T12345");
        when(connectionService.findReferenced(ref)).thenReturn(Optional.of(connection));

        Optional<CredentialBundle> resolved = provider.resolve(ref);

        assertThat(resolved).contains(new BearerToken("xoxb-secret-bot-token", null));
    }
}
