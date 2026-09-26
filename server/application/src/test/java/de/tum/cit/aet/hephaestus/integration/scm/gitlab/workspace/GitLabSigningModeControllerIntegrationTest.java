package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAuditRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig.GitLabConfig.SigningMode;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code PUT /workspaces/{slug}/connections/{id}/gitlab-signing-mode} through the real security chain and
 * the application's own GitLab client, against a GitLab that keeps group hooks the way its update API
 * does: a sent token is stored, an explicit {@code null} clears it, an absent key leaves it alone.
 */
@Tag("integration")
class GitLabSigningModeControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String APP_ADMIN_TOKEN = "mock-jwt-token-for-admin-user";
    private static final String WORKSPACE_ADMIN_TOKEN = "mock-jwt-token-for-mentor-user";
    private static final String HOOK_URL = "http://localhost:8080/webhooks/gitlab";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private ConnectionAuditRepository connectionAuditRepository;

    @Autowired
    private CredentialBundleConverter credentialConverter;

    @Value("${hephaestus.webhook.secret}")
    private String webhookSecret;

    private MockWebServer server;
    private FakeGitLab gitlab;
    private Workspace workspace;
    private Connection connection;

    @BeforeEach
    void startGitLab() throws IOException {
        gitlab = new FakeGitLab();
        server = new MockWebServer();
        server.setDispatcher(gitlab);
        server.start();
        User owner = persistUser("gitlab-owner");
        workspace = createWorkspace("signing-space", "Signing", "my-group", AccountType.ORG, owner);
        connection = activeGitLabConnection(workspace);
        // A hook the legacy registration left behind, adopted by URL.
        gitlab.hooks.put(7L, new Hook(HOOK_URL, true, false));
    }

    @AfterEach
    void stopGitLab() throws IOException {
        server.close();
    }

    @Test
    void shouldMoveTheHookToASigningTokenAndBack() {
        assertThat(put(APP_ADMIN_TOKEN, workspace, SigningMode.WHSEC)
                        .expectStatus()
                        .isOk()
                        .expectBody(GitLabSigningModeDTO.class)
                        .returnResult()
                        .getResponseBody())
                .isEqualTo(new GitLabSigningModeDTO(SigningMode.WHSEC, 7L));
        assertThat(gitlab.hooks.get(7L)).isEqualTo(new Hook(HOOK_URL, false, true));
        assertThat(gitlab.signingToken).isEqualTo(webhookSecret);
        assertThat(storedConfig().signingMode()).isEqualTo(SigningMode.WHSEC);
        assertThat(storedConfig().gitlabWebhookId()).isEqualTo(7L);

        put(APP_ADMIN_TOKEN, workspace, SigningMode.PLAINTEXT)
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        assertThat(gitlab.hooks.get(7L)).isEqualTo(new Hook(HOOK_URL, true, false));
        assertThat(storedConfig().signingMode()).isEqualTo(SigningMode.PLAINTEXT);

        // A retry reconciles the hook again but records no second change.
        put(APP_ADMIN_TOKEN, workspace, SigningMode.PLAINTEXT)
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        assertThat(connectionAuditRepository.findByConnectionIdOrderByOccurredAtDesc(connection.getId()))
                .filteredOn(audit -> audit.getEventType().equals("SIGNING_MODE"))
                .extracting(audit -> audit.getActorRef() + " " + audit.getDetail())
                .hasSize(2)
                .allSatisfy(
                        entry -> assertThat(entry).doesNotStartWith("anonymous").doesNotContain(webhookSecret))
                .anySatisfy(entry -> assertThat(entry).contains("PLAINTEXT -> WHSEC"))
                .anySatisfy(entry -> assertThat(entry).contains("WHSEC -> PLAINTEXT"));
    }

    @Test
    void shouldRefuseAGitLabBefore19WithoutTouchingTheHook() {
        gitlab.version = "18.11.2-ee";

        put(APP_ADMIN_TOKEN, workspace, SigningMode.WHSEC)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail")
                .value(detail -> assertThat(detail.toString()).contains("GitLab 19.1 or later"));

        assertThat(gitlab.requests).containsExactly("GET /api/v4/version");
        assertThat(gitlab.hooks.get(7L)).isEqualTo(new Hook(HOOK_URL, true, false));
        assertThat(storedConfig().signingMode()).isEqualTo(SigningMode.PLAINTEXT);
    }

    /** The secret token goes only after the signing token is confirmed, so a failed step leaves both. */
    @Test
    void shouldKeepBothTokensWhenTheSecondStepOfASwitchFails() {
        gitlab.failPut = 2;

        put(APP_ADMIN_TOKEN, workspace, SigningMode.WHSEC)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("GitLab API error: 500");
        assertThat(gitlab.hooks.get(7L)).isEqualTo(new Hook(HOOK_URL, true, true));
        assertThat(storedConfig().signingMode()).isEqualTo(SigningMode.PLAINTEXT);

        gitlab.failPut = 0;
        put(APP_ADMIN_TOKEN, workspace, SigningMode.WHSEC).expectStatus().isOk().expectBody(Void.class);
        gitlab.failPut = gitlab.puts.get() + 2;

        put(APP_ADMIN_TOKEN, workspace, SigningMode.PLAINTEXT)
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        assertThat(gitlab.hooks.get(7L)).isEqualTo(new Hook(HOOK_URL, true, true));
        assertThat(storedConfig().signingMode()).isEqualTo(SigningMode.WHSEC);
    }

    /** Clearing by JSON null is read from GitLab's source; a GitLab that keeps the key is reported. */
    @Test
    void shouldReportAGitLabThatKeepsTheSigningToken() {
        put(APP_ADMIN_TOKEN, workspace, SigningMode.WHSEC).expectStatus().isOk().expectBody(Void.class);
        gitlab.ignoreNullSigningToken = true;

        put(APP_ADMIN_TOKEN, workspace, SigningMode.PLAINTEXT)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail")
                .value(detail -> assertThat(detail.toString()).contains("kept the webhook's signing token"));
        assertThat(gitlab.hooks.get(7L)).isEqualTo(new Hook(HOOK_URL, true, true));
        assertThat(storedConfig().signingMode()).isEqualTo(SigningMode.WHSEC);
    }

    /** What a failed store after a finished switch leaves behind: a retry completes it. */
    @Test
    void shouldStoreTheModeOnRetryWhenTheHookAlreadySwitched() {
        gitlab.hooks.put(7L, new Hook(HOOK_URL, false, true));

        put(APP_ADMIN_TOKEN, workspace, SigningMode.WHSEC).expectStatus().isOk().expectBody(Void.class);

        assertThat(storedConfig().signingMode()).isEqualTo(SigningMode.WHSEC);
    }

    /** Token flags alone would read as switched; the read-back must also show this deployment's URL. */
    @Test
    void shouldRefuseASwitchThatLeavesTheWebhookAtAnotherUrl() {
        String oldUrl = "https://old.example.com/webhooks/gitlab";
        gitlab.hooks.put(7L, new Hook(oldUrl, true, false));
        connectionRepository.save(storedHook(7L));
        gitlab.ignoreUrl = true;

        put(APP_ADMIN_TOKEN, workspace, SigningMode.WHSEC)
                .expectStatus()
                .isEqualTo(409)
                .expectBody()
                .jsonPath("$.detail")
                .value(detail -> assertThat(detail.toString()).contains("no longer shows the webhook at"));
        assertThat(gitlab.hooks.get(7L)).isEqualTo(new Hook(oldUrl, false, true));
        assertThat(storedConfig().signingMode()).isEqualTo(SigningMode.PLAINTEXT);
    }

    @Test
    void shouldAnswerAnUnexpectedGitLabResponseAsAServerError() {
        gitlab.omitIdOnPut = true;

        put(APP_ADMIN_TOKEN, workspace, SigningMode.WHSEC)
                .expectStatus()
                .is5xxServerError()
                .expectBody(Void.class);
        assertThat(storedConfig().signingMode()).isEqualTo(SigningMode.PLAINTEXT);
    }

    @Test
    void shouldForbidAWorkspaceAdminWhoIsNotAnInstanceAdmin() {
        ensureWorkspaceMembership(workspace, persistUser("mentor"), WorkspaceRole.ADMIN);

        put(WORKSPACE_ADMIN_TOKEN, workspace, SigningMode.WHSEC)
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        assertThat(gitlab.requests).isEmpty();
    }

    @Test
    void shouldNotFindAMissingConnectionOrOneOfAnotherWorkspace() {
        Workspace other = createWorkspace("other-space", "Other", "other-group", AccountType.ORG, persistUser("other"));

        put(APP_ADMIN_TOKEN, other, SigningMode.WHSEC)
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("GitLab connection " + connection.getId() + " not found");
        webTestClient
                .put()
                .uri("/workspaces/{slug}/connections/{id}/gitlab-signing-mode", workspace.getWorkspaceSlug(), 999_999L)
                .headers(headers -> headers.setBearerAuth(APP_ADMIN_TOKEN))
                .bodyValue(new GitLabSigningModeRequestDTO(SigningMode.WHSEC))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.detail")
                .isEqualTo("GitLab connection 999999 not found");

        assertThat(gitlab.requests).isEmpty();
        assertThat(storedConfig().signingMode()).isEqualTo(SigningMode.PLAINTEXT);
    }

    private WebTestClient.ResponseSpec put(String token, Workspace target, SigningMode mode) {
        return webTestClient
                .put()
                .uri(
                        "/workspaces/{slug}/connections/{id}/gitlab-signing-mode",
                        target.getWorkspaceSlug(),
                        connection.getId())
                .headers(headers -> headers.setBearerAuth(token))
                .bodyValue(new GitLabSigningModeRequestDTO(mode))
                .exchange();
    }

    /** Seeded ACTIVE without a lifecycle transition, whose activation event would start a GitLab sync. */
    private Connection activeGitLabConnection(Workspace owner) {
        String serverUrl = "http://" + server.getHostName() + ":" + server.getPort();
        Connection seeded = new Connection(
                owner,
                IntegrationKind.GITLAB,
                serverUrl + "/my-group",
                new GitLabConfig(serverUrl, 42L, null, SigningMode.PLAINTEXT, Set.of()));
        seeded.setState(IntegrationState.ACTIVE);
        seeded.setCredentials(new BearerToken("glpat-test", null), credentialConverter);
        return connectionRepository.save(seeded);
    }

    private Connection storedHook(long webhookId) {
        Connection stored = connectionRepository.findById(connection.getId()).orElseThrow();
        stored.setConfig(((GitLabConfig) stored.getConfig()).withGitlabWebhookId(webhookId));
        return stored;
    }

    private GitLabConfig storedConfig() {
        return (GitLabConfig)
                connectionRepository.findById(connection.getId()).orElseThrow().getConfig();
    }

    private record Hook(String url, boolean tokenPresent, boolean signingTokenPresent) {}

    private static final class FakeGitLab extends Dispatcher {

        private static final JsonMapper JSON = JsonMapper.builder().build();

        final Map<Long, Hook> hooks = new ConcurrentHashMap<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger puts = new AtomicInteger();
        volatile String version = "19.4.0-ee";
        /** The 1-based update to answer with a 500; 0 fails none. */
        volatile int failPut;

        volatile boolean ignoreNullSigningToken;
        volatile boolean omitIdOnPut;
        volatile boolean ignoreUrl;
        volatile @Nullable String signingToken;

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String call = request.getMethod() + " " + request.getTarget();
            requests.add(call);
            if (call.equals("GET /api/v4/version")) {
                return json("{\"version\":\"" + version + "\"}");
            }
            if (call.equals("GET /api/v4/groups/42/hooks?per_page=100")) {
                return json(hooks.entrySet().stream()
                        .map(e -> body(e.getKey(), e.getValue()))
                        .toList()
                        .toString());
            }
            String prefix = "/api/v4/groups/42/hooks/";
            if (call.startsWith("PUT " + prefix)) {
                if (puts.incrementAndGet() == failPut) {
                    return new MockResponse.Builder().code(500).build();
                }
                long id = Long.parseLong(request.getTarget().substring(prefix.length()));
                var sent = request.getBody();
                JsonNode update = JSON.readTree(sent == null ? "{}" : sent.utf8());
                Hook current = hooks.get(id);
                if (current == null) {
                    return new MockResponse.Builder().code(404).build();
                }
                Hook next = new Hook(
                        ignoreUrl ? current.url() : update.path("url").asString(current.url()),
                        present(update, "token", current.tokenPresent()),
                        ignoreNullSigningToken && update.path("signing_token").isNull()
                                ? current.signingTokenPresent()
                                : present(update, "signing_token", current.signingTokenPresent()));
                if (update.hasNonNull("signing_token")) {
                    signingToken = update.get("signing_token").asString();
                }
                hooks.put(id, next);
                return json(omitIdOnPut ? "{\"url\":\"" + next.url() + "\"}" : body(id, next));
            }
            if (call.startsWith("GET " + prefix)) {
                long id = Long.parseLong(request.getTarget().substring(prefix.length()));
                Hook hook = hooks.get(id);
                return hook == null ? new MockResponse.Builder().code(404).build() : json(body(id, hook));
            }
            return new MockResponse.Builder().code(404).build();
        }

        /** A sent value sets the token, an explicit null clears it, and an absent key keeps it. */
        private static boolean present(JsonNode update, String field, boolean current) {
            return update.has(field) ? !update.get(field).isNull() : current;
        }

        private static String body(long id, Hook hook) {
            return "{\"id\":" + id + ",\"url\":\"" + hook.url() + "\",\"alert_status\":\"executable\","
                    + "\"token_present\":" + hook.tokenPresent() + ",\"signing_token_present\":"
                    + hook.signingTokenPresent() + "}";
        }

        private static MockResponse json(String body) {
            return new MockResponse.Builder()
                    .addHeader("Content-Type", "application/json")
                    .body(body)
                    .build();
        }
    }
}
