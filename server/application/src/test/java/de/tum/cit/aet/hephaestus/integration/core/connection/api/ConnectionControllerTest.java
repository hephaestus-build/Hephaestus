package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAudit;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionBusyException;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.InstallationCredential;
import de.tum.cit.aet.hephaestus.integration.core.spi.Capability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectInitiation;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.Assertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure unit tests for {@link ConnectionController}. Exercises the controller directly
 * against mocked collaborators — no Spring context, no MockMvc — so failures point
 * at controller logic, not framework wiring.
 *
 * <p>The arch-test {@code haveSecurityAnnotationIfEndpoint} already covers the
 * {@code @PreAuthorize} wiring; here we verify behaviour.
 */
class ConnectionControllerTest extends BaseUnitTest {

    @Mock
    private ConnectionAdminService admin;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private IntegrationManifestRegistry manifests;

    private ObjectMapper objectMapper;
    private FakeStrategy githubStrategy;
    private FakeStrategy gitlabStrategy;
    private ConnectionController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        githubStrategy = new FakeStrategy(IntegrationKind.GITHUB);
        gitlabStrategy = new FakeStrategy(IntegrationKind.GITLAB);
        // admin.manifests() is the single source of truth for capability lookups in the controller;
        // wire it lazily-lenient so list/read/suspend/reactivate tests don't all need to restate it.
        Mockito.lenient().when(admin.manifests()).thenReturn(manifests);
        controller = new ConnectionController(
                admin, connectionService, objectMapper, List.of(githubStrategy, gitlabStrategy));
    }

    @Test
    void list_returnsRowsWithCapabilitiesFromManifests() {
        long workspaceId = 42L;
        Connection a = newConnection(11L, workspaceId, IntegrationKind.GITHUB, "100", IntegrationState.ACTIVE);
        Connection b =
                newConnection(12L, workspaceId, IntegrationKind.GITLAB, "gitlab.com:200", IntegrationState.SUSPENDED);
        when(admin.listForWorkspace(workspaceId)).thenReturn(List.of(a, b));
        when(manifests.capabilitiesFor(IntegrationKind.GITHUB))
                .thenReturn(Set.of(Capability.WEBHOOK_INGEST, Capability.TOKEN_REFRESH));
        when(manifests.capabilitiesFor(IntegrationKind.GITLAB)).thenReturn(Set.of(Capability.WEBHOOK_INGEST));

        ResponseEntity<List<ConnectionSummaryDTO>> response = controller.list(ctx(workspaceId));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<ConnectionSummaryDTO> body = response.getBody();
        assertThat(body).hasSize(2);
        assertThat(body.get(0).id()).isEqualTo(11L);
        assertThat(body.get(0).kind()).isEqualTo(IntegrationKind.GITHUB);
        assertThat(body.get(0).capabilities())
                .containsExactlyInAnyOrder(Capability.WEBHOOK_INGEST, Capability.TOKEN_REFRESH);
        assertThat(body.get(1).kind()).isEqualTo(IntegrationKind.GITLAB);
        assertThat(body.get(1).capabilities()).containsExactly(Capability.WEBHOOK_INGEST);
    }

    @Test
    @DisplayName("read 404s when connection belongs to a different workspace")
    void read_wrongWorkspace_throwsNotFound() {
        when(admin.findInWorkspaceOrThrow(999L, 7L))
                .thenThrow(new NoSuchElementException("Connection not found in workspace 999: id=7"));
        assertThatThrownBy(() -> controller.read(ctx(999L), 7L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("workspace 999");
    }

    @Test
    @DisplayName("read never serializes the Outline webhook signing secret")
    void read_outlineConnection_redactsWebhookSecret() {
        long workspaceId = 42L;
        Workspace ws = new Workspace();
        ws.setId(workspaceId);
        Connection outline = new Connection(
                ws,
                IntegrationKind.OUTLINE,
                "https://o.test",
                new ConnectionConfig.OutlineConfig("https://o.test", "sub-1", "ENC:v2:signing-secret", Set.of()));
        outline.setState(IntegrationState.ACTIVE);
        setIdAndTimestamps(outline, 5L);
        when(admin.findInWorkspaceOrThrow(workspaceId, 5L)).thenReturn(outline);
        when(manifests.capabilitiesFor(IntegrationKind.OUTLINE)).thenReturn(Set.of(Capability.WEBHOOK_INGEST));

        ResponseEntity<ConnectionDetailDTO> response = controller.read(ctx(workspaceId), 5L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertNotNull(response.getBody());
        Map<String, Object> config = response.getBody().config();
        assertThat(config).doesNotContainKey("webhookSecret");
        assertThat(config).containsEntry("webhookSubscriptionId", "sub-1");
        assertThat(objectMapper.writeValueAsString(response.getBody())).doesNotContain("ENC:v2:signing-secret");
    }

    @Test
    void initiate_github_returnsRedirect() {
        URI vendor = URI.create("https://github.com/apps/x/installations/new?state=abc");
        githubStrategy.nextInitiation = new ConnectInitiation.RedirectToVendor(vendor, "abc");

        InitiateConnectionRequestDTO req = new InitiateConnectionRequestDTO(IntegrationKind.GITHUB, Map.of());
        ResponseEntity<InitiateConnectionResponseDTO> response = controller.initiate(ctx(7L), req, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        InitiateConnectionResponseDTO redirect = response.getBody();
        assertNotNull(redirect);
        assertThat(redirect.type()).isEqualTo(InitiateConnectionResponseDTO.Type.REDIRECT);
        assertThat(redirect.vendorUrl()).isEqualTo(vendor);
        assertThat(redirect.connectionId()).isNull();
        verify(admin, never()).createInlineConnection(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void initiate_gitlab_acceptInline_createsRow() {
        long workspaceId = 17L;
        gitlabStrategy.nextInitiation = new ConnectInitiation.AcceptInline(new BearerToken("glpat-fake", null), "200");

        Connection saved = newConnection(99L, workspaceId, IntegrationKind.GITLAB, "200", IntegrationState.ACTIVE);
        when(admin.createInlineConnection(
                        eq(workspaceId),
                        eq(IntegrationKind.GITLAB),
                        eq("200"),
                        any(BearerToken.class),
                        any(),
                        eq("alice@example.com")))
                .thenReturn(saved);

        InitiateConnectionRequestDTO req = new InitiateConnectionRequestDTO(
                IntegrationKind.GITLAB,
                Map.of("pat", "glpat-fake", "group_id", "200", "server_url", "https://gitlab.com"));
        Authentication auth = new UsernamePasswordAuthenticationToken("alice@example.com", "");
        ResponseEntity<InitiateConnectionResponseDTO> response = controller.initiate(ctx(workspaceId), req, auth);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        InitiateConnectionResponseDTO linked = response.getBody();
        assertNotNull(linked);
        assertThat(linked.type()).isEqualTo(InitiateConnectionResponseDTO.Type.LINKED);
        assertThat(linked.connectionId()).isEqualTo(99L);
        assertThat(linked.vendorUrl()).isNull();

        verify(admin)
                .createInlineConnection(
                        eq(workspaceId),
                        eq(IntegrationKind.GITLAB),
                        eq("200"),
                        any(BearerToken.class),
                        anyMap(),
                        eq("alice@example.com"));
    }

    @Test
    @DisplayName("initiate with no registered strategy throws IllegalArgumentException (→ 400 via advice)")
    void initiate_unknownKind_throwsBadRequest() {
        ConnectionController bare = new ConnectionController(admin, connectionService, objectMapper, List.of());
        InitiateConnectionRequestDTO req = new InitiateConnectionRequestDTO(IntegrationKind.SLACK, Map.of());
        assertThatThrownBy(() -> bare.initiate(ctx(1L), req, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No ConnectionStrategy registered");
    }

    @Test
    void updateStatus_toSuspended_transitionsAndPersistsReason() {
        long workspaceId = 42L;
        Connection c = newConnection(7L, workspaceId, IntegrationKind.GITHUB, "100", IntegrationState.ACTIVE);
        when(admin.findInWorkspaceOrThrow(workspaceId, 7L)).thenReturn(c);
        when(connectionService.transition(any(Connection.class), any(TransitionRequest.class)))
                .thenAnswer(inv -> {
                    Connection conn = inv.getArgument(0);
                    conn.setState(IntegrationState.SUSPENDED);
                    conn.setStateReason(((TransitionRequest) inv.getArgument(1)).detail());
                    return conn;
                });
        when(manifests.capabilitiesFor(IntegrationKind.GITHUB)).thenReturn(Set.of());

        ResponseEntity<ConnectionSummaryDTO> response = controller.updateStatus(
                ctx(workspaceId),
                7L,
                new UpdateConnectionStatusRequestDTO(IntegrationState.SUSPENDED, "scheduled maintenance"),
                null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertNotNull(response.getBody());
        assertThat(response.getBody().state()).isEqualTo(IntegrationState.SUSPENDED);
        assertThat(response.getBody().stateReason()).isEqualTo("scheduled maintenance");

        ArgumentCaptor<TransitionRequest> req = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(connectionService).transition(any(Connection.class), req.capture());
        assertThat(req.getValue().next()).isEqualTo(IntegrationState.SUSPENDED);
        assertThat(req.getValue().eventType()).isEqualTo("SUSPEND");
        assertThat(req.getValue().detail()).isEqualTo("scheduled maintenance");
        assertThat(req.getValue().actorKind()).isEqualTo("ADMIN");
    }

    @Test
    void updateStatus_toActive_transitions() {
        long workspaceId = 42L;
        Connection c = newConnection(7L, workspaceId, IntegrationKind.GITHUB, "100", IntegrationState.SUSPENDED);
        when(admin.findInWorkspaceOrThrow(workspaceId, 7L)).thenReturn(c);
        when(connectionService.transition(any(Connection.class), any(TransitionRequest.class)))
                .thenAnswer(inv -> {
                    Connection conn = inv.getArgument(0);
                    conn.setState(IntegrationState.ACTIVE);
                    return conn;
                });
        when(manifests.capabilitiesFor(IntegrationKind.GITHUB)).thenReturn(Set.of());

        ResponseEntity<ConnectionSummaryDTO> response = controller.updateStatus(
                ctx(workspaceId), 7L, new UpdateConnectionStatusRequestDTO(IntegrationState.ACTIVE, null), null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertNotNull(response.getBody());
        assertThat(response.getBody().state()).isEqualTo(IntegrationState.ACTIVE);

        ArgumentCaptor<TransitionRequest> req = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(connectionService).transition(any(Connection.class), req.capture());
        assertThat(req.getValue().next()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(req.getValue().eventType()).isEqualTo("REACTIVATE");
    }

    @Test
    void updateStatus_toUninstalled_revokesAndTransitions() {
        long workspaceId = 42L;
        Connection c = newConnection(7L, workspaceId, IntegrationKind.GITHUB, "100", IntegrationState.ACTIVE);
        when(admin.findInWorkspaceOrThrow(workspaceId, 7L)).thenReturn(c);
        when(connectionService.disconnect(any(Connection.class), any(TransitionRequest.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    inv.<Runnable>getArgument(2).run();
                    Connection conn = inv.getArgument(0);
                    conn.setState(IntegrationState.UNINSTALLED);
                    return conn;
                });
        when(manifests.capabilitiesFor(IntegrationKind.GITHUB)).thenReturn(Set.of());

        ResponseEntity<ConnectionSummaryDTO> response = controller.updateStatus(
                ctx(workspaceId), 7L, new UpdateConnectionStatusRequestDTO(IntegrationState.UNINSTALLED, null), null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertNotNull(response.getBody());
        assertThat(response.getBody().state()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(githubStrategy.revokeCalls).isEqualTo(1);

        ArgumentCaptor<TransitionRequest> req = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(connectionService).disconnect(any(Connection.class), req.capture(), any(Runnable.class));
        verify(connectionService, never()).transition(any(Connection.class), any(TransitionRequest.class));
        assertThat(req.getValue().next()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(req.getValue().eventType()).isEqualTo("DISCONNECT");
    }

    /**
     * The controller must NOT swallow a revoke failure itself — swallowing it inside the callback
     * leaves the surrounding transaction rollback-only and turns "proceed locally" into a 500 at
     * commit. It lets the exception out of the callback and {@code ConnectionService} absorbs it on
     * the far side of its own transaction; the stub below mimics that real behaviour.
     */
    @Test
    void updateStatus_uninstalledRevokeThrows_isNotSwallowedInTheCallbackButStillTransitions() {
        long workspaceId = 42L;
        Connection c = newConnection(7L, workspaceId, IntegrationKind.GITHUB, "100", IntegrationState.ACTIVE);
        when(admin.findInWorkspaceOrThrow(workspaceId, 7L)).thenReturn(c);
        githubStrategy.revokeThrows = true;
        AtomicReference<RuntimeException> escaped = new AtomicReference<>();
        when(connectionService.disconnect(any(Connection.class), any(TransitionRequest.class), any(Runnable.class)))
                .thenAnswer(inv -> {
                    // Stands in for ConnectionService#runRevokeIsolated: run the callback on its own
                    // transaction, absorb whatever escapes, then commit the local transition anyway.
                    try {
                        inv.<Runnable>getArgument(2).run();
                    } catch (RuntimeException e) {
                        escaped.set(e);
                    }
                    Connection conn = inv.getArgument(0);
                    conn.setState(IntegrationState.UNINSTALLED);
                    return conn;
                });
        when(manifests.capabilitiesFor(IntegrationKind.GITHUB)).thenReturn(Set.of());

        ResponseEntity<ConnectionSummaryDTO> response = controller.updateStatus(
                ctx(workspaceId), 7L, new UpdateConnectionStatusRequestDTO(IntegrationState.UNINSTALLED, null), null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertNotNull(response.getBody());
        assertThat(response.getBody().state()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(githubStrategy.revokeCalls).isEqualTo(1);
        // The failure must reach the service — the only layer that can absorb it without poisoning
        // the lifecycle transaction.
        assertThat(escaped.get())
                .as("revoke failure must escape the controller callback")
                .isNotNull();
        verify(connectionService, times(1))
                .disconnect(any(Connection.class), any(TransitionRequest.class), any(Runnable.class));
    }

    @Test
    void busyConnection_isMappedToConflictWithActiveJobId() {
        ProblemDetail problem = controller.handleBusyConnection(new ConnectionBusyException(7L, 99L));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("connectionId", 7L).containsEntry("jobId", 99L);
    }

    @Test
    void updateStatus_pendingTarget_throwsBadRequest() {
        long workspaceId = 42L;
        Connection c = newConnection(7L, workspaceId, IntegrationKind.GITHUB, "100", IntegrationState.ACTIVE);
        when(admin.findInWorkspaceOrThrow(workspaceId, 7L)).thenReturn(c);

        Assertions.assertThatThrownBy(() -> controller.updateStatus(
                        ctx(workspaceId),
                        7L,
                        new UpdateConnectionStatusRequestDTO(IntegrationState.PENDING, null),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(connectionService, Mockito.never()).transition(any(Connection.class), any(TransitionRequest.class));
    }

    @Test
    void audit_returnsEntriesCappedAt200() {
        long workspaceId = 42L;
        Connection c = newConnection(7L, workspaceId, IntegrationKind.GITHUB, "100", IntegrationState.ACTIVE);
        when(admin.findInWorkspaceOrThrow(workspaceId, 7L)).thenReturn(c);

        ConnectionAudit a1 = new ConnectionAudit(
                c, "INITIATE", null, IntegrationState.ACTIVE, "ADMIN", "alice", "corr-1", "started");
        ConnectionAudit a2 = new ConnectionAudit(
                c,
                "SUSPEND",
                IntegrationState.ACTIVE,
                IntegrationState.SUSPENDED,
                "ADMIN",
                "bob",
                "corr-2",
                "vacation");
        ReflectionTestUtils.setField(a1, "occurredAt", Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(a2, "occurredAt", Instant.parse("2026-01-02T00:00:00Z"));
        when(admin.auditForConnection(eq(7L), anyInt())).thenReturn(List.of(a2, a1));

        ResponseEntity<List<ConnectionAuditEntryDTO>> response = controller.audit(ctx(workspaceId), 7L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<ConnectionAuditEntryDTO> body = response.getBody();
        assertNotNull(body);
        assertThat(body).hasSize(2);
        assertThat(body.get(0).eventType()).isEqualTo("SUSPEND");
        assertThat(body.get(0).fromState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(body.get(0).toState()).isEqualTo(IntegrationState.SUSPENDED);
        assertThat(body.get(1).eventType()).isEqualTo("INITIATE");
        verify(admin).auditForConnection(7L, 200);
    }

    @Test
    @DisplayName("audit 404s if the connection isn't in the path workspace")
    void audit_wrongWorkspace_throwsNotFound() {
        when(admin.findInWorkspaceOrThrow(999L, 7L))
                .thenThrow(new NoSuchElementException("Connection not found in workspace 999: id=7"));
        assertThatThrownBy(() -> controller.audit(ctx(999L), 7L)).isInstanceOf(NoSuchElementException.class);
        verify(admin, never()).auditForConnection(anyLong(), anyInt());
    }

    @Test
    void exceptionHandler_notFound_to404ProblemDetail() {
        ProblemDetail problem = controller.handleNotFound(new NoSuchElementException("Connection not found: id=999"));
        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getDetail()).contains("999");
        assertThat(problem.getTitle()).isEqualTo("Resource not found");
    }

    // helpers

    /**
     * Minimal admin {@link WorkspaceContext} for the given workspace id. The controller only reads
     * {@code id()}; the filter + {@code @RequireAtLeastWorkspaceAdmin} guard (covered by integration
     * tests) supply the real context at runtime.
     */
    private static WorkspaceContext ctx(long workspaceId) {
        return new WorkspaceContext(
                workspaceId,
                "ws-" + workspaceId,
                "Workspace " + workspaceId,
                AccountType.ORG,
                null,
                false,
                false,
                Set.of(WorkspaceRole.ADMIN));
    }

    private Connection newConnection(
            long id, long workspaceId, IntegrationKind kind, String instanceKey, IntegrationState state) {
        Workspace ws = new Workspace();
        ws.setId(workspaceId);
        ConnectionConfig cfg =
                switch (kind) {
                    case GITHUB -> new ConnectionConfig.GitHubAppConfig(100L, "acme", null, Set.of());
                    case GITLAB ->
                        new ConnectionConfig.GitLabConfig(
                                "https://gitlab.com",
                                200L,
                                null,
                                ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                                Set.of());
                    case GITHUB_ACCESS -> new ConnectionConfig.GitHubAccessConfig(Set.of());
                    case KEYCLOAK_DIRECTORY ->
                        new ConnectionConfig.KeycloakDirectoryConfig(
                                "organization", "https://identity.example.com/realms/team", Set.of());
                    case SLACK -> new ConnectionConfig.SlackConfig(null, null, null, null, null, Set.of());
                    case OUTLINE ->
                        new ConnectionConfig.OutlineConfig("https://app.getoutline.com", null, null, Set.of());
                };
        Connection c = new Connection(ws, kind, instanceKey, cfg);
        c.setState(state);
        setIdAndTimestamps(c, id);
        return c;
    }

    private static void setIdAndTimestamps(Connection c, long id) {
        try {
            // The entity normally gets createdAt/updatedAt from @CreationTimestamp /
            // @UpdateTimestamp on save. Unit tests skip the JPA layer so we set them by
            // reflection — DTO serialisation would NPE on null Instants otherwise.
            Field idField = Connection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, id);

            Field createdAt = Connection.class.getDeclaredField("createdAt");
            createdAt.setAccessible(true);
            createdAt.set(c, Instant.parse("2026-01-01T00:00:00Z"));

            Field updatedAt = Connection.class.getDeclaredField("updatedAt");
            updatedAt.setAccessible(true);
            updatedAt.set(c, Instant.parse("2026-01-02T00:00:00Z"));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Per-kind strategy stub — initiate output is dictated by the test. */
    static final class FakeStrategy implements ConnectionStrategy {

        private final IntegrationKind kind;
        ConnectInitiation nextInitiation =
                new ConnectInitiation.AcceptInline(new BearerToken("test-token", null), null);
        int revokeCalls = 0;
        boolean revokeThrows = false;

        FakeStrategy(IntegrationKind kind) {
            this.kind = kind;
        }

        @Override
        public IntegrationKind kind() {
            return kind;
        }

        @Override
        public ConnectInitiation initiate(InitiateRequest request) {
            if (nextInitiation == null) {
                throw new IllegalStateException("test forgot to set nextInitiation for " + kind);
            }
            return nextInitiation;
        }

        @Override
        public ConnectFinalization finalizeConnect(@Nullable IntegrationRef ref, Map<String, String> callbackParams) {
            return new ConnectFinalization.Completed(
                    "unused-instance-key", new InstallationCredential(0L, "unused"), null);
        }

        @Override
        public void revoke(@Nullable IntegrationRef ref) {
            revokeCalls++;
            if (revokeThrows) {
                throw new RuntimeException("vendor unreachable");
            }
        }
    }
}
