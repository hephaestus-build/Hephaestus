package de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener.MembershipChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.TeamMembershipListener;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaboratorRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.dto.GitLabMemberEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceLinkService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.HiddenFormerMember;
import de.tum.cit.aet.hephaestus.workspace.HiddenFormerMemberRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.ClientGraphQlRequest;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlClientInterceptor;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * A workspace on a course subgroup whose students hold access through the parent group, and whose tutors are listed
 * only by a team subgroup. GitLab is stood in for at the HTTP boundary: the real {@code GetGroup},
 * {@code GetGroupDescendants} and {@code GetGroupMembers} documents are sent and recorded JSON answers parsed; the
 * rows, the workspace roster and review eligibility behind them are real.
 */
class GitLabMembershipReconciliationIntegrationTest extends BaseIntegrationTest {

    private static final String GITLAB_URL = "https://gitlab.lrz.de";
    private static final String COURSE = "course/intro";
    private static final long COURSE_ID = 500L;
    private static final long TEAM_1 = 501L;
    private static final long TEAM_2 = 502L;
    private static final long STUDENT = 1001L;
    private static final long ASSISTANT = 1002L;
    private static final long TUTOR = 1003L;
    private static final long OWNER_ID = 999L;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private OrganizationRepository organizations;

    @Autowired
    private OrganizationMembershipRepository roster;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceMembershipRepository memberships;

    @Autowired
    private WorkspaceMembershipService membershipService;

    @Autowired
    private HiddenFormerMemberRepository hiddenFormerMembers;

    @Autowired
    private UserRepository users;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private TeamMembershipRepository teamMemberships;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryCollaboratorRepository collaborators;

    @Autowired
    private GitLabGraphQlResponseHandler responseHandler;

    @Autowired
    private GitLabProperties gitLabProperties;

    @Autowired
    private GitLabUserService gitLabUserService;

    @Autowired
    private GitLabWorkspaceLinkService workspaceLinkService;

    @Autowired
    private OrganizationMembershipListener organizationMembershipListener;

    @Autowired
    private TeamMembershipListener teamMembershipListener;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private GitLabMemberMessageHandler memberHandler;

    /** GitLab's answers by operation and group path, each consumed in order. */
    private final Map<String, Deque<String>> answers = new HashMap<>();

    private String lastRequest = "";
    private GitLabGroupMemberSyncService memberSync;
    private GitLabTeamSyncService teamSync;
    private Workspace workspace;
    private Organization course;
    private IdentityProvider gitLab;
    private User owner;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        answers.clear();
        gitLab = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, GITLAB_URL)
                .orElseGet(() -> providers.save(new IdentityProvider(IdentityProviderType.GITLAB, GITLAB_URL)));

        course = new Organization();
        course.setNativeId(COURSE_ID);
        course.setLogin(COURSE);
        course.setName("intro");
        course.setAvatarUrl("");
        course.setHtmlUrl(GITLAB_URL + "/" + COURSE);
        course.setCreatedAt(Instant.now());
        course.setUpdatedAt(Instant.now());
        course.setProvider(gitLab);
        course = organizations.save(course);

        workspace = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaces, connections, WorkspaceTestFixtures.gitLabPatWorkspace(COURSE), GITLAB_URL);
        workspace.setOrganization(course);
        workspace = workspaces.save(workspace);
        owner = users.save(TestUserFactory.createUser(OWNER_ID, "owner", gitLab));
        membershipService.createMembership(workspace, owner.getId(), WorkspaceRole.OWNER);

        GitLabGraphQlClientProvider clients = mock(GitLabGraphQlClientProvider.class);
        when(clients.forScope(anyLong())).thenReturn(gitLabClient());
        memberSync = new GitLabGroupMemberSyncService(
                clients,
                responseHandler,
                roster,
                users,
                workspaceLinkService,
                gitLabProperties,
                organizationMembershipListener,
                transactionTemplate);
        teamSync = new GitLabTeamSyncService(
                teams,
                teamMemberships,
                repositories,
                clients,
                responseHandler,
                new GitLabTeamProcessor(teams),
                gitLabUserService,
                workspaceLinkService,
                gitLabProperties,
                collaborators,
                transactionTemplate,
                teamMembershipListener);
    }

    @Test
    void shouldAdmitAnInheritedStudentAndHoldEachMembersHighestGrant() {
        answer(
                "GetGroupMembers " + COURSE,
                page(
                        false,
                        member(STUDENT, "student", 30),
                        member(ASSISTANT, "assistant", 40),
                        member(ASSISTANT, "assistant", 30)));

        assertThat(memberSync.syncGroupMemberships(workspace.getId(), COURSE, course))
                .isEqualTo(2);

        assertThat(groupRoleOf(STUDENT)).contains(OrganizationMemberRole.MEMBER);
        assertThat(groupRoleOf(ASSISTANT)).contains(OrganizationMemberRole.ADMIN);
        assertThat(workspaceRoleOf(ASSISTANT)).contains(WorkspaceRole.ADMIN);
        assertThat(membershipService.practiceReviewEligibleUserIds(workspace.getId()))
                .contains(userId(STUDENT), userId(ASSISTANT));
    }

    @Test
    void shouldKeepEveryRoleWhenALaterRosterPageCannotBeReadUntilAWholeListingLowersIt() {
        answer(
                "GetGroupMembers " + COURSE,
                page(false, member(STUDENT, "student", 30), member(ASSISTANT, "assistant", 40)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);

        // Page one lists only the assistant's lower direct grant; page two, with the inherited one, is unreadable.
        answer(
                "GetGroupMembers " + COURSE,
                page(true, member(ASSISTANT, "assistant", 30)),
                "{\"data\":{\"group\":null}}");
        assertThat(memberSync.syncGroupMemberships(workspace.getId(), COURSE, course))
                .isEqualTo(-1);
        assertThat(groupRoleOf(ASSISTANT)).contains(OrganizationMemberRole.ADMIN);
        assertThat(workspaceRoleOf(ASSISTANT)).contains(WorkspaceRole.ADMIN);
        assertThat(workspaceRoleOf(STUDENT)).contains(WorkspaceRole.MEMBER);

        answer("GetGroupMembers " + COURSE, page(false, member(ASSISTANT, "assistant", 30)));
        assertThat(memberSync.syncGroupMemberships(workspace.getId(), COURSE, course))
                .isEqualTo(1);
        assertThat(workspaceRoleOf(ASSISTANT)).contains(WorkspaceRole.MEMBER);
        assertThat(workspaceRoleOf(STUDENT)).isEmpty();
    }

    @Test
    void shouldKeepATeamOnlyTutorThroughAnUnreadableTeamAndRevokeThemInThePassThatDropsTheirLastTeam() {
        User tutor = users.save(TestUserFactory.createUser(TUTOR, "tutor", gitLab));
        Team root = teams.save(team(COURSE_ID, "intro", null));
        Team teamOne = teams.save(team(TEAM_1, "team-1", root.getId()));
        teamMemberships.save(new TeamMembership(teamOne, tutor, TeamMembership.Role.MAINTAINER));
        answer("GetGroupMembers " + COURSE, page(false, member(STUDENT, "student", 30)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);
        assertThat(workspaceRoleOf(TUTOR)).as("a team still lists them").contains(WorkspaceRole.MEMBER);

        answerTeamSync(page(false), "{\"data\":{\"group\":null}}");
        assertThat(teamSync.syncTeamsForGroup(workspace.getId(), COURSE).complete())
                .isFalse();
        assertThat(workspaceRoleOf(TUTOR)).contains(WorkspaceRole.MEMBER);
        assertThat(membershipService.practiceReviewEligibleUserIds(workspace.getId()))
                .contains(tutor.getId());

        answerTeamSync(page(false), page(false));
        assertThat(teamSync.syncTeamsForGroup(workspace.getId(), COURSE).complete())
                .isTrue();
        assertThat(workspaceRoleOf(TUTOR)).isEmpty();
        assertThat(membershipService.practiceReviewEligibleUserIds(workspace.getId()))
                .doesNotContain(tutor.getId());
        assertThat(workspaceRoleOf(STUDENT)).contains(WorkspaceRole.MEMBER);
    }

    @Test
    void shouldRemoveAHiddenStudentGitLabNoLongerListsAndHideThemAgainWhenTheyReturn() {
        answer("GetGroupMembers " + COURSE, page(false, member(STUDENT, "student", 30)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);
        WorkspaceMembership hidden = memberships
                .findByWorkspace_IdAndUser_Id(workspace.getId(), userId(STUDENT))
                .orElseThrow();
        hidden.setHidden(true);
        memberships.save(hidden);

        answer("GetGroupMembers " + COURSE, page(false));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);
        assertThat(workspaceRoleOf(STUDENT)).isEmpty();
        assertThat(membershipService.practiceReviewEligibleUserIds(workspace.getId()))
                .doesNotContain(userId(STUDENT));
        assertThat(hiddenFormerMembers.existsById(new HiddenFormerMember.Key(workspace.getId(), userId(STUDENT))))
                .isTrue();

        answer("GetGroupMembers " + COURSE, page(false, member(STUDENT, "student", 30)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);
        assertThat(memberships
                        .findByWorkspace_IdAndUser_Id(workspace.getId(), userId(STUDENT))
                        .map(WorkspaceMembership::isHidden))
                .contains(true);
        assertThat(hiddenFormerMembers.existsById(new HiddenFormerMember.Key(workspace.getId(), userId(STUDENT))))
                .isFalse();
    }

    @Test
    void shouldKeepAnOwnerTheRosterDropsForAdministrationButReviewNobodyOnceDisconnected() {
        answer("GetGroupMembers " + COURSE, page(false, member(STUDENT, "student", 30)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);

        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);
        assertThat(membershipService.practiceReviewEligibleUserIds(workspace.getId()))
                .containsExactly(userId(STUDENT));

        Connection connection = connections
                .findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(
                        workspace.getId(), IntegrationKind.GITLAB, IntegrationState.ACTIVE)
                .orElseThrow();
        ReflectionTestUtils.setField(connection, "state", IntegrationState.UNINSTALLED);
        connections.save(connection);
        assertThat(membershipService.practiceReviewEligibleUserIds(workspace.getId()))
                .isEmpty();
    }

    @Test
    void shouldKeepAnInheritedStudentWhenAnEventOffAConnectionRouteRemovesTheirDirectGrant() {
        answer("GetGroupMembers " + COURSE, page(false, member(STUDENT, "student", 30)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);

        transactionTemplate.executeWithoutResult(status -> memberHandler.handleEvent(new GitLabMemberEventDTO(
                GitLabMemberEventDTO.EVENT_USER_REMOVE,
                "intro",
                COURSE,
                COURSE_ID,
                "student",
                "student",
                null,
                STUDENT,
                null,
                null,
                null,
                null,
                null)));

        assertThat(groupRoleOf(STUDENT)).contains(OrganizationMemberRole.MEMBER);
        assertThat(workspaceRoleOf(STUDENT)).contains(WorkspaceRole.MEMBER);
    }

    @Test
    void shouldRevokeAStudentAtOnceWhenGitLabReportsNoAccessToTheGroupAndKeepTheOwner() {
        answer(
                "GetGroupMembers " + COURSE,
                page(false, member(STUDENT, "student", 30), member(ASSISTANT, "assistant", 40)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);
        WorkspaceMembership hidden = memberships
                .findByWorkspace_IdAndUser_Id(workspace.getId(), userId(STUDENT))
                .orElseThrow();
        hidden.setHidden(true);
        memberships.save(hidden);

        reportNoAccess(STUDENT, COURSE_ID);
        reportNoAccess(OWNER_ID, COURSE_ID);

        assertThat(groupRoleOf(STUDENT)).isEmpty();
        assertThat(workspaceRoleOf(STUDENT)).isEmpty();
        assertThat(hiddenFormerMembers.existsById(new HiddenFormerMember.Key(workspace.getId(), userId(STUDENT))))
                .isTrue();
        assertThat(workspaceRoleOf(ASSISTANT)).as("nobody else is reconciled").contains(WorkspaceRole.ADMIN);
        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);

        // What the handler signals after storing a reported grant.
        roster.upsertMembership(course.getId(), userId(STUDENT), OrganizationMemberRole.MEMBER);
        organizationMembershipListener.onMemberAdded(
                new MembershipChangedEvent(course.getId(), COURSE, userId(STUDENT), "student", "DEVELOPER"));
        assertThat(memberships
                        .findByWorkspace_IdAndUser_Id(workspace.getId(), userId(STUDENT))
                        .map(WorkspaceMembership::isHidden))
                .contains(true);
    }

    @Test
    void shouldRevokeATeamOnlyTutorAtOnceWhenGitLabReportsTheirLastTeamGone() {
        User tutor = users.save(TestUserFactory.createUser(TUTOR, "tutor", gitLab));
        Team root = teams.save(team(COURSE_ID, "intro", null));
        for (long teamId : List.of(TEAM_1, TEAM_2)) {
            Team subgroup = teams.save(team(teamId, "team-" + (teamId - COURSE_ID), root.getId()));
            teamMemberships.save(new TeamMembership(subgroup, tutor, TeamMembership.Role.MAINTAINER));
        }
        answer("GetGroupMembers " + COURSE, page(false, member(STUDENT, "student", 30)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);

        reportNoAccess(TUTOR, TEAM_1);
        assertThat(workspaceRoleOf(TUTOR)).as("team-2 still lists them").contains(WorkspaceRole.MEMBER);

        reportNoAccess(TUTOR, TEAM_2);
        assertThat(workspaceRoleOf(TUTOR)).isEmpty();
        assertThat(workspaceRoleOf(STUDENT)).contains(WorkspaceRole.MEMBER);
        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);
    }

    /** A provider-verified membership lookup that lists no access for the user. */
    private void reportNoAccess(long nativeUserId, long groupId) {
        var route = new GitLabRouteAdmission.AdmittedRoute(
                0L, workspace.getId(), Objects.requireNonNull(gitLab.getId()), COURSE_ID, COURSE);
        transactionTemplate.executeWithoutResult(status -> memberHandler.applyReportedMembership(
                nativeUserId, route, new GitLabRouteAdmission.ReportedMembership(groupId, List.of())));
    }

    private Optional<OrganizationMemberRole> groupRoleOf(long nativeId) {
        Long userId = userId(nativeId);
        return roster.findByOrganizationId(course.getId()).stream()
                .filter(membership -> membership.getUserId().equals(userId))
                .map(OrganizationMembership::getRole)
                .findFirst();
    }

    private Optional<WorkspaceRole> workspaceRoleOf(long nativeId) {
        return users.findByNativeIdAndProviderId(nativeId, Objects.requireNonNull(gitLab.getId()))
                .flatMap(user -> workspaceRoleOf(user));
    }

    private Optional<WorkspaceRole> workspaceRoleOf(User user) {
        return memberships
                .findByWorkspace_IdAndUser_Id(workspace.getId(), user.getId())
                .map(WorkspaceMembership::getRole);
    }

    private Long userId(long nativeId) {
        return users.findByNativeIdAndProviderId(nativeId, Objects.requireNonNull(gitLab.getId()))
                .orElseThrow()
                .getId();
    }

    private Team team(long nativeId, String slug, @Nullable Long parentId) {
        Team team = new Team();
        team.setNativeId(nativeId);
        team.setProvider(gitLab);
        team.setName(slug);
        team.setSlug(slug);
        team.setOrganization(COURSE);
        team.setHtmlUrl(GITLAB_URL + "/" + COURSE + "/" + slug);
        team.setPrivacy(Team.Privacy.VISIBLE);
        team.setLastSyncAt(Instant.now());
        team.setParentId(parentId);
        return team;
    }

    /** One team sync of the course group and its subgroup team-1, whose members GitLab lists as given. */
    private void answerTeamSync(String courseMembers, String teamOneMembers) {
        answer("GetGroup " + COURSE, """
                {"data":{"group":{%s,"parent":null}}}
                """.formatted(group(COURSE_ID, COURSE)));
        answer("GetGroupDescendants " + COURSE, """
                {"data":{"group":{"descendantGroups":{"pageInfo":{"hasNextPage":false,"endCursor":null},
                 "nodes":[{%s,"parent":{"id":"gid://gitlab/Group/%d","fullPath":"%s"}}]}}}}
                """.formatted(group(TEAM_1, COURSE + "/team-1"), COURSE_ID, COURSE));
        answer("GetGroupMembers " + COURSE, courseMembers);
        answer("GetGroupMembers " + COURSE + "/team-1", teamOneMembers);
    }

    private static String group(long id, String fullPath) {
        return """
                "id":"gid://gitlab/Group/%d","fullPath":"%s","name":"%s","avatarUrl":null,"webUrl":"%s/%s",
                "description":null,"visibility":"private"
                """.formatted(id, fullPath, fullPath.substring(fullPath.lastIndexOf('/') + 1), GITLAB_URL, fullPath)
                .strip();
    }

    private static String member(long nativeId, String username, int accessLevel) {
        return """
                {"user":{"id":"gid://gitlab/User/%d","username":"%s","name":"%s","avatarUrl":null,
                 "webUrl":"%s/%s","publicEmail":null},
                 "accessLevel":{"stringValue":"%s","integerValue":%d}}
                """.formatted(
                        nativeId,
                        username,
                        username,
                        GITLAB_URL,
                        username,
                        accessLevel >= 40 ? "MAINTAINER" : "DEVELOPER",
                        accessLevel);
    }

    private static String page(boolean hasNextPage, String... members) {
        return """
                {"data":{"group":{"groupMembers":{"pageInfo":{"hasNextPage":%s,"endCursor":%s},"nodes":[%s]}}}}
                """.formatted(hasNextPage, hasNextPage ? "\"next\"" : "null", String.join(",", members));
    }

    private void answer(String operationAndGroup, String... pages) {
        answers.computeIfAbsent(operationAndGroup, key -> new ArrayDeque<>()).addAll(List.of(pages));
    }

    private HttpGraphQlClient gitLabClient() {
        Pattern operation = Pattern.compile("query (\\w+)");
        WebClient.Builder webClient = WebClient.builder().exchangeFunction(request -> {
            String answer = Optional.ofNullable(answers.get(lastRequest))
                    .map(Deque::poll)
                    .orElse(null);
            assertThat(answer)
                    .as("GitLab was asked more than the test answered: %s", lastRequest)
                    .isNotNull();
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(Objects.requireNonNull(answer))
                    .build());
        });
        GraphQlClientInterceptor recordRequest = new GraphQlClientInterceptor() {
            @Override
            public Mono<ClientGraphQlResponse> intercept(ClientGraphQlRequest request, Chain chain) {
                Matcher name = operation.matcher(request.getDocument());
                lastRequest = (name.find() ? name.group(1) : "") + " "
                        + request.getVariables().get("fullPath");
                return chain.next(request);
            }
        };
        return HttpGraphQlClient.builder(webClient)
                .url(GITLAB_URL + "/api/graphql")
                .documentSource(new FragmentMergingDocumentSource(
                        List.of(
                                new ClassPathResource("graphql/gitlab/operations/"),
                                new ClassPathResource("graphql/gitlab/fragments/")),
                        List.of(".graphql", ".gql"),
                        List.of(new ClassPathResource("graphql/gitlab/fragments/GitLabUserFields.graphql"))))
                .interceptor(recordRequest)
                .build();
    }
}
