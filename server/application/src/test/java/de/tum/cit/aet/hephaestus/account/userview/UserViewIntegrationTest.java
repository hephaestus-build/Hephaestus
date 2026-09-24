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
    void shouldUseProviderIdentityForTheListedAndAuditedAccountWhenActorCacheIsStale() {
        User other = persistUser("different-member");
        ensureWorkspaceMembership(workspace, other, WorkspaceMembership.WorkspaceRole.MEMBER);
        Account viewedAccount = persistAccount("Viewed account");
        Account otherAccount = persistAccount("Other account");
        long providerId = Objects.requireNonNull(ensureGitHubProvider().getId());

        IdentityLink viewedLink = new IdentityLink();
        viewedLink.setAccount(viewedAccount);
        viewedLink.setProviderId(providerId);
        viewedLink.setSubject(viewed.getNativeId().toString());
        viewedLink.setExternalActorId(other.getId());
        identityLinks.save(viewedLink);
        IdentityLink otherLink = new IdentityLink();
        otherLink.setAccount(otherAccount);
        otherLink.setProviderId(providerId);
        otherLink.setSubject(other.getNativeId().toString());
        otherLink.setExternalActorId(viewed.getId());
        identityLinks.save(otherLink);

        request("/user-view/users")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].userId")
                .isEqualTo(other.getId())
                .jsonPath("$.content[0].accountId")
                .isEqualTo(otherAccount.getId())
                .jsonPath("$.content[1].userId")
                .isEqualTo(viewed.getId())
                .jsonPath("$.content[1].accountId")
                .isEqualTo(viewedAccount.getId());

        request(viewedPath()).exchange().expectStatus().isOk().expectBody(Void.class);
        var audit = jdbc.queryForMap(
                "SELECT account_id, acting_account_id FROM auth_event WHERE event_type = 'USER_VIEW' AND viewed_user_id = ?",
                viewed.getId());
        assertThat(audit)
                .containsEntry("account_id", viewedAccount.getId())
                .containsEntry("acting_account_id", administrator.getId());
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
        request(viewedPath())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.userId")
                .isEqualTo(viewed.getId());
        var audit = jdbc.queryForMap(
                "SELECT account_id, acting_account_id, workspace_id, details::text AS details FROM auth_event "
                        + "WHERE event_type = 'USER_VIEW' AND viewed_user_id = ?",
                viewed.getId());
        assertThat(audit)
                .containsEntry("account_id", null)
                .containsEntry("acting_account_id", administrator.getId())
                .containsEntry("workspace_id", workspace.getId());
        assertThat(String.valueOf(audit.get("details"))).contains("/workspaces/acme/user-view/users/" + viewed.getId());
    }

    @Test
    void shouldHideAUserAndRecordNothingWhenTheyAreNotAMemberOfTheWorkspace() {
        User outsider = persistUser("outside-view");
        request("/user-view/users/" + outsider.getId())
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
        sessionRequest("/workspaces/acme/mentor/threads")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].id")
                .isEqualTo(own.getId().toString());
    }

    @Test
    void shouldNotDiscloseAConversationWhenItBelongsToAnotherMember() {
        User other = persistUser("another-view-user");
        ensureWorkspaceMembership(workspace, other, WorkspaceMembership.WorkspaceRole.MEMBER);
        ChatThread foreign = thread(workspace, other, "Other private conversation");
        sessionRequest("/workspaces/acme/mentor/threads/" + foreign.getId())
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
        sessionRequest("/workspaces/acme/mentor/threads/" + foreignWorkspace.getId())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldRejectAViewAndRecordNothingWhenTheReasonIsMissing() {
        client.get()
                .uri("/workspaces/acme" + viewedPath())
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
                .uri("/workspaces/acme" + viewedPath())
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
            request(viewedPath())
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectBody()
                    .jsonPath("$.userId")
                    .doesNotExist()
                    .jsonPath("$.status")
                    .isEqualTo(503);
            assertThat(userViewRowsFor(viewed)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE auth_event DROP CONSTRAINT reject_user_view_test");
        }
    }

    @Test
    void shouldReadTheNormalMentorRouteAsAnAccountlessUserWithoutSwitchingAuthentication() {
        ChatThread own = thread(workspace, viewed, "Viewed conversation");
        User other = persistUser("other-member");
        ensureWorkspaceMembership(workspace, other, WorkspaceMembership.WorkspaceRole.MEMBER);
        thread(workspace, other, "Other conversation");

        sessionRequest("/workspaces/acme/mentor/threads")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].id")
                .isEqualTo(own.getId().toString());
        client.get()
                .uri("/user")
                .headers(headers -> headers.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.id")
                .isEqualTo(administrator.getId());
        assertThat(userViewRowsFor(viewed)).isEqualTo(1);
    }

    @Test
    void shouldReadTheNormalProfileAsTheSelectedAccountlessUser() {
        sessionRequest("/workspaces/acme/profile/" + viewed.getLogin())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.userInfo.id")
                .isEqualTo(viewed.getId());
        assertThat(userViewRowsFor(viewed)).isEqualTo(1);
    }

    @Test
    void shouldRestrictTheNormalWorkspaceListToTheViewedWorkspace() {
        User other = persistUser("other-workspace-member");
        createWorkspace("other-view", "Other view", other.getLogin(), AccountType.USER, other);

        sessionRequest("/workspaces")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].workspaceSlug")
                .isEqualTo("acme");
        sessionRequest("/workspaces/other-view").exchange().expectStatus().isNotFound();
    }

    @Test
    void shouldRejectWritesAndUnsupportedReadsInTheNormalApp() {
        client.post()
                .uri("/workspaces/acme/mentor/chat")
                .headers(h -> h.setBearerAuth(token()))
                .header(UserViewSessionFilter.WORKSPACE_HEADER, "acme")
                .header(UserViewSessionFilter.USER_HEADER, viewed.getId().toString())
                .header(UserViewSessionFilter.REASON_HEADER, "Support")
                .exchange()
                .expectStatus()
                .isForbidden();
        sessionRequest("/user/settings").exchange().expectStatus().isForbidden();
        assertThat(userViewRowsFor(viewed)).isZero();
    }

    @Test
    void shouldNotReadTheNormalAppWhenItsAuditCannotCommit() {
        jdbc.execute("ALTER TABLE auth_event ADD CONSTRAINT reject_session_view_test "
                + "CHECK (event_type <> 'USER_VIEW' OR viewed_user_id <> " + viewed.getId() + ") NOT VALID");
        try {
            sessionRequest("/workspaces/acme/mentor/threads")
                    .exchange()
                    .expectStatus()
                    .isEqualTo(503)
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo(503);
        } finally {
            jdbc.execute("ALTER TABLE auth_event DROP CONSTRAINT reject_session_view_test");
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

    private WebTestClient.RequestHeadersSpec<?> sessionRequest(String path) {
        return client.get()
                .uri(path)
                .headers(h -> h.setBearerAuth(token()))
                .header(UserViewSessionFilter.WORKSPACE_HEADER, "acme")
                .header(UserViewSessionFilter.USER_HEADER, viewed.getId().toString())
                .header(UserViewSessionFilter.REASON_HEADER, "Support");
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
