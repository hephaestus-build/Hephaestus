package de.tum.cit.aet.hephaestus.integration.scm.github.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProvisioningListener;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationService;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.adoption.GitHubInstallationRequests;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.dto.GitHubInstallationEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.dto.GitHubInstallationRepositoriesEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.installation.dto.GitHubInstallationTargetEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle.GitHubLifecycleListener;
import de.tum.cit.aet.hephaestus.integration.scm.github.workspace.GitHubInstallationReconciler;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceScopeFilter;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Every GitHub installation path leaves a purged workspace as it is: its row does not change, and no path creates
 * another workspace in its place. The installation events arrive through the real handlers and the startup
 * reconciliation through the real reconciler; only GitHub itself is a fixture.
 */
class GitHubPurgedWorkspaceIntegrationTest extends BaseIntegrationTest {

    private final GitHubAppTokenService appTokens = mock(GitHubAppTokenService.class);

    private GitHubInstallationMessageHandler installations;

    @Autowired
    private GitHubInstallationRepositoriesMessageHandler repositories;

    @Autowired
    private GitHubInstallationTargetMessageHandler targets;

    @Autowired
    private ProvisioningListener provisioningListener;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private EventPayloadDeserializer deserializer;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private IdentityProviderRepository identityProviders;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private GitHubLifecycleListener lifecycleListener;

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private WorkspaceScopeFilter scopeFilter;

    @Autowired
    private GitHubInstallationRequests installationRequests;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        if (identityProviders
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .isEmpty()) {
            identityProviders.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com"));
        }
        installations = new GitHubInstallationMessageHandler(
                provisioningListener,
                organizationService,
                appTokens,
                identityProviders,
                deserializer,
                transactionTemplate);
    }

    @Test
    void shouldKeepAPurgedWorkspaceWhenItsInstallationIsCreatedAgain() throws Exception {
        var event = installationEvent("installation.created");
        var purged = purgedWorkspace(installationId(event));

        installations.handleEvent(event);

        assertUnchanged(purged);
    }

    @Test
    void shouldKeepAPurgedWorkspaceWhenGitHubUnsuspendsItsInstallation() throws Exception {
        var event = installationEvent("installation.unsuspend");
        var purged = purgedWorkspace(installationId(event));
        when(appTokens.isInstallationSuspended(installationId(event))).thenReturn(false);

        installations.handleEvent(event);

        assertUnchanged(purged);
    }

    @Test
    void shouldKeepAPurgedWorkspaceWhenGitHubSuspendsItsInstallation() throws Exception {
        var event = installationEvent("installation.suspend");
        var purged = purgedWorkspace(installationId(event));
        when(appTokens.isInstallationSuspended(installationId(event))).thenReturn(true);

        installations.handleEvent(event);

        assertUnchanged(purged);
    }

    @Test
    void shouldKeepAPurgedWorkspaceWhenItsInstallationIsDeletedAgain() throws Exception {
        var event = installationEvent("installation.deleted");
        var purged = purgedWorkspace(installationId(event));

        installations.handleEvent(event);

        assertUnchanged(purged);
    }

    @Test
    void shouldKeepAPurgedWorkspaceWhenRepositoriesAreAddedToItsInstallation() throws Exception {
        var event = payload("installation_repositories.added", GitHubInstallationRepositoriesEventDTO.class);
        var purged =
                purgedWorkspace(Objects.requireNonNull(event.installation()).id());

        repositories.handleEvent(event);

        assertUnchanged(purged);
    }

    @Test
    void shouldKeepAPurgedWorkspaceWhenRepositoriesAreRemovedFromItsInstallation() throws Exception {
        var event = payload("installation_repositories.removed", GitHubInstallationRepositoriesEventDTO.class);
        var purged =
                purgedWorkspace(Objects.requireNonNull(event.installation()).id());

        repositories.handleEvent(event);

        assertUnchanged(purged);
    }

    @Test
    void shouldKeepAPurgedWorkspaceWhenTheInstallationAccountIsRenamed() throws Exception {
        var event = payload("installation_target", GitHubInstallationTargetEventDTO.class);
        var purged =
                purgedWorkspace(Objects.requireNonNull(event.installation()).id());

        targets.handleEvent(event);

        assertUnchanged(purged);
    }

    @Test
    void shouldKeepAPurgedWorkspaceWhenTheStartupReconciliationFindsItsInstallationActive() throws Exception {
        long installationId = installationId(installationEvent("installation.created"));
        var purged = purgedWorkspace(installationId);

        reconciler(installationId, false).ensureGitHubAppInstallations();

        assertUnchanged(purged);
    }

    @Test
    void shouldKeepAPurgedWorkspaceWhenTheStartupReconciliationFindsItsInstallationSuspended() throws Exception {
        long installationId = installationId(installationEvent("installation.created"));
        var purged = purgedWorkspace(installationId);

        reconciler(installationId, true).ensureGitHubAppInstallations();

        assertUnchanged(purged);
    }

    /**
     * The installation's workspace, created by its installation event and purged by its deletion, as GitHub sends them,
     * with its row as it is now.
     */
    private Purged purgedWorkspace(long installationId) throws Exception {
        when(appTokens.isInstallationSuspended(installationId)).thenReturn(false);
        installations.handleEvent(withInstallation("installation.created", installationId));
        long id = Objects.requireNonNull(workspaceRepository
                .findByInstallationId(installationId)
                .orElseThrow()
                .getId());
        installations.handleEvent(withInstallation("installation.deleted", installationId));
        assertThat(row(id)).containsEntry("status", "PURGED");
        return new Purged(id, installationId, row(id), workspacesOf(installationId, row(id).get("account_login")));
    }

    private GitHubInstallationEventDTO withInstallation(String name, long installationId) throws Exception {
        var event = (ObjectNode) objectMapper.readTree(resource(name));
        ((ObjectNode) event.get("installation")).put("id", installationId);
        return objectMapper.treeToValue(event, GitHubInstallationEventDTO.class);
    }

    private void assertUnchanged(Purged purged) {
        assertThat(row(purged.id())).containsEntry("status", "PURGED").isEqualTo(purged.row());
        assertThat(workspacesOf(purged.installationId(), purged.row().get("account_login")))
                .isEqualTo(purged.workspaces());
    }

    /** The startup reconciliation, with GitHub answering that the App has this one installation. */
    private GitHubInstallationReconciler reconciler(long installationId, boolean suspended) {
        var tokens = mock(GitHubAppTokenService.class);
        when(tokens.isConfigured()).thenReturn(true);
        when(tokens.generateAppJWT()).thenReturn("app-jwt");
        when(tokens.isInstallationMarkedSuspended(anyLong())).thenReturn(false);
        String app = """
                {"id": 1, "name": "Hephaestus", "slug": "hephaestus", "owner": {"login": "HephaestusTest"}}""";
        String installation = """
                [{"id": %d, "repository_selection": "all", "suspended_at": %s,
                  "account": {"id": 215361191, "login": "HephaestusTest", "type": "Organization",
                              "avatar_url": "https://avatars.githubusercontent.com/u/215361191?v=4"}}]""".formatted(installationId, suspended ? "\"2026-11-01T00:00:00Z\"" : "null");
        var github = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body(request.url().getPath().endsWith("/installations") ? installation : app)
                        .build()));
        return new GitHubInstallationReconciler(
                tokens,
                lifecycleListener,
                workspaceRepository,
                workspaceService,
                scopeFilter,
                installationRequests,
                github);
    }

    private Map<String, @Nullable Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM workspace WHERE id = ?", id);
    }

    /** The workspaces of this installation or its account: a replacement for the purged one would be among them. */
    private List<Long> workspacesOf(long installationId, @Nullable Object accountLogin) {
        return jdbc.query("""
                SELECT id FROM workspace
                WHERE account_login = ?
                   OR id IN (SELECT workspace_id FROM connection WHERE kind = 'GITHUB' AND instance_key = ?)
                ORDER BY id""", (row, number) -> row.getLong("id"), accountLogin, Long.toString(installationId));
    }

    private GitHubInstallationEventDTO installationEvent(String name) throws Exception {
        return payload(name, GitHubInstallationEventDTO.class);
    }

    private <T> T payload(String name, Class<T> type) throws Exception {
        return objectMapper.readValue(resource(name), type);
    }

    private static String resource(String name) throws Exception {
        return new ClassPathResource("github/" + name + ".json").getContentAsString(StandardCharsets.UTF_8);
    }

    private static long installationId(GitHubInstallationEventDTO event) {
        return Objects.requireNonNull(event.installation()).id();
    }

    private record Purged(long id, long installationId, Map<String, @Nullable Object> row, List<Long> workspaces) {}
}
