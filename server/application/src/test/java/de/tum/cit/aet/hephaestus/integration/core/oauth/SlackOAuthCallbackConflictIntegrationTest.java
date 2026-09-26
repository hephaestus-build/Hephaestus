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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Only the outbound Slack token exchange is a double; state signing, the callback and persistence are real.
 * Shares the {@code slack-signed} context with SlackChannelAdminControllerIntegrationTest. */
@TestPropertySource(
        properties = {
            "hephaestus.integration.slack.enabled=true",
            "hephaestus.integration.slack.signing-secret=test-signing-secret",
            "hephaestus.integration.slack.redirect-uri=https://hephaestus.test/oauth/callback/slack",
        })
class SlackOAuthCallbackConflictIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String GENERIC_CONFLICT =
            "This Slack workspace is already connected to Hephaestus elsewhere; it must be disconnected there first";

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

    private String team;

    @AfterEach
    void resetSlackExchange() {
        reset(slackOAuthClient);
    }

    @BeforeEach
    void stubSlackExchange() {
        team = "T" + System.nanoTime();
        when(slackOAuthClient.exchangeCode(any(), any()))
                .thenReturn(new OAuthV2Access(
                        true, null, "xoxb-test", new OAuthV2Access.Team(team, "Intro Course"), null, null));
    }

    @Test
    void shouldHideOwnerWorkspaceWhenCallerDoesNotAdministerIt() {
        Workspace source = workspaceOwnedBy(accountHolder("source-owner"));
        connectSlack(source, IntegrationState.ACTIVE);
        User stranger = accountHolder("target-owner");
        Workspace target = workspaceOwnedBy(stranger);

        ProblemDetail problem = callback(target, accountId(stranger));

        assertThat(problem.getDetail()).isEqualTo(GENERIC_CONFLICT);
        assertThat(problem.getProperties())
                .containsExactlyInAnyOrderEntriesOf(Map.of("kind", "SLACK", "error", "transition_conflict"));
        assertThat(connectionRepository.findActive(target.getId(), IntegrationKind.SLACK))
                .isEmpty();
        assertThat(connectionRepository.findActive(source.getId(), IntegrationKind.SLACK))
                .isPresent();
    }

    @Test
    void shouldNameOwnerWorkspaceToItsAdministrator() {
        User sourceOwner = accountHolder("source-owner");
        Workspace source = workspaceOwnedBy(sourceOwner);
        connectSlack(source, IntegrationState.ACTIVE);
        Workspace target = workspaceOwnedBy(accountHolder("target-owner"));

        ProblemDetail problem = callback(target, accountId(sourceOwner));

        assertThat(problem.getDetail()).contains("already connected to workspace " + source.getId());
        assertThat(connectionRepository.findActive(target.getId(), IntegrationKind.SLACK))
                .isEmpty();
    }

    @Test
    void shouldRejectReconnectWhenTeamIsActiveInAnotherWorkspaceToo() {
        User owner = accountHolder("dup-owner");
        Workspace mine = workspaceOwnedBy(owner);
        Connection myConnection = connectSlack(mine, IntegrationState.ACTIVE);
        Workspace other = workspaceOwnedBy(accountHolder("dup-other"));
        connectSlack(other, IntegrationState.ACTIVE);

        ProblemDetail problem = callback(mine, accountId(owner));

        assertThat(problem.getDetail()).isEqualTo(GENERIC_CONFLICT);
        assertThat(connectionRepository
                        .findById(myConnection.getId())
                        .orElseThrow()
                        .getDisplayName())
                .isNull();
    }

    private ProblemDetail callback(Workspace workspace, long accountId) {
        String state = oauthStateService.issue(workspace.getId(), IntegrationKind.SLACK, Long.toString(accountId));
        ProblemDetail problem = webTestClient
                .get()
                .uri(uri -> uri.path("/oauth/callback/slack")
                        .queryParam("state", state)
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

    private Workspace workspaceOwnedBy(User owner) {
        String slug = "slack-oauth-" + System.nanoTime();
        return createWorkspace(slug, "Slack OAuth", slug, AccountType.ORG, owner);
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
