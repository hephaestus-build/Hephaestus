package de.tum.cit.aet.hephaestus.integration.slack.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@TestPropertySource(
        properties = {
            "hephaestus.integration.slack.enabled=true",
            "hephaestus.integration.slack.signing-secret=test-signing-secret",
            "hephaestus.integration.slack.redirect-uri=https://hephaestus.test/oauth/callback/slack",
        })
class SlackScopesControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String TOKEN = "xoxb-scopes-test";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SlackOAuthClient slackOAuthClient;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private CredentialBundleConverter credentialConverter;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        reset(slackOAuthClient);
        User owner = persistUser("slack-scopes-owner-" + System.nanoTime());
        workspace = createWorkspace(
                "slack-scopes-" + System.nanoTime(), "Slack Scopes Test", "slack-scopes-org", AccountType.ORG, owner);
    }

    @AfterEach
    void resetSlackOAuthClient() {
        reset(slackOAuthClient);
    }

    @Test
    @WithAdminUser
    void shouldNameEachRequestedScopeThatTheTokenLacksWhenAnAdminAsks() {
        ensureAdminMembership(workspace);
        connectSlack();
        Set<String> granted = new HashSet<>(SlackConnectionStrategy.DEFAULT_SCOPES);
        granted.remove("reactions:write");
        granted.remove("assistant:write");
        granted.add("commands");
        when(slackOAuthClient.grantedScopes(TOKEN)).thenReturn(granted);

        SlackScopesDTO scopes = getScopes()
                .expectStatus()
                .isOk()
                .expectBody(SlackScopesDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(scopes).isNotNull();
        assertThat(scopes.missingScopes()).containsExactly("assistant:write", "reactions:write");
    }

    @Test
    @WithAdminUser
    void shouldAnswerWithAProblemWhenSlackRefusesTheToken() {
        ensureAdminMembership(workspace);
        connectSlack();
        when(slackOAuthClient.grantedScopes(TOKEN)).thenThrow(new SlackOAuthException("invalid_auth"));

        getScopes()
                .expectStatus()
                .isEqualTo(412)
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Slack did not complete the request")
                .jsonPath("$.detail")
                .isEqualTo("Reconnect Slack for this workspace, then try again.")
                .jsonPath("$.slackError")
                .isEqualTo("invalid_auth");
    }

    @Test
    @WithAdminUser
    void shouldAnswerWithAProblemWithoutAskingSlackWhenTheWorkspaceHasNoSlackToken() {
        ensureAdminMembership(workspace);

        getScopes()
                .expectStatus()
                .isEqualTo(412)
                .expectBody()
                .jsonPath("$.slackError")
                .isEqualTo("no_active_slack_connection");

        verify(slackOAuthClient, never()).grantedScopes(anyString());
    }

    @Test
    @WithMentorUser
    void shouldRefuseAMemberWhoIsNotAnAdmin() {
        ensureMembership(workspace, persistUser("mentor"), WorkspaceRole.MEMBER);
        connectSlack();

        getScopes().expectStatus().isForbidden().expectBody(Void.class);

        verify(slackOAuthClient, never()).grantedScopes(anyString());
    }

    private void connectSlack() {
        String team = "T" + System.nanoTime();
        Connection connection = new Connection(
                workspace, IntegrationKind.SLACK, team, new ConnectionConfig.SlackConfig(team, null, null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        connection.setCredentials(new BearerToken(TOKEN, null), credentialConverter);
        connections.saveAndFlush(connection);
    }

    private WebTestClient.ResponseSpec getScopes() {
        return webTestClient
                .get()
                .uri("/workspaces/{slug}/slack/scopes", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange();
    }
}
