package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.CredentialReaders;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspacePurgeBlockedException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class ConnectionPurgeContributorTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 42L;

    @Mock
    private ConnectionRepository connectionRepository;

    @Mock
    private ConnectionAuditRepository auditRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SyncJobService syncJobService;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private ConnectionStrategy connectionStrategy;

    private CredentialBundleConverter credentialConverter;
    private ConnectionService connectionService;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        credentialConverter = new CredentialBundleConverter("a".repeat(32), false);
        connectionService = new ConnectionService(
                connectionRepository,
                auditRepository,
                credentialConverter,
                eventPublisher,
                syncJobService,
                transactionManager,
                CredentialReaders.forTests(credentialConverter));
        Mockito.lenient().when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));
        Mockito.lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        Mockito.lenient().when(connectionStrategy.kind()).thenReturn(IntegrationKind.GITHUB);
        workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
    }

    @Test
    void purge_revokesSuspendedProviderBeforeScrubbingCredentialsWithoutConsultingTheSyncFence() throws Exception {
        Connection connection = activeConnectionWithCredentials();
        connection.setState(IntegrationState.SUSPENDED);
        when(connectionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(connection));
        doAnswer(invocation -> {
                    assertThat(connection.getCredentialsEncrypted()).isNotNull();
                    return null;
                })
                .when(connectionStrategy)
                .revokeProvider(erasureRef(connection));

        contributor(List.of(connectionStrategy)).deleteWorkspaceData(WORKSPACE_ID);

        verify(connectionStrategy).revokeProvider(erasureRef(connection));
        assertThat(connection.getState()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(connection.getCredentialsEncrypted()).isNull();
        verify(syncJobService, never()).requestCancelForTeardown(anyLong());
    }

    @Test
    void purge_preservesCredentialsWhenProviderRevokeFails() throws Exception {
        Connection connection = activeConnectionWithCredentials();
        when(connectionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(connection));
        doThrow(new RuntimeException("provider unavailable"))
                .when(connectionStrategy)
                .revokeProvider(erasureRef(connection));

        assertThatThrownBy(() -> contributor(List.of(connectionStrategy)).deleteWorkspaceData(WORKSPACE_ID))
                .isInstanceOf(WorkspacePurgeBlockedException.class)
                .hasMessage(
                        "Could not confirm disconnecting GitHub. No local data was deleted; retry when the provider is available.");

        assertThat(connection.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(connection.getCredentialsEncrypted()).isNotNull();
    }

    @Test
    void purge_scrubsCredentialsWhenDisabledProviderHasNoStrategy() throws Exception {
        Connection connection = activeConnectionWithCredentials();
        when(connectionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(connection));

        contributor(List.of()).deleteWorkspaceData(WORKSPACE_ID);

        assertThat(connection.getState()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(connection.getCredentialsEncrypted()).isNull();
    }

    @Test
    void purge_skipsAlreadyUninstalledConnections() throws Exception {
        Connection connection = activeConnectionWithCredentials();
        connection.setState(IntegrationState.UNINSTALLED);
        when(connectionRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(List.of(connection));

        contributor(List.of(connectionStrategy)).deleteWorkspaceData(WORKSPACE_ID);

        verify(auditRepository, never()).save(any());
        verify(connectionStrategy, never()).revokeProvider(any());
    }

    private ConnectionPurgeContributor contributor(List<ConnectionStrategy> strategies) {
        return new ConnectionPurgeContributor(connectionRepository, connectionService, strategies);
    }

    private static IntegrationRef erasureRef(Connection connection) {
        return new IntegrationRef(
                connection.getKind(),
                connection.getWorkspace().getId(),
                connection.getInstanceKey(),
                connection.getId());
    }

    private Connection activeConnectionWithCredentials() throws Exception {
        Connection connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "100",
                new ConnectionConfig.GitHubAppConfig(100L, null, null, Set.of()));
        Field id = Connection.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(connection, 55L);
        connection.setState(IntegrationState.ACTIVE);
        connection.setCredentials(new BearerToken("ghp-secret", null), credentialConverter);
        Mockito.lenient()
                .when(connectionRepository.findByIdAndWorkspaceId(55L, WORKSPACE_ID))
                .thenReturn(Optional.of(connection));
        return connection;
    }
}
