package de.tum.cit.aet.hephaestus.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
class LeaderboardControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    @WithAdminUser
    void shouldComputeLeagueStatsWhenAnotherUserSharesTheMembersLogin() {
        // Two users with one login: the member on GitHub and a namesake on GitLab. Logins are
        // unique per provider only, which is exactly what a lookup by login alone tripped over.
        User member = persistUser("shared-login");
        persistNamesakeOnGitLab("shared-login");
        Workspace workspace =
                createWorkspace("shared-login-ws", "Shared login", "shared-login-org", AccountType.ORG, member);
        ensureWorkspaceMembership(workspace, member, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureAdminMembership(workspace);

        LeagueChangeDTO change = webTestClient
                .get()
                .uri(
                        "/workspaces/{slug}/leaderboard/users/{login}/league-stats?after={after}&before={before}",
                        workspace.getWorkspaceSlug(),
                        "shared-login",
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z"))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(LeagueChangeDTO.class)
                .returnResult()
                .getResponseBody();

        // The member and the workspace's admin are both on the padded leaderboard, so the member's
        // projected change is a placement, not zero; what matters is that it is the member's.
        assertThat(change).isNotNull();
        assertThat(change.login()).isEqualTo("shared-login");
        assertThat(change.leaguePointsChange()).isNotNull();
    }

    private User persistNamesakeOnGitLab(String login) {
        User user = new User();
        user.setNativeId(System.nanoTime());
        user.setProvider(ensureGitLabProvider());
        user.setLogin(login);
        user.setName("Namesake " + login);
        user.setAvatarUrl("https://example.com/" + login + "-gitlab.png");
        user.setHtmlUrl("https://gitlab.com/" + login);
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }
}
