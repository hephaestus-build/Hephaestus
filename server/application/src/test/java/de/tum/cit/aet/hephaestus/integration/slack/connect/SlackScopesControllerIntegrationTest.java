package de.tum.cit.aet.hephaestus.integration.slack.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackSendException;
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

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SlackMessageService slackMessageService;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        reset(slackMessageService);
        User owner = persistUser("slack-scopes-owner-" + System.nanoTime());
        workspace = createWorkspace(
                "slack-scopes-" + System.nanoTime(), "Slack Scopes Test", "slack-scopes-org", AccountType.ORG, owner);
    }

    @AfterEach
    void resetSlackMessageService() {
        reset(slackMessageService);
    }

    @Test
    @WithAdminUser
    void shouldNameEachRequestedScopeThatTheTokenLacksWhenAnAdminAsks() {
        ensureAdminMembership(workspace);
        Set<String> granted = new HashSet<>(SlackConnectionStrategy.DEFAULT_SCOPES);
        granted.remove("reactions:write");
        granted.remove("assistant:write");
        granted.add("commands");
        when(slackMessageService.grantedScopes(workspace.getId())).thenReturn(granted);

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
    void shouldAnswerWithAProblemWhenSlackCannotNameTheScopes() {
        ensureAdminMembership(workspace);
        when(slackMessageService.grantedScopes(workspace.getId()))
                .thenThrow(new SlackSendException(workspace.getId(), "", "no_active_slack_connection"));

        getScopes()
                .expectStatus()
                .isEqualTo(412)
                .expectBody()
                .jsonPath("$.title")
                .isEqualTo("Slack did not complete the request")
                .jsonPath("$.detail")
                .isEqualTo("Reconnect Slack for this workspace, then try again.")
                .jsonPath("$.slackError")
                .isEqualTo("no_active_slack_connection");
    }

    @Test
    @WithMentorUser
    void shouldRefuseAMemberWhoIsNotAnAdmin() {
        ensureMembership(workspace, persistUser("mentor"), WorkspaceRole.MEMBER);

        getScopes().expectStatus().isForbidden().expectBody(Void.class);

        verify(slackMessageService, never()).grantedScopes(anyLong());
    }

    private WebTestClient.ResponseSpec getScopes() {
        return webTestClient
                .get()
                .uri("/workspaces/{slug}/slack/scopes", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange();
    }
}
