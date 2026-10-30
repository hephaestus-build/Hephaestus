package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.web.PageResponseDTO;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.dto.AssignRoleRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.SetMemberHiddenRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceMembershipDTO;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.hibernate.SessionFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

class MembershipControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private TeamMembershipRepository teamMemberships;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Test
    @WithAdminUser
    void shouldShowAnAdminWhoHasNotAnsweredThePublicActivityQuestionOnlyWhereTheWorkspacePublishes() {
        User owner = persistUser("public-answer-owner");
        Workspace workspace =
                createWorkspace("public-answers", "Public answers", "public-answers", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        User answered = persistUser("public-answer-answered");
        User unanswered = persistUser("public-answer-unanswered");
        User former = persistUser("public-answer-former");
        User reader = persistUser("mentor");
        for (User member : List.of(answered, unanswered, former, reader)) {
            ensureMembership(workspace, member, WorkspaceRole.MEMBER);
        }
        var seen = ensureMembership(workspace, answered, WorkspaceRole.MEMBER);
        seen.setPublicActivitySeen(true);
        workspaceMembershipRepository.saveAndFlush(seen);
        var left = ensureMembership(workspace, former, WorkspaceRole.MEMBER);
        left.setLeftAt(left.getJoinedAt());
        workspaceMembershipRepository.saveAndFlush(left);

        var unpublished = publicActivityAnswered(workspace, TestAuthUtils.withCurrentUser());
        assertThat(unpublished.get(Long.toString(accountId(answered)))).isNull();
        assertThat(unpublished.get(Long.toString(accountId(unanswered)))).isNull();

        jdbc.update("INSERT INTO instance_settings (id, silent_mode_engaged, version, public_activity_allowed) VALUES"
                + " (1,true,0,true) ON CONFLICT (id) DO UPDATE SET public_activity_allowed=true");
        workspace.setPublicActivityEnabled(true);
        workspaceRepository.saveAndFlush(workspace);

        var asAdmin = publicActivityAnswered(workspace, TestAuthUtils.withCurrentUser());
        assertThat(asAdmin.get(Long.toString(accountId(answered)))).isTrue();
        assertThat(asAdmin.get(Long.toString(accountId(unanswered)))).isFalse();
        assertThat(asAdmin.get(Long.toString(accountId(former)))).isNull();

        // A member does not learn who answered.
        var asMember =
                publicActivityAnswered(workspace, headers -> headers.setBearerAuth("mock-jwt-token-for-mentor-user"));
        assertThat(asMember.get(Long.toString(accountId(answered)))).isNull();
        assertThat(asMember.get(Long.toString(accountId(unanswered)))).isNull();
    }

    /** Whether each listed member answered, by account id; a missing fact maps to null. */
    private Map<String, @Nullable Boolean> publicActivityAnswered(Workspace workspace, Consumer<HttpHeaders> caller) {
        var page = Objects.requireNonNull(webTestClient
                .get()
                .uri("/workspaces/{slug}/members?size=50", workspace.getWorkspaceSlug())
                .headers(caller)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<PageResponseDTO<WorkspaceMembershipDTO>>() {})
                .returnResult()
                .getResponseBody());
        Map<String, @Nullable Boolean> answers = new HashMap<>();
        for (var member : page.content()) answers.put(member.account().id(), member.publicActivityAnswered());
        return answers;
    }

    @Test
    @WithAdminUser
    void shouldFilterAndSortMemberPagesWithinTheConnectedTeamNamespace() {
        var owner = persistUser("member-search-owner");
        var workspace = createWorkspace("member-search", "Members", "member-search-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        connectActivitySource(workspace, owner.getProvider());
        var alpha = persistUser("zulu-login");
        alpha.setName("Searchable Alpha");
        userRepository.saveAndFlush(alpha);
        var beta = persistUser("alpha-login");
        beta.setName("Searchable Beta");
        userRepository.saveAndFlush(beta);
        ensureMembership(workspace, alpha, WorkspaceRole.MEMBER);
        ensureMembership(workspace, beta, WorkspaceRole.MEMBER);
        var team = memberTeam("own", workspace.getAccountLogin(), owner.getProvider(), 900_001);
        var foreignTeam = memberTeam("foreign", "another-organization", owner.getProvider(), 900_002);
        for (var actor : List.of(alpha, beta)) {
            teamMemberships.saveAndFlush(new TeamMembership(team, actor, TeamMembership.Role.MEMBER));
            teamMemberships.saveAndFlush(new TeamMembership(foreignTeam, actor, TeamMembership.Role.MEMBER));
        }
        for (int page = 0; page < 2; page++) {
            webTestClient
                    .get()
                    .uri(
                            "/workspaces/{slug}/members?q=SEARCHABLE&team={team}&size=1&page={page}&sort=username,asc",
                            workspace.getWorkspaceSlug(),
                            team.getId(),
                            page)
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.totalElements")
                    .isEqualTo(2)
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].account.id")
                    .isEqualTo(Long.toString(accountId(page == 0 ? beta : alpha)));
        }
        webTestClient
                .get()
                .uri("/workspaces/{slug}/members?q=SEARCHABLE&size=1&sort=name,desc", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                .jsonPath("$.content[0].account.id")
                .isEqualTo(Long.toString(accountId(beta)));
        webTestClient
                .get()
                .uri("/workspaces/{slug}/members?team={team}", workspace.getWorkspaceSlug(), foreignTeam.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(0);
    }

    @Test
    @WithAdminUser
    void shouldSortMemberPagesByTheDisplayedNameIncludingProviderLoginFallback() {
        var owner = persistUser("display-order-owner");
        var workspace = createWorkspace("display-order", "Members", "display-order", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        connectActivitySource(workspace, owner.getProvider());
        var fallback = persistUser("alpha-fallback");
        fallback.setName(null);
        userRepository.saveAndFlush(fallback);
        var first = persistUser("display-order-aaron");
        var last = persistUser("display-order-bravo");
        for (var actor : List.of(fallback, first, last)) ensureMembership(workspace, actor, WorkspaceRole.MEMBER);
        for (var entry : Map.of(fallback, " ", first, "Aaron", last, "Bravo").entrySet()) {
            var account = accounts.findById(accountId(entry.getKey())).orElseThrow();
            account.setDisplayName(entry.getValue());
            accounts.saveAndFlush(account);
        }
        var team = memberTeam("display-order", workspace.getAccountLogin(), owner.getProvider(), 900_003);
        for (var actor : List.of(fallback, first, last))
            teamMemberships.saveAndFlush(new TeamMembership(team, actor, TeamMembership.Role.MEMBER));
        webTestClient
                .get()
                .uri(
                        "/workspaces/{slug}/members?team={team}&size=1&page=1&sort=name,asc",
                        workspace.getWorkspaceSlug(),
                        team.getId())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(3)
                .jsonPath("$.content[0].account.id")
                .isEqualTo(Long.toString(accountId(fallback)))
                .jsonPath("$.content[0].account.status")
                .isEqualTo("UNCLAIMED")
                .jsonPath("$.content[0].account.identities[0].login")
                .isEqualTo("alpha-fallback");
    }

    private Team memberTeam(String slug, String organization, IdentityProvider provider, long nativeId) {
        var team = new Team();
        team.setProvider(provider);
        team.setNativeId(nativeId);
        team.setSlug(slug);
        team.setName(slug);
        team.setOrganization(organization);
        team.setHtmlUrl("https://github.com/orgs/" + organization + "/teams/" + slug);
        return teams.saveAndFlush(team);
    }

    @Test
    @WithAdminUser
    void shouldKeepProviderProfileQueriesBoundedForFiftyMembers() {
        var owner = persistUser("profile-batch-owner");
        var workspace = createWorkspace("profile-batch", "Profiles", "profiles", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        connectActivitySource(workspace, owner.getProvider());
        var gitlab = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.com"));
        for (int index = 0; index < 50; index++) {
            var member = persistUser("profile-batch-" + index);
            ensureMembership(workspace, member, WorkspaceRole.MEMBER);
            var secondProfile = userRepository.saveAndFlush(
                    TestUserFactory.createUser(900_000 + index, "gitlab-profile-" + index, gitlab));
            var identity = new Identity();
            var account = accounts.findById(accountId(member)).orElseThrow();
            account.setStatus(Account.Status.ACTIVE);
            accounts.saveAndFlush(account);
            identity.setAccount(account);
            identity.setProviderId(Objects.requireNonNull(gitlab.getId()));
            identity.setProviderUserKey(secondProfile.getNativeId().toString());
            identities.saveAndFlush(identity);
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            var counts = new ArrayList<Long>();
            for (int size : List.of(10, 50)) {
                long before = statistics.getPrepareStatementCount();
                webTestClient
                        .get()
                        .uri("/workspaces/{slug}/members?size={size}", workspace.getWorkspaceSlug(), size)
                        .headers(TestAuthUtils.withCurrentUser())
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody()
                        .jsonPath("$.content.length()")
                        .isEqualTo(size);
                counts.add(statistics.getPrepareStatementCount() - before);
            }
            assertThat(counts.getLast() - counts.getFirst()).isLessThanOrEqualTo(2);
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    @Test
    @WithAdminUser
    void shouldReturnNotFoundWhenTheWorkspaceDoesNotExist() {
        webTestClient
                .get()
                .uri("/workspaces/absent-membership-space/members")
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(404);
    }

    @Test
    @WithAdminUser
    void shouldReturnNotFoundWhenAssigningAnUnknownAccount() {
        var owner = persistUser("missing-account-owner");
        var workspace = createWorkspace("missing-account-space", "Members", "members", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        webTestClient
                .post()
                .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AssignRoleRequestDTO(Long.toString(Long.MAX_VALUE), WorkspaceRole.MEMBER))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(404);
    }

    @Test
    @WithAdminUser
    void shouldRejectAnUnknownMemberSortField() {
        var owner = persistUser("invalid-sort-owner");
        var workspace = createWorkspace("invalid-member-sort", "Members", "members", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        webTestClient
                .get()
                .uri("/workspaces/{slug}/members?sort=missingField", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    @WithAdminUser
    void listMembersReturnsAllWorkspaceMembersForAdmin() {
        User owner = persistUser("membership-owner");
        Workspace workspace =
                createWorkspace("membership-space", "Membership Space", "membership", AccountType.ORG, owner);

        var admin = ensureAdminMembership(workspace);

        User member = persistUser("membership-member");
        workspaceMembershipService.createMembership(workspace, accountId(member), WorkspaceRole.MEMBER);

        PageResponseDTO<WorkspaceMembershipDTO> response = webTestClient
                .get()
                .uri("/workspaces/{slug}/members", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<PageResponseDTO<WorkspaceMembershipDTO>>() {})
                .returnResult()
                .getResponseBody();

        var page = Objects.requireNonNull(response);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.content())
                .extracting(memberDto -> memberDto.account().id())
                .containsExactlyInAnyOrder(
                        Long.toString(accountId(owner)),
                        Long.toString(accountId(member)),
                        Objects.requireNonNull(admin.getAccountId()).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=-1", "?size=0", "?size=-1"})
    @WithAdminUser
    void shouldUseDefaultPaginationWhenParametersAreOutOfRange(String query) {
        Workspace workspace = createWorkspace(
                "pagination-space", "Pagination Space", "pagination", AccountType.ORG, persistUser("pagination-owner"));
        ensureAdminMembership(workspace);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/members" + query, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.number")
                .isEqualTo(0)
                .jsonPath("$.size")
                .isEqualTo(50);
    }

    @Test
    @WithAdminUser
    void shouldAcceptPageSizeAboveCap() {
        Workspace workspace = createWorkspace(
                "large-page-space", "Large Page Space", "large-page", AccountType.ORG, persistUser("large-page-owner"));
        ensureAdminMembership(workspace);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/members?size=101", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.size")
                .isEqualTo(100);
    }

    @Test
    @WithMentorUser
    void adminCanAssignRoleToMember() {
        User owner = persistUser("membership-owner-2");
        Workspace workspace =
                createWorkspace("membership-space-2", "Membership Space 2", "membership2", AccountType.ORG, owner);

        User admin = persistUser("mentor");
        ensureMembership(workspace, admin, WorkspaceRole.ADMIN);

        User targetUser = persistUser("target-user");
        workspaceMembershipService.createMembership(workspace, accountId(targetUser), WorkspaceRole.MEMBER);

        AssignRoleRequestDTO request =
                new AssignRoleRequestDTO(Long.toString(accountId(targetUser)), WorkspaceRole.ADMIN);

        WorkspaceMembershipDTO response = webTestClient
                .post()
                .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceMembershipDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.role()).isEqualTo(WorkspaceRole.ADMIN);

        Membership updated = workspaceMembershipRepository
                .findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId(targetUser))
                .orElseThrow();
        assertThat(updated.getRole()).isEqualTo(WorkspaceRole.ADMIN);
    }

    @Test
    @WithMentorUser
    void shouldRejectDemotionAndRemovalOfOwnerWhenCallerIsWorkspaceAdmin() {
        User owner = persistUser("protected-owner");
        Workspace workspace = createWorkspace("protected-owner", "Owner", "owner", AccountType.ORG, owner);
        ensureMembership(workspace, persistUser("mentor"), WorkspaceRole.ADMIN);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AssignRoleRequestDTO(Long.toString(accountId(owner)), WorkspaceRole.MEMBER))
                .exchange()
                .expectStatus()
                .isForbidden();
        assertThat(workspaceMembershipService
                        .getMembership(workspace.getId(), accountId(owner))
                        .getRole())
                .isEqualTo(WorkspaceRole.OWNER);

        webTestClient
                .delete()
                .uri("/workspaces/{slug}/members/{userId}", workspace.getWorkspaceSlug(), accountId(owner))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden();
        assertThat(workspaceMembershipService
                        .getMembership(workspace.getId(), accountId(owner))
                        .getRole())
                .isEqualTo(WorkspaceRole.OWNER);
    }

    @Test
    @WithMentorUser
    void shouldRejectDemotionAndRemovalWhenCallerIsTheLastOwner() {
        User owner = persistUser("mentor");
        Workspace workspace = createWorkspace("last-owner", "Last owner", "owner", AccountType.ORG, owner);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AssignRoleRequestDTO(Long.toString(accountId(owner)), WorkspaceRole.ADMIN))
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        assertThat(workspaceMembershipService
                        .getMembership(workspace.getId(), accountId(owner))
                        .getRole())
                .isEqualTo(WorkspaceRole.OWNER);

        webTestClient
                .delete()
                .uri("/workspaces/{slug}/members/{userId}", workspace.getWorkspaceSlug(), accountId(owner))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isEqualTo(409);
        assertThat(workspaceMembershipService
                        .getMembership(workspace.getId(), accountId(owner))
                        .getRole())
                .isEqualTo(WorkspaceRole.OWNER);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @WithMentorUser
    void shouldAllowOwnerToLeaveOwnershipWhenAnotherOwnerHasBeenAssigned(boolean remove) {
        User owner = persistUser("mentor");
        Workspace workspace = createWorkspace("owner-transfer", "Transfer", "owner", AccountType.ORG, owner);
        User successor = persistUser("successor");
        ensureMembership(workspace, successor, WorkspaceRole.MEMBER);

        webTestClient
                .post()
                .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AssignRoleRequestDTO(Long.toString(accountId(successor)), WorkspaceRole.OWNER))
                .exchange()
                .expectStatus()
                .isOk();

        if (remove) {
            webTestClient
                    .delete()
                    .uri("/workspaces/{slug}/members/{userId}", workspace.getWorkspaceSlug(), accountId(owner))
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNoContent();
            assertThat(workspaceMembershipService.getMembership(workspace.getId(), accountId(owner)))
                    .extracting(Membership::getLeftAt)
                    .isNotNull();
        } else {
            webTestClient
                    .post()
                    .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new AssignRoleRequestDTO(Long.toString(accountId(owner)), WorkspaceRole.ADMIN))
                    .exchange()
                    .expectStatus()
                    .isOk();
            assertThat(workspaceMembershipService
                            .getMembership(workspace.getId(), accountId(owner))
                            .getRole())
                    .isEqualTo(WorkspaceRole.ADMIN);
        }
        assertThat(workspaceMembershipService
                        .getMembership(workspace.getId(), accountId(successor))
                        .getRole())
                .isEqualTo(WorkspaceRole.OWNER);
    }

    @Test
    void shouldRetainOneOwnerWhenBothOwnersDemoteThemselvesConcurrently() throws Exception {
        User first = persistUser("concurrent-owner-one");
        User second = persistUser("concurrent-owner-two");
        Workspace workspace = createWorkspace("concurrent-owners", "Owners", "owners", AccountType.ORG, first);
        ensureMembership(workspace, second, WorkspaceRole.OWNER);
        var barrier = new CyclicBarrier(2);
        var responses = new ArrayList<Future<Integer>>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            for (User owner : List.of(first, second)) {
                TestUserFactory.ensureAccountForUser(accounts, identities, owner);
                var accountId = identities
                        .findActiveByProviderSubject(
                                Objects.requireNonNull(owner.getProvider().getId()),
                                owner.getNativeId().toString(),
                                null)
                        .orElseThrow()
                        .getAccount()
                        .getId();
                String token = "mock-jwt-user-sub-" + Objects.requireNonNull(accountId);
                responses.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return webTestClient
                            .post()
                            .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                            .headers(headers -> headers.setBearerAuth(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(new AssignRoleRequestDTO(Long.toString(accountId(owner)), WorkspaceRole.ADMIN))
                            .exchange()
                            .returnResult(Void.class)
                            .getStatus()
                            .value();
                }));
            }
            assertThat(List.of(
                            responses.get(0).get(20, TimeUnit.SECONDS),
                            responses.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(workspaceMembershipRepository.countByWorkspace_IdAndRole(workspace.getId(), WorkspaceRole.OWNER))
                .isEqualTo(1);
    }

    @Test
    @WithAdminUser
    void updateMemberVisibilityTogglesHiddenFlag() {
        User owner = persistUser("visibility-owner");
        Workspace workspace =
                createWorkspace("visibility-space", "Visibility Space", "visibility", AccountType.ORG, owner);

        ensureAdminMembership(workspace);

        User target = persistUser("visibility-target");
        workspaceMembershipService.createMembership(workspace, accountId(target), WorkspaceRole.MEMBER);

        WorkspaceMembershipDTO hidden = webTestClient
                .patch()
                .uri("/workspaces/{slug}/members/{accountId}/hidden", workspace.getWorkspaceSlug(), accountId(target))
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SetMemberHiddenRequestDTO(true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(WorkspaceMembershipDTO.class)
                .returnResult()
                .getResponseBody();

        assertThat(hidden).isNotNull();
        assertThat(hidden.hidden()).isTrue();
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(
                        workspace.getId(), accountId(target)))
                .get()
                .extracting(Membership::isHidden)
                .isEqualTo(true);
    }

    @Test
    @WithAdminUser
    void hiddenMemberLeavesWhenOrgSyncOmitsThemAndIsHiddenAgainOnReturn() {

        User owner = persistUser("sync-owner");
        Workspace workspace = createWorkspace("sync-space", "Sync Space", "syncorg", AccountType.ORG, owner);

        User hiddenUser = persistUser("hidden-user");
        workspaceMembershipService.createMembership(workspace, accountId(hiddenUser), WorkspaceRole.MEMBER);
        workspaceMembershipService.updateMemberVisibility(workspace.getId(), accountId(hiddenUser), true);

        User visibleUser = persistUser("visible-user");
        workspaceMembershipService.createMembership(workspace, accountId(visibleUser), WorkspaceRole.MEMBER);

        Map<Long, WorkspaceRole> desiredRoles = new HashMap<>();
        desiredRoles.put(owner.getId(), WorkspaceRole.OWNER);

        workspaceMembershipService.syncWorkspaceMembers(workspace, desiredRoles);

        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(
                        workspace.getId(), accountId(hiddenUser)))
                .get()
                .extracting(Membership::getLeftAt)
                .isNotNull();
        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(
                        workspace.getId(), accountId(visibleUser)))
                .get()
                .extracting(Membership::getLeftAt)
                .isNotNull();

        desiredRoles.put(hiddenUser.getId(), WorkspaceRole.MEMBER);
        workspaceMembershipService.syncWorkspaceMembers(workspace, desiredRoles);

        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(
                        workspace.getId(), accountId(hiddenUser)))
                .get()
                .extracting(Membership::isHidden)
                .isEqualTo(true);
    }

    @Test
    @WithAdminUser
    void hiddenFlagIsPreservedWhenOrgSyncUpdatesUnrelatedMemberRole() {

        User owner = persistUser("rolechange-owner");
        Workspace workspace =
                createWorkspace("rolechange-space", "Role Change Space", "rolechange", AccountType.ORG, owner);

        User hiddenUser = persistUser("rolechange-hidden");
        workspaceMembershipService.createMembership(workspace, accountId(hiddenUser), WorkspaceRole.MEMBER);
        workspaceMembershipService.updateMemberVisibility(workspace.getId(), accountId(hiddenUser), true);

        User promoted = persistUser("rolechange-promoted");
        workspaceMembershipService.createMembership(workspace, accountId(promoted), WorkspaceRole.MEMBER);

        Map<Long, WorkspaceRole> desiredRoles = new HashMap<>();
        desiredRoles.put(owner.getId(), WorkspaceRole.OWNER);
        desiredRoles.put(hiddenUser.getId(), WorkspaceRole.MEMBER);
        desiredRoles.put(promoted.getId(), WorkspaceRole.ADMIN);

        workspaceMembershipService.syncWorkspaceMembers(workspace, desiredRoles);

        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(
                        workspace.getId(), accountId(hiddenUser)))
                .get()
                .extracting(Membership::isHidden)
                .isEqualTo(true);

        assertThat(workspaceMembershipRepository.findByWorkspace_IdAndId_AccountId(
                        workspace.getId(), accountId(promoted)))
                .get()
                .extracting(Membership::getRole)
                .isEqualTo(WorkspaceRole.ADMIN);
    }

    @Test
    @WithMentorUser
    void nonMembersCannotAccessMembershipEndpoints() {

        persistUser("mentor");

        User owner = persistUser("membership-owner-3");
        Workspace workspace =
                createWorkspace("membership-space-3", "Membership Space 3", "membership3", AccountType.ORG, owner);

        webTestClient
                .get()
                .uri("/workspaces/{slug}/members", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }
}
