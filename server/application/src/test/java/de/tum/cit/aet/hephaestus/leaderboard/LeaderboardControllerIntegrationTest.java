package de.tum.cit.aet.hephaestus.leaderboard;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.activity.ActivityEvent;
import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

@Tag("integration")
class LeaderboardControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ActivityEventRepository activityEvents;

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

    @Test
    void shouldUseTheSelectedActorForLeagueStatsWhenWorkspaceMembersShareALogin() {
        User first = persistUser("same-login");
        User second = persistNamesakeOnGitLab("same-login");
        Workspace workspace = createWorkspace("viewed-league", "Viewed league", "viewed-org", AccountType.ORG, first);
        WorkspaceMembership firstMember =
                ensureWorkspaceMembership(workspace, first, WorkspaceMembership.WorkspaceRole.MEMBER);
        WorkspaceMembership secondMember =
                ensureWorkspaceMembership(workspace, second, WorkspaceMembership.WorkspaceRole.MEMBER);
        firstMember.setLeaguePoints(100);
        secondMember.setLeaguePoints(1000);
        workspaceMembershipRepository.saveAndFlush(firstMember);
        workspaceMembershipRepository.saveAndFlush(secondMember);
        Instant activityTime = Instant.parse("2026-08-15T00:00:00Z");
        activityEvents.saveAndFlush(ActivityEvent.builder()
                .id(UUID.randomUUID())
                .eventKey("viewed-league-second")
                .eventType(ActivityEventType.PULL_REQUEST_OPENED)
                .occurredAt(activityTime)
                .actor(second)
                .workspace(workspace)
                .xp(200.0)
                .build());
        var admin = persistInstanceAdmin("League view admin");

        Long adminId = Objects.requireNonNull(admin.getId());
        LeagueChangeDTO firstChange = viewedLeagueStats(workspace, first, adminId);
        LeagueChangeDTO secondChange = viewedLeagueStats(workspace, second, adminId);

        assertThat(firstChange.leaguePointsChange()).isNotEqualTo(secondChange.leaguePointsChange());
    }

    private LeagueChangeDTO viewedLeagueStats(Workspace workspace, User viewed, Long adminId) {
        return Objects.requireNonNull(webTestClient
                .get()
                .uri(
                        "/workspaces/{slug}/leaderboard/users/{login}/league-stats?after={after}&before={before}",
                        workspace.getWorkspaceSlug(),
                        viewed.getLogin(),
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z"))
                .headers(headers -> headers.setBearerAuth("mock-jwt-sub-" + adminId))
                .header("X-User-View-Workspace", workspace.getWorkspaceSlug())
                .header("X-User-View-User", viewed.getId().toString())
                .header("X-User-View-Reason", "Check league stats")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(LeagueChangeDTO.class)
                .returnResult()
                .getResponseBody());
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
