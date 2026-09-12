package de.tum.cit.aet.hephaestus.account.userview;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

class UserViewIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private WebTestClient client;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChatThreadRepository threads;

    @Autowired
    private IdentityLinkRepository identityLinks;

    private Account administrator;
    private User viewed;
    private Workspace workspace;

    @BeforeEach
    void setUpView() {
        administrator = persistInstanceAdmin("Viewing administrator");
        viewed = persistUser("never-signed-in");
        workspace = createWorkspace("acme", "Acme", viewed.getLogin(), AccountType.USER, viewed);
    }

    @Test
    void shouldListTheAccountBehindEachMemberWhenOneNeverSignedIn() {
        User signedIn = persistUser("signed-in");
        ensureWorkspaceMembership(workspace, signedIn, WorkspaceMembership.WorkspaceRole.MEMBER);
        Account account = persistAccount("Signed-in member");
        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(ensureGitHubProvider().getId()));
        link.setSubject(String.valueOf(signedIn.getNativeId()));
        link.setExternalActorId(signedIn.getId());
        identityLinks.save(link);

        request("/user-view/users")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.content[0].userId")
                .isEqualTo(viewed.getId())
                .jsonPath("$.content[0].accountId")
                .doesNotExist()
                .jsonPath("$.content[1].userId")
                .isEqualTo(signedIn.getId())
                .jsonPath("$.content[1].accountId")
                .isEqualTo(account.getId())
                .jsonPath("$.content[1].accountStatus")
                .isEqualTo("ACTIVE");
    }

    @Test
    void shouldPageHumanMembersOnlyWhenABotIsAMemberToo() {
        User bot = persistUser("automation");
        bot.setType(User.Type.BOT);
        userRepository.save(bot);
        ensureWorkspaceMembership(workspace, bot, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, persistUser("another-member"), WorkspaceMembership.WorkspaceRole.MEMBER);
        request("/user-view/users?size=1&page=1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].userId")
                .isEqualTo(viewed.getId())
                .jsonPath("$.totalPages")
                .isEqualTo(2);
    }

    @Test
    void shouldRecordTheAdministratorAndTheViewedUserWhenPracticesAreDisclosed() {
        request(viewedPath() + "/practices")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.groups")
                .isArray();
        var audit = jdbc.queryForMap(
                "SELECT account_id, acting_account_id, workspace_id, details::text AS details FROM auth_event "
                        + "WHERE event_type = 'USER_VIEW' AND viewed_user_id = ?",
                viewed.getId());
        assertThat(audit)
                .containsEntry("account_id", null)
                .containsEntry("acting_account_id", administrator.getId())
                .containsEntry("workspace_id", workspace.getId());
        assertThat(String.valueOf(audit.get("details")))
                .contains("/workspaces/acme/user-view/users/" + viewed.getId() + "/practices");
    }

    @Test
    void shouldHideAUserAndRecordNothingWhenTheyAreNotAMemberOfTheWorkspace() {
        User outsider = persistUser("outside-view");
        request("/user-view/users/" + outsider.getId() + "/practices")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        assertThat(userViewRowsFor(outsider)).isZero();
    }

    @Test
    void shouldListOnlyTheViewedUsersConversationsWhenOthersExistInAndOutsideTheWorkspace() {
        User other = persistUser("another-view-user");
        ensureWorkspaceMembership(workspace, other, WorkspaceMembership.WorkspaceRole.MEMBER);
        ChatThread own = thread(workspace, viewed, "Viewed user's conversation");
        thread(workspace, other, "Other private conversation");
        Workspace elsewhere = createWorkspace("other-view", "Other view", other.getLogin(), AccountType.USER, other);
        thread(elsewhere, viewed, "Other workspace conversation");
        request(viewedPath() + "/conversations")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].id")
                .isEqualTo(own.getId().toString());
    }

    @Test
    void shouldNotDiscloseAConversationWhenItBelongsToAnotherMember() {
        User other = persistUser("another-view-user");
        ensureWorkspaceMembership(workspace, other, WorkspaceMembership.WorkspaceRole.MEMBER);
        ChatThread foreign = thread(workspace, other, "Other private conversation");
        request(viewedPath() + "/conversations/" + foreign.getId())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldNotDiscloseAConversationWhenItBelongsToAnotherWorkspace() {
        User other = persistUser("another-view-user");
        Workspace elsewhere = createWorkspace("other-view", "Other view", other.getLogin(), AccountType.USER, other);
        ChatThread foreignWorkspace = thread(elsewhere, viewed, "Other workspace conversation");
        request(viewedPath() + "/conversations/" + foreignWorkspace.getId())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldRejectAViewAndRecordNothingWhenTheReasonIsMissing() {
        client.get()
                .uri("/workspaces/acme" + viewedPath() + "/practices")
                .headers(h -> h.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertThat(userViewRowsFor(viewed)).isZero();
    }

    @Test
    void shouldDenyPrivateContentToAWorkspaceOwnerWhoIsNotAnInstanceAdministrator() {
        Account member = persistAccount("Workspace owner");
        IdentityLink link = new IdentityLink();
        link.setAccount(member);
        link.setProviderId(Objects.requireNonNull(ensureGitHubProvider().getId()));
        link.setSubject(String.valueOf(viewed.getNativeId()));
        link.setExternalActorId(viewed.getId());
        identityLinks.save(link);

        client.get()
                .uri("/workspaces/acme" + viewedPath() + "/practices")
                .headers(h -> h.setBearerAuth("mock-jwt-member-" + member.getId()))
                .header("X-User-View-Reason", "Support")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        assertThat(userViewRowsFor(viewed)).isZero();
    }

    @Test
    void shouldNotDisclosePracticesWhenTheAuditInsertFails() {
        jdbc.execute("ALTER TABLE auth_event ADD CONSTRAINT reject_user_view_test "
                + "CHECK (event_type <> 'USER_VIEW' OR viewed_user_id <> " + viewed.getId() + ") NOT VALID");
        try {
            request(viewedPath() + "/practices")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectBody()
                    .jsonPath("$.groups")
                    .doesNotExist()
                    .jsonPath("$.status")
                    .isEqualTo(503);
            assertThat(userViewRowsFor(viewed)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE auth_event DROP CONSTRAINT reject_user_view_test");
        }
    }

    private long userViewRowsFor(User user) {
        Long rows = jdbc.queryForObject(
                "SELECT count(*) FROM auth_event WHERE event_type = 'USER_VIEW' AND viewed_user_id = ?",
                Long.class,
                user.getId());
        return rows == null ? 0 : rows;
    }

    private String token() {
        return "mock-jwt-sub-" + administrator.getId();
    }

    private String viewedPath() {
        return "/user-view/users/" + viewed.getId();
    }

    private WebTestClient.RequestHeadersSpec<?> request(String path) {
        return client.get()
                .uri("/workspaces/acme" + path)
                .headers(h -> h.setBearerAuth(token()))
                .header("X-User-View-Reason", "Investigate missing feedback");
    }

    private ChatThread thread(Workspace ownerWorkspace, User owner, String title) {
        ChatThread thread = new ChatThread();
        thread.setId(UUID.randomUUID());
        thread.setWorkspace(ownerWorkspace);
        thread.setUser(owner);
        thread.setTitle(title);
        return threads.save(thread);
    }
}
