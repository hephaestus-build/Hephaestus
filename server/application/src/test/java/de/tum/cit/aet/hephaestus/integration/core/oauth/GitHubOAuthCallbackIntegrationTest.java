package de.tum.cit.aet.hephaestus.integration.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppUserAuthorizationClient;
import de.tum.cit.aet.hephaestus.integration.scm.github.app.GitHubAppUserAuthorizationClient.UserInstallation;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriBuilder;

/**
 * Connecting a GitHub App installation through the callback. Only GitHub's answers about the authorizing person are a
 * double; state signing, the callback and persistence are real.
 */
class GitHubOAuthCallbackIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private OAuthStateService oauthStateService;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private IdentityLinkRepository identityLinks;

    @Autowired
    private GitHubAppUserAuthorizationClient userAuthorization;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private long installationId;

    @BeforeEach
    void setUp() {
        installationId = ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE);
    }

    @AfterEach
    void tearDown() {
        reset(userAuthorization);
    }

    @Test
    void shouldConnectAnInstallationTheAdministratorOwns() {
        User admin = accountHolder("owner");
        Workspace workspace = workspaceOwnedBy(admin, "Acme");
        givenOwnedByTheAuthorizingPerson();

        URI location = browserCallback(uri -> uri.queryParam("state", state(workspace, admin))
                .queryParam("code", "code-1")
                .queryParam("installation_id", installationId)
                .queryParam("setup_action", "install"));

        assertThat(location).hasPath("/integrations").hasParameter("status", "success");
        Connection connection = connectionRepository
                .findActive(workspace.getId(), IntegrationKind.GITHUB)
                .orElseThrow();
        assertThat(connection.getInstanceKey()).isEqualTo(Long.toString(installationId));
        assertThat(connection.getDisplayName()).isEqualTo("acme");
        assertThat(connection.getConfig())
                .isEqualTo(new ConnectionConfig.GitHubAppConfig(installationId, "acme", null, Set.of()));
        assertThat(connection.getCredentialsEncrypted()).isNull();
    }

    @Test
    void shouldNotConnectAnInstallationIdThatArrivesWithoutAnAuthorization() {
        User admin = accountHolder("forger");
        Workspace workspace = workspaceOwnedBy(admin, "Forger");

        URI location = browserCallback(uri -> uri.queryParam("state", state(workspace, admin))
                .queryParam("installation_id", installationId)
                .queryParam("setup_action", "install"));

        assertThat(location).hasPath("/integrations").hasParameter("status", "error");
        assertThat(connectionRepository.findActive(workspace.getId(), IntegrationKind.GITHUB))
                .isEmpty();
        verifyNoInteractions(userAuthorization);
    }

    @Test
    void shouldNotConnectAnInstallationTheAdministratorCannotAccess() {
        User admin = accountHolder("outsider");
        Workspace workspace = workspaceOwnedBy(admin, "Outsider");
        when(userAuthorization.exchangeCode("code-1")).thenReturn("ghu_user");
        when(userAuthorization.findAccessibleInstallation("ghu_user", installationId))
                .thenReturn(Optional.empty());

        ProblemDetail problem = jsonCallback(workspace, admin, 400);

        assertThat(problem.getDetail()).isEqualTo("Your GitHub account cannot access this installation of the app.");
        assertThat(problem.getProperties()).containsEntry("error", "finalize_failed");
        assertThat(connectionRepository.findActive(workspace.getId(), IntegrationKind.GITHUB))
                .isEmpty();
    }

    @Test
    void shouldRefuseAnInstallationThatAnotherWorkspaceHolds() {
        Workspace holder = workspaceOwnedBy(accountHolder("holder"), "Holder");
        Connection held = connectInstallation(holder, IntegrationState.SUSPENDED);
        User stranger = accountHolder("stranger");
        Workspace target = workspaceOwnedBy(stranger, "Target");
        givenOwnedByTheAuthorizingPerson();

        ProblemDetail problem = jsonCallback(target, stranger, 409);

        assertThat(problem.getDetail())
                .isEqualTo("This GitHub App installation is already connected to another Hephaestus workspace."
                        + " A workspace admin there must disconnect GitHub first.");
        assertThat(problem.getProperties()).containsEntry("error", "connected_elsewhere");
        assertThat(connectionRepository.findActive(target.getId(), IntegrationKind.GITHUB))
                .isEmpty();
        assertThat(connectionRepository.findById(held.getId()).orElseThrow().getState())
                .isEqualTo(IntegrationState.SUSPENDED);
        assertThat(workspaceRepository.findByInstallationId(installationId))
                .map(Workspace::getId)
                .contains(holder.getId());
    }

    @Test
    void shouldSendSomeoneWhoInstalledFromGitHubHomeWithoutConnectingAnything() {
        URI location = browserCallback(uri -> uri.queryParam("code", "code-1")
                .queryParam("installation_id", installationId)
                .queryParam("setup_action", "install"));

        assertThat(location).hasPath("/").hasNoParameters();
        verifyNoInteractions(userAuthorization);
    }

    @Test
    void shouldResolveAnInstallationToTheWorkspaceThatHoldsItOverOneThatDisconnectedIt() {
        Workspace disconnected = workspaceOwnedBy(accountHolder("former"), "Former");
        connectInstallation(disconnected, IntegrationState.UNINSTALLED);

        assertThat(workspaceRepository.findByInstallationId(installationId))
                .map(Workspace::getId)
                .contains(disconnected.getId());

        Workspace holder = workspaceOwnedBy(accountHolder("current"), "Current");
        connectInstallation(holder, IntegrationState.ACTIVE);

        assertThat(workspaceRepository.findByInstallationId(installationId))
                .map(Workspace::getId)
                .contains(holder.getId());
    }

    private void givenOwnedByTheAuthorizingPerson() {
        when(userAuthorization.exchangeCode("code-1")).thenReturn("ghu_user");
        when(userAuthorization.findAccessibleInstallation("ghu_user", installationId))
                .thenReturn(Optional.of(new UserInstallation(
                        installationId, "Organization", new UserInstallation.Account(77L, "acme"))));
        when(userAuthorization.ownsOrganization("ghu_user", "acme")).thenReturn(true);
    }

    private URI browserCallback(UnaryOperator<UriBuilder> query) {
        URI location = webTestClient
                .get()
                .uri(uri -> query.apply(uri.path("/oauth/callback/github")).build())
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus()
                .isFound()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getLocation();
        assertThat(location).isNotNull();
        return Objects.requireNonNull(location);
    }

    private ProblemDetail jsonCallback(Workspace workspace, User caller, int status) {
        ProblemDetail problem = webTestClient
                .get()
                .uri(uri -> uri.path("/oauth/callback/github")
                        .queryParam("state", state(workspace, caller))
                        .queryParam("code", "code-1")
                        .queryParam("installation_id", installationId)
                        .queryParam("setup_action", "install")
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isEqualTo(status)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();
        assertThat(problem).isNotNull();
        return Objects.requireNonNull(problem);
    }

    private String state(Workspace workspace, User caller) {
        return oauthStateService.issue(workspace.getId(), IntegrationKind.GITHUB, accountId(caller));
    }

    private User accountHolder(String prefix) {
        User user = persistUser(prefix + "-" + System.nanoTime());
        TestUserFactory.ensureAccountForUser(accountRepository, identityLinks, user);
        return user;
    }

    private long accountId(User user) {
        Long providerId = Objects.requireNonNull(user.getProvider().getId());
        return Objects.requireNonNull(identityLinks
                .findActiveByProviderSubject(providerId, user.getNativeId().toString(), null)
                .orElseThrow()
                .getAccount()
                .getId());
    }

    private Workspace workspaceOwnedBy(User owner, String displayName) {
        String slug = "github-oauth-" + System.nanoTime();
        return createWorkspace(slug, displayName, slug, AccountType.ORG, owner);
    }

    private Connection connectInstallation(Workspace workspace, IntegrationState state) {
        Connection connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                Long.toString(installationId),
                new ConnectionConfig.GitHubAppConfig(installationId, "acme", null, Set.of()));
        connection.setState(state);
        return connectionRepository.save(connection);
    }
}
