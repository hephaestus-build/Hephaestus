package de.tum.cit.aet.hephaestus.integration.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectFinalization;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.EntityNotFoundException;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@Tag("unit")
class OAuthCallbackServiceTest extends BaseUnitTest {

    @Mock
    private ConnectionRepository connectionRepository;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private CredentialBundleConverter credentialBundleConverter;

    private OAuthCallbackService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OAuthCallbackService(
                connectionRepository, connectionService, workspaceRepository, credentialBundleConverter);
    }

    @Test
    void findOrCreate_reusesPending() {
        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        42L, IntegrationKind.SLACK, IntegrationState.PENDING))
                .thenReturn(Optional.of(pending));

        Connection result = service.findOrCreatePendingConnection(42L, IntegrationKind.SLACK);

        assertThat(result).isSameAs(pending);
        verify(workspaceRepository, never()).findById(any());
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void findOrCreate_reusesActiveForReconnect() {
        Connection active = newConnection(7L, 42L, IntegrationKind.SLACK, "T1", IntegrationState.ACTIVE);
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        42L, IntegrationKind.SLACK, IntegrationState.PENDING))
                .thenReturn(Optional.empty());
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        42L, IntegrationKind.SLACK, IntegrationState.ACTIVE))
                .thenReturn(Optional.of(active));

        Connection result = service.findOrCreatePendingConnection(42L, IntegrationKind.SLACK);

        assertThat(result).isSameAs(active);
        verify(workspaceRepository, never()).findById(any());
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void findOrCreate_createsFreshWhenNoneExists() {
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        any(Long.class), any(), any()))
                .thenReturn(Optional.empty());
        Workspace workspace = new Workspace();
        when(workspaceRepository.findById(42L)).thenReturn(Optional.of(workspace));
        when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));

        Connection result = service.findOrCreatePendingConnection(42L, IntegrationKind.SLACK);

        assertThat(result.getKind()).isEqualTo(IntegrationKind.SLACK);
        assertThat(result.getInstanceKey()).isNull();
        assertThat(result.getConfig()).isInstanceOf(ConnectionConfig.SlackConfig.class);
        verify(connectionRepository).save(any(Connection.class));
    }

    @Test
    void findOrCreate_missingWorkspace_throws() {
        when(connectionRepository.findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        any(Long.class), any(), any()))
                .thenReturn(Optional.empty());
        when(workspaceRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findOrCreatePendingConnection(42L, IntegrationKind.SLACK))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("Workspace not found");
    }

    @Test
    @DisplayName(
            "completeConnection stamps instance_key on a fresh PENDING row + transitions ACTIVE with the captured actorRef")
    void complete_stampsInstanceKeyAndTransitions() {
        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        ConnectFinalization.Completed completed =
                new ConnectFinalization.Completed("T123", new BearerToken("xoxb", null), "Acme");
        when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(connectionService.transition(any(Connection.class), any(TransitionRequest.class)))
                .thenAnswer(inv -> {
                    Connection c = inv.getArgument(0);
                    c.setState(IntegrationState.ACTIVE);
                    return c;
                });
        when(credentialBundleConverter.encrypt(any(), any())).thenReturn(new byte[] {0x02, 1, 2, 3});

        Connection result = service.completeConnection(pending, completed, "alice@example.com");

        assertThat(result.getInstanceKey()).isEqualTo("T123");
        assertThat(result.getDisplayName()).isEqualTo("Acme");
        assertThat(result.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(result.getCredentialsAlg()).isEqualTo("aesgcm-v2");

        ArgumentCaptor<TransitionRequest> req = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(connectionService).transition(any(Connection.class), req.capture());
        assertThat(req.getValue().next()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(req.getValue().eventType()).isEqualTo("OAUTH_COMPLETE");
        assertThat(req.getValue().actorKind()).isEqualTo("USER");
        assertThat(req.getValue().actorRef()).isEqualTo("alice@example.com");
        assertThat(req.getValue().correlationId()).startsWith("oauth-T123-");
        assertThat(req.getValue().detail()).isEqualTo("Acme");
    }

    @Test
    void complete_nullActorRef_usesSentinel() {
        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        ConnectFinalization.Completed completed =
                new ConnectFinalization.Completed("T1", new BearerToken("t", null), null);
        when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(connectionService.transition(any(Connection.class), any(TransitionRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.completeConnection(pending, completed, null);

        ArgumentCaptor<TransitionRequest> req = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(connectionService).transition(any(Connection.class), req.capture());
        assertThat(req.getValue().actorRef()).isEqualTo(OAuthCallbackService.ACTOR_FALLBACK);
    }

    @Test
    void complete_conflictingInstanceKey_throws() {
        Connection existing = newConnection(7L, 42L, IntegrationKind.SLACK, "T_ORIG", IntegrationState.ACTIVE);
        ConnectFinalization.Completed completed =
                new ConnectFinalization.Completed("T_NEW", new BearerToken("t", null), "Renamed");
        Assertions.assertThatThrownBy(() -> service.completeConnection(existing, completed, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("instance_key");
    }

    @Test
    void complete_slackTeamAlreadyActiveInAnotherWorkspace_throwsBeforeSaving() {
        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        Connection otherWorkspace = newConnection(8L, 99L, IntegrationKind.SLACK, "T1", IntegrationState.ACTIVE);
        ConnectFinalization.Completed completed =
                new ConnectFinalization.Completed("T1", new BearerToken("t", null), "Acme");
        when(connectionRepository.findFirstByKindAndInstanceKeyAndState(
                        IntegrationKind.SLACK, "T1", IntegrationState.ACTIVE))
                .thenReturn(Optional.of(otherWorkspace));

        assertThatThrownBy(() -> service.completeConnection(pending, completed, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already connected")
                .hasMessageContaining("workspace 99");

        verify(connectionRepository, never()).save(any(Connection.class));
        verify(connectionService, never()).transition(any(), any());
    }

    @Test
    void complete_slackTeamAlreadyActiveInSameWorkspace_refreshesActiveConnection() {
        Connection stalePending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        Connection active = newConnection(8L, 42L, IntegrationKind.SLACK, "T1", IntegrationState.ACTIVE);
        ConnectFinalization.Completed completed =
                new ConnectFinalization.Completed("T1", new BearerToken("new-token", null), "Acme");
        when(connectionRepository.findFirstByKindAndInstanceKeyAndState(
                        IntegrationKind.SLACK, "T1", IntegrationState.ACTIVE))
                .thenReturn(Optional.of(active));
        when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(connectionService.transition(any(Connection.class), any(TransitionRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(credentialBundleConverter.encrypt(any(), any())).thenReturn(new byte[] {0x02, 4, 5, 6});

        Connection result = service.completeConnection(stalePending, completed, "alice");

        assertThat(result).isSameAs(active);
        assertThat(active.getDisplayName()).isEqualTo("Acme");
        assertThat(active.getCredentialsAlg()).isEqualTo("aesgcm-v2");
        verify(connectionRepository).save(active);
        verify(connectionRepository, never()).save(stalePending);
        verify(connectionRepository).delete(stalePending);
        verify(connectionService).transition(eq(active), any(TransitionRequest.class));
    }

    @Test
    void complete_slackTeamPreviouslyUninstalledInSameWorkspace_reusesConnectionAndDeletesPending() {
        Connection stalePending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        Connection uninstalled = newConnection(8L, 42L, IntegrationKind.SLACK, "T1", IntegrationState.UNINSTALLED);
        ConnectFinalization.Completed completed =
                new ConnectFinalization.Completed("T1", new BearerToken("new-token", null), "Acme");
        when(connectionRepository.findFirstByKindAndInstanceKeyAndState(
                        IntegrationKind.SLACK, "T1", IntegrationState.ACTIVE))
                .thenReturn(Optional.empty());
        when(connectionRepository.findByWorkspaceIdAndKindAndInstanceKey(42L, IntegrationKind.SLACK, "T1"))
                .thenReturn(Optional.of(uninstalled));
        when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(connectionService.transition(any(Connection.class), any(TransitionRequest.class)))
                .thenAnswer(inv -> {
                    Connection c = inv.getArgument(0);
                    c.setState(IntegrationState.ACTIVE);
                    return c;
                });
        when(credentialBundleConverter.encrypt(any(), any())).thenReturn(new byte[] {0x02, 7, 8, 9});

        Connection result = service.completeConnection(stalePending, completed, "alice");

        assertThat(result).isSameAs(uninstalled);
        assertThat(uninstalled.getDisplayName()).isEqualTo("Acme");
        assertThat(uninstalled.getCredentialsAlg()).isEqualTo("aesgcm-v2");
        verify(connectionRepository).delete(stalePending);
        verify(connectionRepository).save(uninstalled);
        verify(connectionRepository, never()).save(stalePending);
        verify(connectionService).transition(eq(uninstalled), any(TransitionRequest.class));
    }

    @Test
    void complete_transitionGuardRejects_throwsThrough() {
        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        ConnectFinalization.Completed completed =
                new ConnectFinalization.Completed("T1", new BearerToken("t", null), null);
        when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(connectionService.transition(any(Connection.class), any(TransitionRequest.class)))
                .thenThrow(new IllegalStateException("Illegal transition for connection 7"));

        assertThatThrownBy(() -> service.completeConnection(pending, completed, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Illegal transition");
    }

    @Test
    void complete_nullCredentials_noBlobStamped() {
        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        ConnectFinalization.Completed completed = new ConnectFinalization.Completed("T1", null, null);
        when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(connectionService.transition(any(Connection.class), any(TransitionRequest.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Connection result = service.completeConnection(pending, completed, "alice");

        assertThat(result.getCredentialsAlg()).isNull();
        assertThat(result.getCredentialsEncrypted()).isNull();
        verify(connectionService).transition(eq(pending), any(TransitionRequest.class));
    }

    // helpers

    private static Connection newConnection(
            long id, long workspaceId, IntegrationKind kind, @Nullable String instanceKey, IntegrationState state) {
        Workspace ws = new Workspace();
        ws.setId(workspaceId);
        ConnectionConfig cfg =
                switch (kind) {
                    case GITHUB -> new ConnectionConfig.GitHubAppConfig(null, null, null, Set.of());
                    case GITLAB ->
                        new ConnectionConfig.GitLabConfig(
                                "https://gitlab.com",
                                null,
                                null,
                                ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                                Set.of());
                    case KEYCLOAK_DIRECTORY ->
                        new ConnectionConfig.KeycloakDirectoryConfig(
                                "organization", "https://identity.example.com/realms/team", Set.of());
                    case SLACK -> new ConnectionConfig.SlackConfig(null, null, null, null, null, Set.of());
                    case OUTLINE ->
                        new ConnectionConfig.OutlineConfig(
                                "https://app.getoutline.com", null, null, java.util.Set.of());
                };
        Connection c = new Connection(ws, kind, instanceKey, cfg);
        c.setState(state);
        try {
            Field idField = Connection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        return c;
    }
}
