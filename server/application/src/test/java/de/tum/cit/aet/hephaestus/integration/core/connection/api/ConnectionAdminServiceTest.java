package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAudit;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAuditRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Covers the logic that moved out of the controller — workspace-scoped lookup +
 * inline-connection creation. The companion {@link ConnectionControllerTest} mocks
 * this service away to test the HTTP adapter shell in isolation.
 */
class ConnectionAdminServiceTest extends BaseUnitTest {

    @Mock
    private ConnectionRepository connectionRepository;

    @Mock
    private ConnectionAuditRepository auditRepository;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private IntegrationManifestRegistry manifests;

    private CredentialBundleConverter credentialConverter;
    private ConnectionAdminService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Real converter so the encrypt/round-trip behaviour is exercised end-to-end.
        credentialConverter = new CredentialBundleConverter("a".repeat(32), false);
        service = new ConnectionAdminService(
                connectionRepository,
                auditRepository,
                connectionService,
                workspaceRepository,
                manifests,
                credentialConverter);
    }

    @Test
    void listForWorkspace_delegatesToRepo() {
        long workspaceId = 7L;
        when(connectionRepository.findByWorkspaceId(workspaceId)).thenReturn(List.of());
        assertThat(service.listForWorkspace(workspaceId)).isEmpty();
    }

    @Test
    void findInWorkspaceOrThrow_matchingWorkspace_returns() {
        long workspaceId = 7L;
        Workspace ws = new Workspace();
        ws.setId(workspaceId);
        Connection c = new Connection(
                ws, IntegrationKind.GITHUB, "100", new ConnectionConfig.GitHubAppConfig(100L, null, null, Set.of()));
        when(connectionRepository.findById(42L)).thenReturn(Optional.of(c));

        assertThat(service.findInWorkspaceOrThrow(workspaceId, 42L)).isSameAs(c);
    }

    @Test
    void findInWorkspaceOrThrow_missingId_throws() {
        when(connectionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findInWorkspaceOrThrow(7L, 99L)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void findInWorkspaceOrThrow_wrongWorkspace_throws() {
        Workspace ws = new Workspace();
        ws.setId(1L);
        Connection c = new Connection(
                ws, IntegrationKind.GITHUB, "100", new ConnectionConfig.GitHubAppConfig(100L, null, null, Set.of()));
        when(connectionRepository.findById(42L)).thenReturn(Optional.of(c));

        // requested workspace 999 != actual 1
        assertThatThrownBy(() -> service.findInWorkspaceOrThrow(999L, 42L)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void auditForConnection_appliesLimit() {
        Workspace ws = new Workspace();
        Connection c = new Connection(
                ws, IntegrationKind.GITHUB, "100", new ConnectionConfig.GitHubAppConfig(100L, null, null, Set.of()));
        List<ConnectionAudit> entries = List.of(
                new ConnectionAudit(c, "A", null, IntegrationState.ACTIVE, "ADMIN", "x", "1", null),
                new ConnectionAudit(
                        c, "B", IntegrationState.ACTIVE, IntegrationState.SUSPENDED, "ADMIN", "x", "2", null),
                new ConnectionAudit(
                        c, "C", IntegrationState.SUSPENDED, IntegrationState.ACTIVE, "ADMIN", "x", "3", null));
        when(auditRepository.findByConnectionIdOrderByOccurredAtDesc(7L)).thenReturn(entries);

        assertThat(service.auditForConnection(7L, 2)).hasSize(2);
        assertThat(service.auditForConnection(7L, 100)).hasSize(3);
    }

    @Test
    void createInlineConnection_happyPath() {
        long workspaceId = 17L;
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        when(workspaceRepository.findById(workspaceId)).thenReturn(Optional.of(workspace));
        when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> {
            Connection saved = inv.getArgument(0);
            setId(saved, 99L);
            return saved;
        });
        when(connectionService.transition(any(Connection.class), any(TransitionRequest.class)))
                .thenAnswer(inv -> {
                    Connection conn = inv.getArgument(0);
                    conn.setState(IntegrationState.ACTIVE);
                    return conn;
                });

        Connection result = service.createInlineConnection(
                workspaceId,
                IntegrationKind.GITLAB,
                "200",
                new BearerToken("glpat-test", null),
                Map.of("server_url", "https://gitlab.example.com"),
                "alice");

        assertThat(result.getId()).isEqualTo(99L);
        assertThat(result.getKind()).isEqualTo(IntegrationKind.GITLAB);
        assertThat(result.getInstanceKey()).isEqualTo("200");
        assertThat(result.getConfig()).isInstanceOf(ConnectionConfig.GitLabConfig.class);
        ConnectionConfig.GitLabConfig cfg = (ConnectionConfig.GitLabConfig) result.getConfig();
        assertThat(cfg.serverUrl()).isEqualTo("https://gitlab.example.com");
        assertThat(cfg.gitlabGroupId()).isEqualTo(200L);
        assertThat(result.getCredentialsAlg()).isEqualTo(CredentialBundleConverter.ALGORITHM_TAG);
        assertThat(result.getCredentialsEncrypted()).isNotNull();
        // Round-trip the freshly-encrypted blob to prove it's not a placeholder.
        assertThat(result.credentials(credentialConverter)).contains(new BearerToken("glpat-test", null));

        ArgumentCaptor<TransitionRequest> req = ArgumentCaptor.forClass(TransitionRequest.class);
        Mockito.verify(connectionService).transition(any(Connection.class), req.capture());
        assertThat(req.getValue().next()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(req.getValue().eventType()).isEqualTo("INITIATE");
        assertThat(req.getValue().actorRef()).isEqualTo("alice");
        assertThat(req.getValue().correlationId()).startsWith("initiate-99-");
    }

    @Test
    void createInlineConnection_missingWorkspace_throws() {
        when(workspaceRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createInlineConnection(
                        99L, IntegrationKind.GITLAB, "x", new BearerToken("t", null), Map.of(), "alice"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    private static void setId(Connection c, long id) {
        try {
            var f = Connection.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
