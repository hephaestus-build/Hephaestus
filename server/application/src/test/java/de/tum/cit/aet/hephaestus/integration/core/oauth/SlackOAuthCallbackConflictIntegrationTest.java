package de.tum.cit.aet.hephaestus.integration.core.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.oauth.state.OAuthStateService;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient.OAuthV2Access;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Only the outbound Slack token exchange is a double; state signing, the callback and persistence are real.
 * Shares the {@code slack-signed} context with SlackChannelAdminControllerIntegrationTest. */
@TestPropertySource(
        properties = {
            "hephaestus.integration.slack.enabled=true",
            "hephaestus.integration.slack.signing-secret=test-signing-secret",
            "hephaestus.integration.slack.redirect-uri=https://hephaestus.test/oauth/callback/slack",
        })
class SlackOAuthCallbackConflictIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String GENERIC_CONFLICT = "This Slack workspace is already connected to another Hephaestus"
            + " workspace. An administrator of that workspace must disconnect Slack there first.";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private OAuthStateService oauthStateService;

    @Autowired
    private ConnectionRepository connectionRepository;

    @Autowired
    private IdentityLinkRepository identityLinks;

    @Autowired
    private SlackOAuthClient slackOAuthClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private String team;

    @BeforeEach
    void setUp() {
        // The test schema is Hibernate-generated; the one-ACTIVE-connection-per-Slack-team index is Liquibase's.
        jdbcTemplate.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_connection_one_active_slack_per_team"
                + " ON connection (instance_key) WHERE state = 'ACTIVE' AND kind = 'SLACK'");
        team = "T" + System.nanoTime();
        when(slackOAuthClient.exchangeCode(any(), any()))
                .thenReturn(new OAuthV2Access(
                        true, null, "xoxb-test", new OAuthV2Access.Team(team, "Intro Course"), null, null));
    }

    @AfterEach
    void tearDown() {
        reset(slackOAuthClient);
        jdbcTemplate.execute("DROP INDEX IF EXISTS uq_connection_one_active_slack_per_team");
    }

    @Test
    void shouldHideOwnerWorkspaceWhenCallerDoesNotAdministerIt() {
        Workspace source = workspaceOwnedBy(accountHolder("source-owner"), "Staging");
        connectSlack(source, IntegrationState.ACTIVE);
        User stranger = accountHolder("target-owner");
        Workspace target = workspaceOwnedBy(stranger, "Intro Course");

        ProblemDetail problem = jsonCallback(target, stranger);

        assertThat(problem.getDetail()).isEqualTo(GENERIC_CONFLICT);
        assertThat(problem.getProperties())
                .containsExactlyInAnyOrderEntriesOf(Map.of("kind", "SLACK", "error", "slack_team_connected_elsewhere"));
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

        URI location = webTestClient
                .get()
                .uri(uri -> uri.path("/oauth/callback/slack")
                        .queryParam("state", state(target, admin))
                        .queryParam("code", "code")
                        .build())
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
                .containsEntry("reason", "slack_team_connected_elsewhere")
                .containsEntry(
                        "description",
                        "This Slack workspace is already connected to the Hephaestus workspace \"Staging\" ("
                                + source.getWorkspaceSlug()
                                + "). Disconnect Slack there before connecting it here.");
        assertThat(connectionRepository.findActive(target.getId(), IntegrationKind.SLACK))
                .isEmpty();
    }

    /**
     * An install into {@code source} that already passed its ownership check holds the index entry uncommitted;
     * the install into {@code target} passes its own check, blocks on that entry, and fails once it commits.
     */
    @Test
    void shouldReportTheSameConflictWhenAConcurrentInstallCommitsFirst() throws Exception {
        User stranger = accountHolder("target-owner");
        Workspace source = workspaceOwnedBy(accountHolder("source-owner"), "Staging");
        Connection racing = connectSlack(source, IntegrationState.PENDING);
        Workspace target = workspaceOwnedBy(stranger, "Intro Course");

        try (java.sql.Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            try (var activate = winner.prepareStatement("UPDATE connection SET state = 'ACTIVE' WHERE id = ?")) {
                activate.setLong(1, Objects.requireNonNull(racing.getId()));
                activate.executeUpdate();
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
            assertThat(problem.getProperties()).containsEntry("error", "slack_team_connected_elsewhere");
        }
        assertThat(connectionRepository.findActive(target.getId(), IntegrationKind.SLACK))
                .isEmpty();
        assertThat(connectionRepository.findActive(source.getId(), IntegrationKind.SLACK))
                .isPresent();
    }

    private void awaitBlockedOn(String xid, CompletableFuture<?> waiter) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Objects.requireNonNull(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pg_locks WHERE locktype = 'transactionid' AND NOT granted"
                                + " AND transactionid::text = ?",
                        Long.class,
                        xid))
                == 0) {
            assertThat(waiter)
                    .as("callback finished without waiting for the concurrent install")
                    .isNotDone();
            assertThat(Instant.now()).isBefore(deadline);
            Thread.sleep(20);
        }
    }

    private ProblemDetail jsonCallback(Workspace workspace, User caller) {
        ProblemDetail problem = webTestClient
                .get()
                .uri(uri -> uri.path("/oauth/callback/slack")
                        .queryParam("state", state(workspace, caller))
                        .queryParam("code", "code")
                        .build())
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
        return oauthStateService.issue(workspace.getId(), IntegrationKind.SLACK, Long.toString(accountId(caller)));
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
        String slug = "slack-oauth-" + System.nanoTime();
        return createWorkspace(slug, displayName, slug, AccountType.ORG, owner);
    }

    private Connection connectSlack(Workspace workspace, IntegrationState state) {
        Connection connection = new Connection(
                workspace,
                IntegrationKind.SLACK,
                team,
                new ConnectionConfig.SlackConfig(team, null, null, null, null, Set.of()));
        connection.setState(state);
        return connectionRepository.save(connection);
    }
}
