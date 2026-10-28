package de.tum.cit.aet.hephaestus.account.userview;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.ActivityTargetType;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeGuidance;
import de.tum.cit.aet.hephaestus.practices.PracticeGuide;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.PracticeVisual;
import de.tum.cit.aet.hephaestus.practices.ReviewOn;
import de.tum.cit.aet.hephaestus.practices.ReviewedBy;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

class UserViewIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private PracticeVersionService versions;

    @Autowired
    private WebTestClient client;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MentorConversationService conversations;

    @Autowired
    private MentorTurnService turns;

    @Autowired
    private MentorEntryRepository entries;

    @Autowired
    private ActivityEventRepository activityEvents;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitoredRepositories;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

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
    void shouldListAnUnclaimedAccountWhenItsMemberNeverSignedIn() {
        request("/user-view/users")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.content[0].accountId")
                .isEqualTo(Long.toString(accountId(viewed)))
                .jsonPath("$.content[0].account.id")
                .isEqualTo(Long.toString(accountId(viewed)))
                .jsonPath("$.content[0].account.status")
                .isEqualTo("UNCLAIMED")
                .jsonPath("$.content[0].account.primaryEmail")
                .doesNotExist();
    }

    @Test
    void shouldRecordTheExactAccountWhenItHasNoProviderMirror() {
        var account = persistAccount("No mirror");
        workspaceMembershipService.createMembership(
                workspace, Objects.requireNonNull(account.getId()), Membership.WorkspaceRole.MEMBER);
        request("/user-view/users/" + account.getId())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.accountId")
                .isEqualTo(Long.toString(Objects.requireNonNull(account.getId())))
                .jsonPath("$.account.identities.length()")
                .isEqualTo(0);
        var audit = jdbc.queryForMap(
                "SELECT viewed_actor_id,viewed_account_id,acting_account_id FROM auth_event"
                        + " WHERE event_type='USER_VIEW' AND viewed_account_id=?",
                account.getId());
        assertThat(audit)
                .containsEntry("viewed_actor_id", null)
                .containsEntry("viewed_account_id", account.getId())
                .containsEntry("acting_account_id", administrator.getId());
    }

    @Test
    void shouldPageActiveHumanMembershipsWhenAFormerMemberIsRetained() {
        var former = persistAccount("Former member");
        var row = workspaceMembershipService.createMembership(
                workspace, Objects.requireNonNull(former.getId()), Membership.WorkspaceRole.MEMBER);
        row.setLeftAt(Instant.now());
        workspaceMembershipRepository.saveAndFlush(row);
        request("/user-view/users?size=1&page=0")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].accountId")
                .isEqualTo(Long.toString(accountId(viewed)))
                .jsonPath("$.totalElements")
                .isEqualTo(1);
    }

    @Test
    void shouldRecordTheAdministratorAndTheSelectedUserWhenTheSelectionIsConfirmed() {
        request(viewedPath())
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.accountId")
                .isEqualTo(Long.toString(accountId(viewed)));
        var audit = jdbc.queryForMap(
                "SELECT account_id, acting_account_id, workspace_id, details::text AS"
                        + " details FROM auth_event WHERE event_type = 'USER_VIEW' AND"
                        + " viewed_account_id = ?",
                accountId(viewed));
        assertThat(audit)
                .containsEntry("account_id", administrator.getId())
                .containsEntry("acting_account_id", administrator.getId())
                .containsEntry("workspace_id", workspace.getId());
        assertThat(String.valueOf(audit.get("details")))
                .contains("/workspaces/acme/user-view/users/" + accountId(viewed));
    }

    @Test
    void shouldHideAUserAndRecordNothingWhenTheyAreNotAMemberOfTheWorkspace() {
        User outsider = persistUser("outside-view");
        request("/user-view/users/" + accountId(outsider))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        assertThat(userViewRowsFor(outsider)).isZero();
    }

    @Test
    void shouldListOnlyTheViewedUsersConversationWhenOthersExistInAndOutsideTheWorkspace() {
        User other = persistUser("another-view-user");
        ensureMembership(workspace, other, Membership.WorkspaceRole.MEMBER);
        MentorTurnService.Submission own = question(workspace, viewed, "Viewed user's question");
        MentorTurnService.Submission colleagues = question(workspace, other, "Other private question");
        Workspace elsewhere = createWorkspace("other-view", "Other view", other.getLogin(), AccountType.USER, other);
        MentorTurnService.Submission foreign = question(elsewhere, viewed, "Other workspace question");

        sessionRequest("/workspaces/acme/mentor/entries")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.entries[*].id")
                .value(ids -> assertThat(ids)
                        .asInstanceOf(InstanceOfAssertFactories.LIST)
                        .containsExactly(own.question().getId().toString())
                        .doesNotContain(
                                colleagues.question().getId().toString(),
                                foreign.question().getId().toString()));
        assertThat(userViewRowsFor(viewed)).isOne();
    }

    @Test
    void shouldNotDiscloseAConversationWhenItBelongsToAnotherMember() {
        User other = persistUser("another-view-user");
        ensureMembership(workspace, other, Membership.WorkspaceRole.MEMBER);
        MentorTurnService.Submission colleagues = question(workspace, other, "Other private question");

        sessionRequest("/workspaces/acme/mentor/entries/"
                        + colleagues.question().getId() + "/thread")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldNotDiscloseAConversationWhenItBelongsToAnotherWorkspace() {
        User other = persistUser("another-view-user");
        Workspace elsewhere = createWorkspace("other-view", "Other view", other.getLogin(), AccountType.USER, other);
        MentorTurnService.Submission foreign = question(elsewhere, viewed, "Other workspace question");

        sessionRequest("/workspaces/acme/mentor/entries/" + foreign.question().getId() + "/thread")
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
        Account member = viewedUsersOwnAccount();

        client.get()
                .uri("/workspaces/acme" + viewedPath())
                .headers(h -> h.setBearerAuth("mock-jwt-member-" + member.getId()))
                .header(UserViewContextHolder.REASON_HEADER, "Support")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        assertThat(userViewRowsFor(viewed)).isZero();
    }

    @Test
    void shouldDenyNormalUserViewReadsToAWorkspaceOwnerWhoIsNotAnInstanceAdministrator() {
        Account owner = viewedUsersOwnAccount();

        sessionRequest("/workspaces/acme/practice-profile/overview", "mock-jwt-member-" + owner.getId())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(userViewRowsFor(viewed)).isZero();
    }

    @ParameterizedTest(name = "selection confirmation: {0}")
    @ValueSource(booleans = {true, false})
    void shouldDiscloseNothingWhenTheAuditInsertFails(boolean selection) {
        jdbc.execute("ALTER TABLE auth_event ADD CONSTRAINT reject_user_view_test "
                + "CHECK (event_type <> 'USER_VIEW' OR viewed_account_id <> "
                + accountId(viewed)
                + ") NOT VALID");
        try {
            (selection ? request(viewedPath()) : sessionRequest("/workspaces/acme/practice-profile/overview"))
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
    void shouldReadOwnActivityAsTheSelectedUnclaimedMember() {
        connectActivitySource(workspace, viewed.getProvider());
        recordReview(viewed);

        sessionRequest("/workspaces/acme/activity/me/work")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].people[0].id")
                .isEqualTo(viewed.getId());
        sessionRequest("/workspaces/acme/activity/me")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.person.id")
                .isEqualTo(viewed.getId());
        assertThat(userViewRowsFor(viewed)).isEqualTo(2);
    }

    @Test
    void shouldReadThePracticeVisualAndGuideWhenViewingAMember() {
        persistGuidedPractice(workspace);

        sessionRequest("/workspaces/acme/practices/guided-practice/guidance")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.visual.alt")
                .isEqualTo("Two changes")
                .jsonPath("$.guide.markdown")
                .isEqualTo("# Keep changes separate");
        assertThat(userViewRowsFor(viewed)).isEqualTo(1);
    }

    @Test
    void shouldHideGuidanceAndRecordNothingWhenThePathLeavesTheViewedWorkspace() {
        Workspace other =
                createWorkspace("other-guidance", "Other guidance", viewed.getLogin(), AccountType.USER, viewed);
        persistGuidedPractice(other);

        sessionRequest("/workspaces/other-guidance/practices/guided-practice/guidance")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        assertThat(userViewRowsFor(viewed)).isZero();
    }

    @Test
    void shouldReadTheSelectedProviderActorWhenWorkspaceMembersShareALogin() {
        User gitLabNamesake =
                TestUserFactory.ensureUser(userRepository, viewed.getLogin(), 700_002L, ensureGitLabProvider());
        ensureMembership(workspace, gitLabNamesake, Membership.WorkspaceRole.MEMBER);
        connectActivitySource(workspace, gitLabNamesake.getProvider());
        recordReview(viewed);
        recordReview(gitLabNamesake);

        sessionRequest(
                        "/workspaces/acme/activity/work?login=" + gitLabNamesake.getLogin(),
                        token(),
                        accountId(gitLabNamesake))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].people[0].id")
                .isEqualTo(gitLabNamesake.getId());
    }

    @Test
    void shouldReadATeammatesActivityAndRecordTheViewWhenViewingAMember() {
        User teammate = persistUser("teammate-activity");
        ensureMembership(workspace, teammate, Membership.WorkspaceRole.MEMBER);
        connectActivitySource(workspace, teammate.getProvider());
        recordReview(teammate);

        sessionRequest("/workspaces/acme/activity/work?login=" + teammate.getLogin())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[0].people[0].id")
                .isEqualTo(teammate.getId());
        sessionRequest("/workspaces/acme/activity/members/" + teammate.getLogin() + "/open-work")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reviewRequests.content")
                .isArray();
        assertThat(userViewRowsFor(viewed)).isEqualTo(2);
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
    }

    @Test
    void shouldHideAnotherWorkspaceAndRecordNothingWhenThePathLeavesTheViewedWorkspace() {
        User other = persistUser("other-workspace-member");
        createWorkspace("other-view", "Other view", other.getLogin(), AccountType.USER, other);

        sessionRequest("/workspaces/other-view")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        assertThat(userViewRowsFor(viewed)).isZero();
    }

    @Test
    void shouldRefuseToDeleteTheMessageAndRecordNothingWhenAnAdminViewsTheUser() {
        MentorTurnService.Submission own = question(workspace, viewed, "Viewed user's question");

        client.delete()
                .uri("/workspaces/acme/mentor/entries/" + own.question().getId())
                .headers(viewHeaders(token(), accountId(viewed)))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        assertThat(entries.findById(own.question().getId()).orElseThrow().isRedacted())
                .isFalse();
        assertThat(userViewRowsFor(viewed)).isZero();
    }

    @Test
    void shouldRefuseAnOptionsRequestCarryingTheViewAndRecordNothing() {
        client.options()
                .uri("/workspaces/acme/practice-profile/overview")
                .headers(viewHeaders(token(), accountId(viewed)))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        assertThat(userViewRowsFor(viewed)).isZero();
    }

    @Test
    void shouldRefuseAHeadRequestCarryingTheViewAndRecordNothing() {
        client.head()
                .uri("/workspaces/acme/practice-profile/overview")
                .headers(viewHeaders(token(), accountId(viewed)))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        assertThat(userViewRowsFor(viewed)).isZero();
    }

    @Test
    void shouldRefuseAReadOutsideTheViewAllowlistThatTheAdministratorMayPerformOtherwise() {
        sessionRequest("/user/features").exchange().expectStatus().isForbidden().expectBody(Void.class);
        assertThat(userViewRowsFor(viewed)).isZero();

        client.get()
                .uri("/user/features")
                .headers(h -> h.setBearerAuth(token()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    /** The viewed user owns the workspace, so the refusal is the view's, not their role's. */
    @Test
    void shouldRefuseTheWorkspaceTraceListAndRecordNothingWhenViewingItsOwner() {
        sessionRequest("/workspaces/acme/practices/trace")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(userViewRowsFor(viewed)).isZero();
    }

    @Test
    void shouldRecordTheReasonAndTheReadWithItsQueryWhenANormalAppReadIsViewed() {
        sessionRequest("/workspaces/acme/practice-profile/overview?page=0")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        String details = jdbc.queryForObject(
                "SELECT details::text FROM auth_event WHERE event_type = 'USER_VIEW' AND" + " viewed_account_id = ?",
                String.class,
                accountId(viewed));
        assertThat(details).contains("Support").contains("/workspaces/acme/practice-profile/overview?page=0");
    }

    @Test
    void shouldReportTheViewedMembersOwnRoleWhenTheAdministratorViewsAMember() {
        User member = persistUser("plain-member");
        ensureMembership(workspace, member, Membership.WorkspaceRole.MEMBER);

        sessionRequest("/workspaces/acme/members/me", token(), accountId(member))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.account.id")
                .isEqualTo(Long.toString(accountId(member)))
                .jsonPath("$.role")
                .isEqualTo("MEMBER");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"not-an-account", "0", "-1", "+1", "01", "9223372036854775808"})
    void shouldRejectAViewAndRecordNothingWhenTheViewedUserIsNotAnId(@Nullable String userHeader) {
        client.get()
                .uri("/workspaces/acme/practice-profile/overview")
                .headers(headers -> {
                    headers.setBearerAuth(token());
                    headers.set(UserViewContextHolder.WORKSPACE_HEADER, "acme");
                    headers.set(UserViewContextHolder.REASON_HEADER, "Support");
                    if (userHeader != null) {
                        headers.set(UserViewContextHolder.ACCOUNT_HEADER, userHeader);
                    }
                })
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertThat(userViewRowsBy(administrator)).isZero();
    }

    @Test
    void shouldServeEveryAllowedViewReadWithAGetHandler() {
        var getRoutes = mappings.getHandlerMethods().keySet().stream()
                .filter(mapping -> mapping.getMethodsCondition().getMethods().contains(RequestMethod.GET))
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .collect(Collectors.toSet());

        assertThat(UserViewSessionFilter.READ_PATHS)
                .extracting(PathPattern::getPatternString)
                .allSatisfy(path -> assertThat(getRoutes).contains(path));
    }

    private long userViewRowsFor(User user) {
        Long rows = jdbc.queryForObject(
                "SELECT count(*) FROM auth_event WHERE event_type = 'USER_VIEW' AND" + " viewed_account_id = ?",
                Long.class,
                accountId(user));
        return rows == null ? 0 : rows;
    }

    /** The workspace owner signing in as themselves: a member, not an instance administrator. */
    private Account viewedUsersOwnAccount() {
        var account = accountRepository.findById(accountId(viewed)).orElseThrow();
        account.setStatus(Account.Status.ACTIVE);
        return accountRepository.saveAndFlush(account);
    }

    private long userViewRowsBy(Account account) {
        Long rows = jdbc.queryForObject(
                "SELECT count(*) FROM auth_event WHERE event_type = 'USER_VIEW' AND" + " acting_account_id = ?",
                Long.class,
                account.getId());
        return rows == null ? 0 : rows;
    }

    private String token() {
        return "mock-jwt-sub-" + administrator.getId();
    }

    private String viewedPath() {
        return "/user-view/users/" + accountId(viewed);
    }

    private WebTestClient.RequestHeadersSpec<?> request(String path) {
        return client.get()
                .uri("/workspaces/acme" + path)
                .headers(h -> h.setBearerAuth(token()))
                .header(UserViewContextHolder.REASON_HEADER, "Investigate missing feedback");
    }

    private WebTestClient.RequestHeadersSpec<?> sessionRequest(String path) {
        return sessionRequest(path, token());
    }

    private WebTestClient.RequestHeadersSpec<?> sessionRequest(String path, String bearer) {
        return sessionRequest(path, bearer, accountId(viewed));
    }

    private WebTestClient.RequestHeadersSpec<?> sessionRequest(String path, String bearer, long userId) {
        return client.get().uri(path).headers(viewHeaders(bearer, userId));
    }

    private static Consumer<HttpHeaders> viewHeaders(String bearer, long userId) {
        return headers -> {
            headers.setBearerAuth(bearer);
            headers.set(UserViewContextHolder.WORKSPACE_HEADER, "acme");
            headers.set(UserViewContextHolder.ACCOUNT_HEADER, String.valueOf(userId));
            headers.set(UserViewContextHolder.REASON_HEADER, "Support");
        };
    }

    /** A question {@code author} asked Heph in {@code where}, with its queued turn. */
    private MentorTurnService.Submission question(Workspace where, User author, String text) {
        MentorConversation main = conversations.findOrCreateMain(where.getId(), accountId(author));
        return turns.submit(
                where.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null));
    }

    /** One review event by {@code actor} in a connected repository of the viewed workspace. */
    private void recordReview(User actor) {
        UUID id = UUID.randomUUID();
        Repository repository = new Repository();
        repository.setProvider(actor.getProvider());
        repository.setNativeId(id.getMostSignificantBits());
        repository.setName("reviews-" + id);
        repository.setNameWithOwner("acme/" + repository.getName());
        repository.setHtmlUrl("https://example.com/" + repository.getNameWithOwner());
        repository.setDefaultBranch("main");
        repository = repositories.save(repository);
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(repository.getNameWithOwner());
        monitoredRepositories.save(monitor);
        activityEvents.insertIfAbsent(
                id,
                "review-" + id,
                ActivityEventType.REVIEW_APPROVED.name(),
                Instant.now().minusSeconds(60),
                actor.getId(),
                workspace.getId(),
                repository.getId(),
                ActivityTargetType.REVIEW.getValue(),
                id.getMostSignificantBits());
    }

    private void persistGuidedPractice(Workspace ownerWorkspace) {
        var definition = new PracticeDefinition(
                "Separate changes",
                new ReviewOn(List.of(), Map.of()),
                null,
                AboutRole.AUTHOR,
                new PracticeReads(
                        "3.0.0",
                        List.of(new PracticeReadSource(new SourceKind("scm.pull-request.core"), ReadNeed.REQUIRED))),
                new ReviewedBy(ReviewedByKind.HEPHAESTUS, null, List.of()),
                "Keep each change focused.",
                null,
                null,
                false,
                new PracticeGuidance(
                        null,
                        null,
                        null,
                        new PracticeVisual(
                                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 640 320\"/>", "Two changes"),
                        new PracticeGuide("# Keep changes separate", Map.of())));
        versions.create(new PracticeVersionService.NewPractice(
                ownerWorkspace, "guided-practice", new ItemKind("scm.pull_request"), definition, null, 0, null));
    }
}
