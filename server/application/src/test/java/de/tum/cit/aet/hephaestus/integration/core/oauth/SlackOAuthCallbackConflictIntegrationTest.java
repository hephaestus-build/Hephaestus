package de.tum.cit.aet.hephaestus.integration.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient.OAuthV2Access;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthException;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;

@TestPropertySource(
        properties = {
            "hephaestus.integration.slack.enabled=true",
            "hephaestus.integration.slack.signing-secret=test-signing-secret",
        })
class SlackOAuthCallbackConflictIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String GENERIC_CONFLICT = "This Slack workspace is already connected to another Hephaestus"
            + " workspace. A workspace admin there must disconnect Slack first.";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private OAuthStateService oauthStateService;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private IdentityRepository identityLinks;

    @Autowired
    private SlackOAuthClient slackOAuthClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private String team;

    @BeforeEach
    void setUp() {
        team = "T" + System.nanoTime();
        when(slackOAuthClient.botIdentity(any()))
                .thenReturn(new SlackOAuthClient.BotIdentity(true, team, "U-BOT", null));
        when(slackOAuthClient.exchangeCode(any(), any()))
                .thenReturn(new OAuthV2Access(
                        true, null, "xoxb-test", new OAuthV2Access.Team(team, "Intro Course"), null, null));
    }

    @AfterEach
    void tearDown() {
        reset(slackOAuthClient);
    }

    @Test
    void shouldHideOwnerWorkspaceWhenCallerDoesNotAdministerIt() {
        Workspace source = workspaceOwnedBy(accountHolder("source-owner"), "Staging");
        connectSlack(source, IntegrationState.ACTIVE);
        User stranger = accountHolder("target-owner");
        Workspace target = workspaceOwnedBy(stranger, "Intro Course");

        ProblemDetail problem = jsonCallback(target, stranger);

        assertThat(problem.getDetail()).isEqualTo(GENERIC_CONFLICT);
        // The error code and the request reference that every problem carries; nothing names the owner's workspace.
        assertThat(problem.getProperties())
                .containsOnlyKeys("callbackError", "requestId")
                .containsEntry("callbackError", "connected_elsewhere");
        assertThat(connectionRepository.findActive(target.getId(), IntegrationKind.SLACK))
                .isEmpty();
        assertThat(connectionRepository.findActive(source.getId(), IntegrationKind.SLACK))
                .isPresent();
    }

    @Test
    void shouldNameOwnerWorkspaceToItsAdministratorOnTheFailureRedirect() {
        User admin = accountHolder("admin");
        Workspace source = workspaceOwnedBy(admin, "Staging");
        connectSlack(source, IntegrationState.ACTIVE);
        Workspace target = workspaceOwnedBy(admin, "Intro Course");

        String issued = state(target, admin);

        URI location = webTestClient
                .get()
                .uri(uri -> uri.path("/oauth/callback/slack")
                        .queryParam("state", issued)
                        .queryParam("code", "code")
                        .build())
                .cookie(OAuthBrowserBinding.cookieName(issued), OAuthBrowserBinding.cookieValue(issued))
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus()
                .isFound()
                .returnResult(Void.class)
                .getResponseHeaders()
                .getLocation();

        assertThat(location).isNotNull();
        Map<String, String> query = new HashMap<>();
        UriComponentsBuilder.fromUri(location)
                .build()
                .getQueryParams()
                .forEach((name, values) ->
                        query.put(name, URLDecoder.decode(values.getFirst(), StandardCharsets.UTF_8)));
        assertThat(query)
                .containsEntry("status", "error")
                .containsEntry("reason", "connected_elsewhere")
                .containsEntry(
                        "description",
                        "This Slack workspace is already connected to the Hephaestus workspace"
                                + " \"Staging\" ("
                                + source.getWorkspaceSlug()
                                + "). Disconnect Slack there before you connect it here.");
        assertThat(connectionRepository.findActive(target.getId(), IntegrationKind.SLACK))
                .isEmpty();
    }

    @Test
    void shouldReportTheSameConflictWhenAConcurrentInstallCommitsFirst() throws Exception {
        User stranger = accountHolder("target-owner");
        Workspace source = workspaceOwnedBy(accountHolder("source-owner"), "Staging");
        Connection racing = connectSlack(source, IntegrationState.PENDING);
        Workspace target = workspaceOwnedBy(stranger, "Intro Course");

        try (java.sql.Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            // This fixture must retain the JDBC transaction ID for the conflict barrier below. Model the legal
            // PENDING -> ACTIVE change, including both generation and optimistic-lock version, on that connection.
            try (var activate = winner.prepareStatement(
                    "UPDATE connection SET state = 'ACTIVE', lifecycle_generation = lifecycle_generation + 1, version = version + 1 WHERE id = ? AND workspace_id = ? AND state = 'PENDING'")) {
                activate.setLong(1, Objects.requireNonNull(racing.getId()));
                activate.setLong(2, source.getId());
                assertThat(activate.executeUpdate()).isEqualTo(1);
            }
            String winnerXid;
            try (var xid = winner.createStatement();
                    var rs = xid.executeQuery("SELECT pg_current_xact_id()::xid::text")) {
                rs.next();
                winnerXid = rs.getString(1);
            }

            CompletableFuture<ProblemDetail> loser =
                    CompletableFuture.supplyAsync(() -> jsonCallback(target, stranger));
            awaitBlockedOn(winnerXid, loser);
            winner.commit();

            ProblemDetail problem = loser.get(30, TimeUnit.SECONDS);
            assertThat(problem.getDetail()).isEqualTo(GENERIC_CONFLICT);
            assertThat(problem.getProperties()).containsEntry("callbackError", "connected_elsewhere");
        }
        assertThat(connectionRepository.findActive(target.getId(), IntegrationKind.SLACK))
                .isEmpty();
        assertThat(connectionRepository.findActive(source.getId(), IntegrationKind.SLACK))
                .isPresent();
    }

    @ParameterizedTest
    @EnumSource(
            value = IntegrationState.class,
            names = {"ACTIVE", "SUSPENDED"})
    void shouldKeepTheWorkspaceSlackSettingsWhenItsTeamIsReauthorized(IntegrationState state) {
        User admin = accountHolder("admin");
        Workspace workspace = workspaceOwnedBy(admin, "Intro Course");
        Connection existing = connectSlack(workspace, state, 90, Set.of("messages"));

        Connection refreshed = reauthorize(workspace, admin, existing);

        assertThat(refreshed.getConfig())
                .isEqualTo(new ConnectionConfig.SlackConfig(
                        team,
                        "U-BOT",
                        ProviderResourceAdapter.slackTokenFingerprint("xoxb-test"),
                        "Intro Course",
                        90,
                        Set.of("messages")));
    }

    @Test
    void shouldStartFromTheProviderDefaultsWhenAnUninstalledTeamIsReauthorized() {
        User admin = accountHolder("admin");
        Workspace workspace = workspaceOwnedBy(admin, "Intro Course");
        Connection existing = connectSlack(workspace, IntegrationState.UNINSTALLED, 90, Set.of("messages"));

        Connection revived = reauthorize(workspace, admin, existing);

        assertThat(revived.getConfig())
                .isEqualTo(new ConnectionConfig.SlackConfig(
                        team,
                        "U-BOT",
                        ProviderResourceAdapter.slackTokenFingerprint("xoxb-test"),
                        "Intro Course",
                        null,
                        Set.of()));
    }

    @Test
    void shouldRefuseAKnownDifferentTeamBeforeExchangingItsCodeAndCreateNoBotUserFence() {
        User admin = accountHolder("different-team");
        Workspace workspace = workspaceOwnedBy(admin, "Existing Slack team");
        connectSlack(workspace, IntegrationState.ACTIVE);
        String differentTeam = team + "-different";
        String issued = state(workspace, admin);
        webTestClient
                .get()
                .uri(uri -> uri.path("/oauth/callback/slack")
                        .queryParam("state", issued)
                        .queryParam("code", "code")
                        .queryParam("team_id", differentTeam)
                        .build())
                .cookie(OAuthBrowserBinding.cookieName(issued), OAuthBrowserBinding.cookieValue(issued))
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        verifyNoInteractions(slackOAuthClient);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM provider_resource_repair WHERE kind = 'SLACK' AND (parent_id = ? OR observed_team_id = ?)",
                        Long.class,
                        differentTeam,
                        differentTeam))
                .isZero();
    }

    @Test
    void shouldNotRevealAnotherWorkspaceFromAnUnverifiedTeamHint() {
        Workspace source = workspaceOwnedBy(accountHolder("hint-source"), "Source");
        connectSlack(source, IntegrationState.ACTIVE);
        User stranger = accountHolder("hint-target");
        Workspace target = workspaceOwnedBy(stranger, "Target");
        when(slackOAuthClient.exchangeCode(any(), any())).thenThrow(new SlackOAuthException("invalid_code"));
        String issued = state(target, stranger);
        webTestClient
                .get()
                .uri(uri -> uri.path("/oauth/callback/slack")
                        .queryParam("state", issued)
                        .queryParam("code", "invalid")
                        .queryParam("team_id", team)
                        .build())
                .cookie(OAuthBrowserBinding.cookieName(issued), OAuthBrowserBinding.cookieValue(issued))
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.callbackError")
                .isEqualTo("finalize_failed");
    }

    private Connection reauthorize(Workspace workspace, User admin, Connection existing) {
        String issued = state(workspace, admin);
        webTestClient
                .get()
                .uri(uri -> uri.path("/oauth/callback/slack")
                        .queryParam("state", issued)
                        .queryParam("code", "code")
                        .build())
                .cookie(OAuthBrowserBinding.cookieName(issued), OAuthBrowserBinding.cookieValue(issued))
                .accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus()
                .isFound()
                .expectBody(Void.class);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT id FROM connection WHERE workspace_id = ? AND kind =" + " 'SLACK'",
                        Long.class,
                        workspace.getId()))
                .containsExactly(existing.getId());
        Connection connection = connectionRepository
                .findById(Objects.requireNonNull(existing.getId()))
                .orElseThrow();
        assertThat(connection.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(connection.hasCredentials()).isTrue();
        return connection;
    }

    private void awaitBlockedOn(String xid, CompletableFuture<?> waiter) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Objects.requireNonNull(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pg_locks WHERE locktype = 'transactionid' AND"
                                + " NOT granted AND transactionid::text = ?",
                        Long.class,
                        xid))
                == 0) {
            assertThat(waiter)
                    .as("callback finished without waiting for the concurrent install")
                    .isNotDone();
            assertThat(Instant.now()).isBefore(deadline);
            Thread.sleep(20); // NOPMD - proves over this window that the callback keeps waiting for the concurrent install
        }
    }

    private ProblemDetail jsonCallback(Workspace workspace, User caller) {
        String issued = state(workspace, caller);
        ProblemDetail problem = webTestClient
                .get()
                .uri(uri -> uri.path("/oauth/callback/slack")
                        .queryParam("state", issued)
                        .queryParam("code", "code")
                        .build())
                .cookie(OAuthBrowserBinding.cookieName(issued), OAuthBrowserBinding.cookieValue(issued))
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(ProblemDetail.class)
                .returnResult()
                .getResponseBody();
        assertThat(problem).isNotNull();
        return problem;
    }

    private String state(Workspace workspace, User caller) {
        return oauthStateService.issue(workspace.getId(), IntegrationKind.SLACK, accountId(caller));
    }

    private User accountHolder(String prefix) {
        User user = persistUser(prefix + "-" + System.nanoTime());
        TestUserFactory.ensureAccountForUser(accountRepository, identityLinks, user);
        return user;
    }

    private Workspace workspaceOwnedBy(User owner, String displayName) {
        String slug = "slack-oauth-" + System.nanoTime();
        return createWorkspace(slug, displayName, slug, AccountType.ORG, owner);
    }

    private Connection connectSlack(Workspace workspace, IntegrationState state) {
        return connectSlack(workspace, state, null, Set.of());
    }

    private Connection connectSlack(
            Workspace workspace, IntegrationState state, @Nullable Integer retentionDays, Set<String> streams) {
        Connection connection = new Connection(
                workspace,
                IntegrationKind.SLACK,
                team,
                new ConnectionConfig.SlackConfig(team, null, retentionDays, streams));
        ConnectionFixtures.reachState(connection, state);
        return connectionRepository.save(connection);
    }
}
