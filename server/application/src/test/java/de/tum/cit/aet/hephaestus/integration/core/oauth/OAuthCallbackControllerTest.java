package de.tum.cit.aet.hephaestus.integration.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService.StateBinding;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectFinalization;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectInitiation;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.webhook.IntegrationKindRouting;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Pure unit tests for {@link OAuthCallbackController} — no Spring context, no MockMvc.
 *
 * <p>Verifies the security-critical paths (state HMAC mismatch, kind mismatch, vendor
 * cancellation) AND the happy path (state consumed → strategy finalize → PENDING →
 * ACTIVE transition → 302 to success URL). The Spring routing layer + the
 * {@code @PreAuthorize("permitAll()")} wiring are covered by the architecture tests
 * + the SecurityConfig {@code securityMatcher} that pins {@code /oauth/callback/**}
 * to the permit-all worker chain.
 */
class OAuthCallbackControllerTest extends BaseUnitTest {

    private static final OAuthCallbackProperties PROPS =
            new OAuthCallbackProperties("/integrations?status=success", null);

    @Mock
    private OAuthStateService oauthStateService;

    @Mock
    private OAuthCallbackService callbackService;

    private IntegrationKindRouting routing;
    private FakeStrategy slackStrategy;
    private OAuthCallbackController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Real routing — its behaviour is unit-tested separately; using the real bean
        // here gives the test exact parity with production path resolution.
        routing = new IntegrationKindRouting();
        slackStrategy = new FakeStrategy(IntegrationKind.SLACK);
        controller =
                new OAuthCallbackController(routing, oauthStateService, callbackService, List.of(slackStrategy), PROPS);
    }

    // Happy path

    @Test
    void happyPath_slackCompleted_transitionsAndRedirects() {
        String state = "signed-state-token";
        StateBinding binding = new StateBinding(42L, IntegrationKind.SLACK, Instant.now(), "alice@example.com");
        when(oauthStateService.consume(state)).thenReturn(binding);

        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        when(callbackService.findOrCreatePendingConnection(42L, IntegrationKind.SLACK))
                .thenReturn(pending);
        when(callbackService.completeConnection(any(Connection.class), any(), any()))
                .thenAnswer(inv -> {
                    Connection c = inv.getArgument(0);
                    c.setState(IntegrationState.ACTIVE);
                    return c;
                });

        slackStrategy.nextFinalization =
                new ConnectFinalization.Completed("T123ABC", new BearerToken("xoxb-fake", null), "Acme Workspace");

        Map<String, String> allParams = new HashMap<>();
        allParams.put("code", "vendor-code");
        allParams.put("state", state);

        ResponseEntity<?> response = controller.callbackGet("slack", state, null, null, allParams, htmlRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        var location0 = response.getHeaders().getLocation();
        assertNotNull(location0);
        assertThat(location0.toString()).isEqualTo(PROPS.successRedirect());

        ArgumentCaptor<ConnectFinalization.Completed> completed =
                ArgumentCaptor.forClass(ConnectFinalization.Completed.class);
        ArgumentCaptor<String> actor = ArgumentCaptor.forClass(String.class);
        verify(callbackService).completeConnection(eq(pending), completed.capture(), actor.capture());
        assertThat(completed.getValue().instanceKey()).isEqualTo("T123ABC");
        assertThat(completed.getValue().displayName()).isEqualTo("Acme Workspace");
        assertThat(actor.getValue()).isEqualTo("alice@example.com");

        // The strategy's finalize must NOT see the state param — the controller scrubs it
        // before handoff so vendors don't accidentally double-log.
        assertThat(slackStrategy.lastCallbackParams).doesNotContainKey("state");
        assertThat(slackStrategy.lastCallbackParams).containsEntry("code", "vendor-code");
    }

    @Test
    void happyPath_nullActorRef_passedThrough() {
        String state = "signed-state-token";
        StateBinding binding = new StateBinding(42L, IntegrationKind.SLACK, Instant.now(), null);
        when(oauthStateService.consume(state)).thenReturn(binding);
        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        when(callbackService.findOrCreatePendingConnection(42L, IntegrationKind.SLACK))
                .thenReturn(pending);
        when(callbackService.completeConnection(any(Connection.class), any(), any()))
                .thenReturn(pending);
        slackStrategy.nextFinalization = new ConnectFinalization.Completed("T123", new BearerToken("tok", null), null);

        controller.callbackGet("slack", state, null, null, Map.of("code", "c", "state", state), htmlRequest());

        ArgumentCaptor<String> actor = ArgumentCaptor.forClass(String.class);
        verify(callbackService).completeConnection(any(Connection.class), any(), actor.capture());
        assertThat(actor.getValue()).isNull();
    }

    // Vendor-side error

    @Test
    void vendorError_jsonRequest_returns400WithStructuredJson() {
        ResponseEntity<?> response = controller.callbackGet(
                "slack",
                /* state */ null,
                "access_denied",
                "The user denied the request",
                Map.of("error", "access_denied", "error_description", "The user denied the request"),
                jsonRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("kind", "SLACK").containsEntry("error", "access_denied");
        assertThat(body.getDetail()).isEqualTo("The user denied the request");

        // State was never consumed — the user might retry without re-issuing.
        verify(oauthStateService, never()).consume(any());
        // Strategy never called.
        assertThat(slackStrategy.finalizeCalls).isEqualTo(0);
        verify(callbackService, never()).findOrCreatePendingConnection(anyLong(), any());
    }

    @Test
    void vendorError_browserRequest_redirectsToFailure() {
        ResponseEntity<?> response = controller.callbackGet(
                "slack",
                null,
                "access_denied",
                "The user denied the request",
                Map.of("error", "access_denied", "error_description", "The user denied the request"),
                htmlRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        var location1 = response.getHeaders().getLocation();
        assertNotNull(location1);
        String location = location1.toString();
        assertThat(location).contains("status=error");
        assertThat(location).contains("reason=access_denied");
        assertThat(location).contains("kind=SLACK");
        verify(oauthStateService, never()).consume(any());
        assertThat(slackStrategy.finalizeCalls).isEqualTo(0);
    }

    // State validation

    @Test
    void missingState_jsonRequest_returns400() {
        ResponseEntity<?> response =
                controller.callbackGet("slack", null, null, null, Map.of("code", "code"), jsonRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("error", "missing_state");
    }

    @Test
    void missingState_browserRequest_redirects() {
        ResponseEntity<?> response =
                controller.callbackGet("slack", null, null, null, Map.of("code", "code"), htmlRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        var location2 = response.getHeaders().getLocation();
        assertNotNull(location2);
        assertThat(location2.toString()).contains("reason=missing_state");
    }

    @Test
    void badState_jsonRequest_returns400() {
        when(oauthStateService.consume("tampered"))
                .thenThrow(new IllegalArgumentException("OAuth state signature mismatch"));

        ResponseEntity<?> response = controller.callbackGet(
                "slack", "tampered", null, null, Map.of("code", "c", "state", "tampered"), jsonRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("error", "invalid_state");
        assertThat(body.getDetail()).contains("signature mismatch");
        // Critical: strategy NEVER invoked when the state is bad.
        assertThat(slackStrategy.finalizeCalls).isEqualTo(0);
        verify(callbackService, never()).findOrCreatePendingConnection(anyLong(), any());
    }

    @Test
    void stateKindMismatch_jsonRequest_returns400() {
        // State issued for SLACK; replayed against /oauth/callback/github.
        StateBinding binding = new StateBinding(42L, IntegrationKind.SLACK, Instant.now(), "alice");
        when(oauthStateService.consume("slack-state")).thenReturn(binding);

        ResponseEntity<?> response = controller.callbackGet(
                "github", "slack-state", null, null, Map.of("code", "c", "state", "slack-state"), jsonRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("error", "kind_mismatch");
        assertThat(body.getDetail()).contains("SLACK").contains("github");
        assertThat(slackStrategy.finalizeCalls).isEqualTo(0);
        verify(callbackService, never()).findOrCreatePendingConnection(anyLong(), any());
    }

    // Strategy failure

    @Test
    void finalizeFailed_jsonRequest_returns400() {
        StateBinding binding = new StateBinding(42L, IntegrationKind.SLACK, Instant.now(), "alice");
        when(oauthStateService.consume("s")).thenReturn(binding);
        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        when(callbackService.findOrCreatePendingConnection(42L, IntegrationKind.SLACK))
                .thenReturn(pending);

        slackStrategy.nextFinalization = new ConnectFinalization.Failed("vendor rejected the code");

        ResponseEntity<?> response =
                controller.callbackGet("slack", "s", null, null, Map.of("code", "c", "state", "s"), jsonRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("error", "finalize_failed");
        assertThat(body.getDetail()).contains("vendor rejected");
        // No completion issued on a Failed result.
        verify(callbackService, never()).completeConnection(any(), any(), any());
    }

    @Test
    void finalizeFailed_browserRequest_redirects() {
        StateBinding binding = new StateBinding(42L, IntegrationKind.SLACK, Instant.now(), "alice");
        when(oauthStateService.consume("s")).thenReturn(binding);
        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        when(callbackService.findOrCreatePendingConnection(42L, IntegrationKind.SLACK))
                .thenReturn(pending);

        slackStrategy.nextFinalization = new ConnectFinalization.Failed("vendor rejected the code");

        ResponseEntity<?> response =
                controller.callbackGet("slack", "s", null, null, Map.of("code", "c", "state", "s"), htmlRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        var location3 = response.getHeaders().getLocation();
        assertNotNull(location3);
        String location = location3.toString();
        assertThat(location)
                .contains("status=error")
                .contains("reason=finalize_failed")
                .contains("kind=SLACK");
        verify(callbackService, never()).completeConnection(any(), any(), any());
    }

    @Test
    void finalizeThrows_jsonRequest_returns400() {
        StateBinding binding = new StateBinding(42L, IntegrationKind.SLACK, Instant.now(), "alice");
        when(oauthStateService.consume("s")).thenReturn(binding);
        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        when(callbackService.findOrCreatePendingConnection(42L, IntegrationKind.SLACK))
                .thenReturn(pending);

        slackStrategy.throwOnFinalize = new RuntimeException("vendor 500");

        ResponseEntity<?> response =
                controller.callbackGet("slack", "s", null, null, Map.of("code", "c", "state", "s"), jsonRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("error", "strategy_error");
    }

    // Transition guard rejection

    @Test
    void completeConnection_transitionGuardRejects_jsonRequest_returns409() {
        StateBinding binding = new StateBinding(42L, IntegrationKind.SLACK, Instant.now(), "alice");
        when(oauthStateService.consume("s")).thenReturn(binding);
        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        when(callbackService.findOrCreatePendingConnection(42L, IntegrationKind.SLACK))
                .thenReturn(pending);
        slackStrategy.nextFinalization = new ConnectFinalization.Completed("T1", new BearerToken("t", null), null);
        when(callbackService.completeConnection(any(), any(), any()))
                .thenThrow(new IllegalStateException("Illegal transition for connection 7: UNINSTALLED → ACTIVE"));

        ResponseEntity<?> response =
                controller.callbackGet("slack", "s", null, null, Map.of("code", "c", "state", "s"), jsonRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("error", "transition_conflict");
    }

    @Test
    void completeConnection_transitionGuardRejects_browserRequest_redirects() {
        StateBinding binding = new StateBinding(42L, IntegrationKind.SLACK, Instant.now(), "alice");
        when(oauthStateService.consume("s")).thenReturn(binding);
        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        when(callbackService.findOrCreatePendingConnection(42L, IntegrationKind.SLACK))
                .thenReturn(pending);
        slackStrategy.nextFinalization = new ConnectFinalization.Completed("T1", new BearerToken("t", null), null);
        when(callbackService.completeConnection(any(), any(), any()))
                .thenThrow(new IllegalStateException("Illegal transition for connection 7: UNINSTALLED → ACTIVE"));

        ResponseEntity<?> response =
                controller.callbackGet("slack", "s", null, null, Map.of("code", "c", "state", "s"), htmlRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        var location4 = response.getHeaders().getLocation();
        assertNotNull(location4);
        assertThat(location4.toString()).contains("reason=transition_conflict");
    }

    // Unknown kind

    @Test
    void unknownKind_jsonRequest_returns404() {
        ResponseEntity<?> response =
                controller.callbackGet("bitbucket", "s", null, null, Map.of("code", "c", "state", "s"), jsonRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("error", "unknown_kind");
        verify(oauthStateService, never()).consume(any());
    }

    @Test
    void unknownKind_browserRequest_redirects() {
        ResponseEntity<?> response =
                controller.callbackGet("bitbucket", "s", null, null, Map.of("code", "c", "state", "s"), htmlRequest());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        var location5 = response.getHeaders().getLocation();
        assertNotNull(location5);
        assertThat(location5.toString()).contains("reason=unknown_kind");
    }

    // No strategy registered

    @Test
    void noStrategy_returns500() {
        // Build a controller with NO strategies registered.
        OAuthCallbackController bare =
                new OAuthCallbackController(routing, oauthStateService, callbackService, List.of(), PROPS);
        StateBinding binding = new StateBinding(42L, IntegrationKind.SLACK, Instant.now(), "alice");
        when(oauthStateService.consume("s")).thenReturn(binding);

        ResponseEntity<?> response =
                bare.callbackGet("slack", "s", null, null, Map.of("code", "c", "state", "s"), htmlRequest());
        // Server-side wiring bug — always problem+json 500, no point redirecting a broken flow.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ProblemDetail body = (ProblemDetail) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getProperties()).containsEntry("error", "no_strategy");
    }

    // POST callback

    @Test
    void postCallback_sharesGetHandler() {
        StateBinding binding = new StateBinding(42L, IntegrationKind.SLACK, Instant.now(), "alice");
        when(oauthStateService.consume("s")).thenReturn(binding);
        Connection pending = newConnection(7L, 42L, IntegrationKind.SLACK, null, IntegrationState.PENDING);
        when(callbackService.findOrCreatePendingConnection(42L, IntegrationKind.SLACK))
                .thenReturn(pending);
        when(callbackService.completeConnection(any(), any(), any())).thenReturn(pending);
        slackStrategy.nextFinalization = new ConnectFinalization.Completed("T1", new BearerToken("t", null), null);

        ResponseEntity<?> response =
                controller.callbackPost("slack", "s", null, null, Map.of("code", "c", "state", "s"), htmlRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        var location6 = response.getHeaders().getLocation();
        assertNotNull(location6);
        assertThat(location6.toString()).isEqualTo(PROPS.successRedirect());
        verify(callbackService).completeConnection(any(), any(), any());
    }

    // Duplicate strategy guard

    @Test
    void duplicateStrategy_throwsAtWiringTime() {
        FakeStrategy a = new FakeStrategy(IntegrationKind.SLACK);
        FakeStrategy b = new FakeStrategy(IntegrationKind.SLACK);
        Assertions.assertThatThrownBy(() ->
                        new OAuthCallbackController(routing, oauthStateService, callbackService, List.of(a, b), PROPS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate ConnectionStrategy");
    }

    // helpers

    /** Browser request: {@code Accept: text/html,...} (typical browser default). */
    private static HttpServletRequest htmlRequest() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.lenient()
                .when(req.getHeader(HttpHeaders.ACCEPT))
                .thenReturn("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        return req;
    }

    /** JSON-only request (curl / devtools / E2E suite). */
    private static HttpServletRequest jsonRequest() {
        HttpServletRequest req = Mockito.mock(HttpServletRequest.class);
        Mockito.lenient().when(req.getHeader(HttpHeaders.ACCEPT)).thenReturn("application/json");
        return req;
    }

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
                    case GITHUB_ACCESS -> new ConnectionConfig.GitHubAccessConfig(Set.of());
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
        setId(c, id);
        return c;
    }

    private static void setId(Connection c, long id) {
        try {
            Field idField = Connection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(c, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * Per-kind ConnectionStrategy stub. Tests dictate the {@code nextFinalization} (or
     * {@code throwOnFinalize}); call counters let us assert the strategy was — or was
     * NOT — invoked.
     */
    static final class FakeStrategy implements ConnectionStrategy {

        private final IntegrationKind kind;

        @Nullable
        ConnectFinalization nextFinalization;

        @Nullable
        RuntimeException throwOnFinalize;

        int finalizeCalls = 0;

        @Nullable
        Map<String, String> lastCallbackParams;

        FakeStrategy(IntegrationKind kind) {
            this.kind = kind;
        }

        @Override
        public IntegrationKind kind() {
            return kind;
        }

        @Override
        public ConnectInitiation initiate(InitiateRequest request) {
            throw new UnsupportedOperationException("initiate() not exercised by OAuthCallbackController");
        }

        @Override
        public ConnectFinalization finalizeConnect(@Nullable IntegrationRef ref, Map<String, String> callbackParams) {
            finalizeCalls++;
            lastCallbackParams = callbackParams;
            if (throwOnFinalize != null) throw throwOnFinalize;
            if (nextFinalization == null) {
                throw new IllegalStateException("test forgot to set nextFinalization");
            }
            return nextFinalization;
        }

        @Override
        public void revoke(@Nullable IntegrationRef ref) {
            // unused in this controller
        }
    }
}
