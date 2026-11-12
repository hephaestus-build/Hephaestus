package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionBusyException;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.oauth.OAuthBrowserBinding;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.InstallationCredential;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectInitiation;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class ConnectionControllerTest extends BaseUnitTest {

    @Mock
    private ConnectionAdminService admin;

    @Mock
    private ConnectionService connectionService;

    private ObjectMapper objectMapper;
    private FakeStrategy githubStrategy;
    private FakeStrategy gitlabStrategy;
    private ConnectionController controller;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static final OAuthBrowserBinding BROWSER_BINDING =
            new OAuthBrowserBinding(new OAuthStateProperties(null, Duration.ofMinutes(10), Duration.ofDays(7)));

    private final MockHttpServletResponse servletResponse = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        var jwt = Jwt.withTokenValue("test").header("alg", "none").subject("42").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        githubStrategy = new FakeStrategy(IntegrationKind.GITHUB);
        gitlabStrategy = new FakeStrategy(IntegrationKind.GITLAB);
        controller = new ConnectionController(
                admin, connectionService, objectMapper, List.of(githubStrategy, gitlabStrategy), BROWSER_BINDING);
    }

    @Test
    void list_returnsTheWorkspacesConnections() {
        long workspaceId = 42L;
        Connection a = newConnection(11L, workspaceId, IntegrationKind.GITHUB, "100", IntegrationState.ACTIVE);
        Connection b =
                newConnection(12L, workspaceId, IntegrationKind.GITLAB, "gitlab.com:200", IntegrationState.SUSPENDED);
        when(admin.listForWorkspace(workspaceId)).thenReturn(List.of(a, b));

        ResponseEntity<List<ConnectionSummaryDTO>> response = controller.list(ctx(workspaceId));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<ConnectionSummaryDTO> body = response.getBody();
        assertThat(body).hasSize(2);
        assertThat(body.get(0).id()).isEqualTo(11L);
        assertThat(body.get(0).kind()).isEqualTo(IntegrationKind.GITHUB);
        assertThat(body.get(1).kind()).isEqualTo(IntegrationKind.GITLAB);
        assertThat(body.get(1).state()).isEqualTo(IntegrationState.SUSPENDED);
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
    void initiate_github_returnsRedirect() {
        URI vendor = URI.create("https://github.com/apps/x/installations/new?state=abc");
        githubStrategy.nextInitiation = new ConnectInitiation.RedirectToVendor(vendor, "abc");

        InitiateConnectionRequestDTO req = new InitiateConnectionRequestDTO(IntegrationKind.GITHUB, Map.of());
        ResponseEntity<InitiateConnectionResponseDTO> response =
                controller.initiate(ctx(7L), req, null, servletResponse);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        InitiateConnectionResponseDTO redirect = response.getBody();
        assertNotNull(redirect);
        assertThat(redirect.type()).isEqualTo(InitiateConnectionResponseDTO.Type.REDIRECT);
        assertThat(redirect.vendorUrl()).isEqualTo(vendor);
        assertThat(redirect.connectionId()).isNull();
        verify(admin, never()).createInlineConnection(anyLong(), any(), any(), any(), any(), any());
        // The browser that starts the flow is the one whose callback may finish it.
        Cookie binding = servletResponse.getCookie(OAuthBrowserBinding.cookieName("abc"));
        assertNotNull(binding);
        assertThat(binding.getValue()).isEqualTo(OAuthBrowserBinding.cookieValue("abc"));
        assertThat(binding.getName()).startsWith("__Host-");
        assertThat(binding.isHttpOnly()).isTrue();
        assertThat(binding.getSecure()).isTrue();
        assertThat(binding.getPath()).isEqualTo("/");
        assertThat(binding.getMaxAge()).isEqualTo(600);
        assertThat(binding.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    void initiate_gitlab_acceptInline_createsRow() {
        long workspaceId = 17L;
        gitlabStrategy.nextInitiation = new ConnectInitiation.AcceptInline(new BearerToken("glpat-fake", null), "200");

        Connection saved = newConnection(99L, workspaceId, IntegrationKind.GITLAB, "200", IntegrationState.ACTIVE);
        when(admin.createInlineConnection(
                        eq(workspaceId), eq(IntegrationKind.GITLAB), eq("200"), any(BearerToken.class), any(), eq(42L)))
                .thenReturn(saved);

        InitiateConnectionRequestDTO req = new InitiateConnectionRequestDTO(
                IntegrationKind.GITLAB,
                Map.of("pat", "glpat-fake", "group_id", "200", "server_url", "https://gitlab.com"));
        Authentication auth = new UsernamePasswordAuthenticationToken("alice@example.com", "");
        ResponseEntity<InitiateConnectionResponseDTO> response =
                controller.initiate(ctx(workspaceId), req, auth, servletResponse);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        InitiateConnectionResponseDTO linked = response.getBody();
        assertNotNull(linked);
        assertThat(linked.type()).isEqualTo(InitiateConnectionResponseDTO.Type.LINKED);
        assertThat(linked.connectionId()).isEqualTo(99L);
        assertThat(linked.vendorUrl()).isNull();
        // A pasted token returns to no callback, so nothing binds the browser.
        assertThat(servletResponse.getCookies()).isEmpty();

        verify(admin)
                .createInlineConnection(
                        eq(workspaceId),
                        eq(IntegrationKind.GITLAB),
                        eq("200"),
                        any(BearerToken.class),
                        anyMap(),
                        eq(42L));
    }

    @Test
    @DisplayName("initiate with no registered strategy throws IllegalArgumentException (→ 400 via" + " advice)")
    void initiate_unknownKind_throwsBadRequest() {
        ConnectionController bare =
                new ConnectionController(admin, connectionService, objectMapper, List.of(), BROWSER_BINDING);
        InitiateConnectionRequestDTO req = new InitiateConnectionRequestDTO(IntegrationKind.SLACK, Map.of());
        assertThatThrownBy(() -> bare.initiate(ctx(1L), req, null, servletResponse))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("This instance cannot connect that integration.");
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

        ResponseEntity<ConnectionSummaryDTO> response = controller.updateStatus(
                ctx(workspaceId),
                7L,
                new UpdateConnectionStatusRequestDTO(IntegrationState.SUSPENDED, "scheduled maintenance"),
                null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertNotNull(response.getBody());
        assertThat(response.getBody().state()).isEqualTo(IntegrationState.SUSPENDED);

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
    void updateStatus_toUninstalled_disconnectsThroughTheConnectionsStrategy() {
        long workspaceId = 42L;
        Connection c = newConnection(7L, workspaceId, IntegrationKind.GITHUB, "100", IntegrationState.ACTIVE);
        when(admin.findInWorkspaceOrThrow(workspaceId, 7L)).thenReturn(c);
        when(connectionService.disconnect(any(Connection.class), any(TransitionRequest.class), any()))
                .thenAnswer(inv -> {
                    Connection conn = inv.getArgument(0);
                    conn.setState(IntegrationState.UNINSTALLED);
                    return conn;
                });

        ResponseEntity<ConnectionSummaryDTO> response = controller.updateStatus(
                ctx(workspaceId), 7L, new UpdateConnectionStatusRequestDTO(IntegrationState.UNINSTALLED, null), null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertNotNull(response.getBody());
        assertThat(response.getBody().state()).isEqualTo(IntegrationState.UNINSTALLED);

        ArgumentCaptor<TransitionRequest> req = ArgumentCaptor.forClass(TransitionRequest.class);
        verify(connectionService).disconnect(same(c), req.capture(), same(githubStrategy));
        verify(connectionService, never()).transition(any(Connection.class), any(TransitionRequest.class));
        assertThat(req.getValue().next()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(req.getValue().eventType()).isEqualTo("DISCONNECT");
    }

    @Test
    void updateStatus_toUninstalledWithoutStrategy_isRefused() {
        long workspaceId = 42L;
        Connection c = newConnection(7L, workspaceId, IntegrationKind.SLACK, "T1", IntegrationState.ACTIVE);
        when(admin.findInWorkspaceOrThrow(workspaceId, 7L)).thenReturn(c);

        assertThatThrownBy(() -> controller.updateStatus(
                        ctx(workspaceId),
                        7L,
                        new UpdateConnectionStatusRequestDTO(IntegrationState.UNINSTALLED, null),
                        null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("turned off on this instance");

        verify(connectionService, never()).disconnect(any(), any(), any());
        verify(connectionService, never()).transition(any(), any());
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

        assertThatThrownBy(() -> controller.updateStatus(
                        ctx(workspaceId),
                        7L,
                        new UpdateConnectionStatusRequestDTO(IntegrationState.PENDING, null),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(connectionService, never()).transition(any(Connection.class), any(TransitionRequest.class));
    }

    @Test
    void exceptionHandler_notFound_to404ProblemDetail() {
        ProblemDetail problem = controller.handleNotFound(new NoSuchElementException("Connection not found: id=999"));
        assertThat(problem.getStatus()).isEqualTo(404);
        assertThat(problem.getDetail()).contains("999");
        assertThat(problem.getTitle()).isEqualTo("Resource not found");
    }

    private static WorkspaceContext ctx(long workspaceId) {
        return new WorkspaceContext(
                workspaceId,
                "ws-" + workspaceId,
                "Workspace " + workspaceId,
                AccountType.ORG,
                null,
                Set.of(WorkspaceRole.ADMIN));
    }

    private Connection newConnection(
            long id, long workspaceId, IntegrationKind kind, String instanceKey, IntegrationState state) {
        Workspace ws = new Workspace();
        ws.setId(workspaceId);
        ConnectionConfig cfg = switch (kind) {
            case GITHUB -> new ConnectionConfig.GitHubAppConfig(100L, "acme", null, Set.of());
            case GITLAB -> new ConnectionConfig.GitLabConfig("https://gitlab.com", 200L, null, Set.of(), null);
            case SLACK -> new ConnectionConfig.SlackConfig(null, null, null, Set.of());
            case OUTLINE -> new ConnectionConfig.OutlineConfig("https://app.getoutline.com", null, null, Set.of());
        };
        Connection c = new Connection(ws, kind, instanceKey, cfg);
        c.setState(state);
        setIdAndTimestamps(c, id);
        return c;
    }

    private static void setIdAndTimestamps(Connection c, long id) {
        // The entity normally gets createdAt/updatedAt from @CreationTimestamp /
        // @UpdateTimestamp on save. Unit tests skip the JPA layer so we set them by
        // reflection — DTO serialisation would NPE on null Instants otherwise.
        ReflectionTestUtils.setField(c, "id", id);
        ReflectionTestUtils.setField(c, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        ReflectionTestUtils.setField(c, "updatedAt", Instant.parse("2026-01-02T00:00:00Z"));
    }

    /** Per-kind strategy stub — initiate output is dictated by the test. */
    static final class FakeStrategy implements ConnectionStrategy {

        private final IntegrationKind kind;
        ConnectInitiation nextInitiation =
                new ConnectInitiation.AcceptInline(new BearerToken("test-token", null), null);

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
        public void eraseLocalData(IntegrationRef ref) {}

        @Override
        public Optional<Runnable> prepareProviderTeardown(IntegrationRef ref, ProviderFootprint footprint) {
            return Optional.empty();
        }
    }
}
