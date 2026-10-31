package de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.graphql.FragmentMergingDocumentSource;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener.MembershipChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipListener.OrganizationSyncedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.TeamMembershipListener;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMemberRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator.RepositoryCollaborator;
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
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupMemberResponse;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.organization.dto.GitLabMemberEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.repository.collaborator.GitLabCollaboratorSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.team.GitLabTeamSyncService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabProjectAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabRouteAdmission;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceLinkService;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewCoverageService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewRepositoryTarget;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeMode;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceReviewScope;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipReviewSelection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.ClientGraphQlRequest;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlClientInterceptor;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GitLabMembershipReconciliationIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private GitLabProjectAdmission projectAdmission;

    private static final String GITLAB_URL = "https://gitlab.lrz.de";
    private static final String COURSE = "course/intro";
    private static final long COURSE_ID = 500L;
    private static final long TEAM_1 = 501L;
    private static final long TEAM_2 = 502L;
    private static final long STUDENT = 1001L;
    private static final long ASSISTANT = 1002L;
    private static final long TUTOR = 1003L;
    private static final long OWNER_ID = 999L;
    private static final long TA = 1004L;
    private static final long ANCESTOR = 1005L;
    private static final long GUEST = 1006L;

    /** An e-mail invitation nobody has accepted: GraphQL lists it with no user. */
    private static final String PENDING_INVITATION = """
        {"user":null,"accessLevel":{"stringValue":"DEVELOPER","integerValue":30}}
        """;

    @Autowired
    private ItemIndexService itemWriter;

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
    private MembershipRepository memberships;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private MembershipReviewSelection reviewSelection;

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

    @Autowired
    private PracticeReviewCoverageService coverage;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private WorkspaceActorSelector actorSelector;

    @Autowired
    private Clock clock;

    @Autowired
    private AutowireCapableBeanFactory beanFactory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConnectionService connectionService;

    /** GitLab's answers by operation and group path, each consumed in order. */
    private final Map<String, Deque<String>> answers = new HashMap<>();

    /** A change made while GitLab answers the request of that operation and path, once. */
    private final Map<String, Runnable> whileAnswering = new HashMap<>();

    private String lastRequest = "";

    /** The {@code relations} each request asked GitLab for, by operation and group path. */
    private final Map<String, Object> relationsAsked = new HashMap<>();

    private GitLabGroupMemberSyncService memberSync;
    private GitLabTeamSyncService teamSync;
    private GitLabCollaboratorSyncService collaboratorSync;
    private GitLabSelectedMembershipProjection projection;
    private Workspace workspace;
    private Organization course;
    private IdentityProvider gitLab;
    private User owner;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        answers.clear();
        relationsAsked.clear();
        whileAnswering.clear();
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
        workspace.setOrganizationId(course.getId());
        workspace = workspaces.save(workspace);
        owner = users.save(TestUserFactory.createUser(OWNER_ID, "owner", gitLab));
        membershipService.createMembership(workspace, ensureAccount(owner), WorkspaceRole.OWNER);

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
        collaboratorSync = new GitLabCollaboratorSyncService(
                collaborators,
                clients,
                responseHandler,
                gitLabUserService,
                gitLabProperties,
                transactionTemplate,
                workspaceLinkService,
                projectAdmission);
        projection = new GitLabSelectedMembershipProjection(
                workspaces,
                monitors,
                connections,
                actorSelector,
                roster,
                collaboratorSync,
                teamSync,
                gitLabUserService,
                membershipService,
                transactionTemplate,
                clock);
        // Its persistence context, as the container injects it.
        beanFactory.autowireBean(projection);
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

        assertThat(relationsAsked.get("GetGroupMembers " + COURSE))
                .as("the connected group's effective members, as GET /groups/:id/members/all" + " lists them")
                .isEqualTo(List.of("DIRECT", "INHERITED", "SHARED_FROM_GROUPS"));
        assertThat(groupRoleOf(STUDENT)).contains(OrganizationMemberRole.MEMBER);
        assertThat(groupRoleOf(ASSISTANT)).contains(OrganizationMemberRole.ADMIN);
        assertThat(workspaceRoleOf(ASSISTANT)).contains(WorkspaceRole.ADMIN);
        assertThat(eligibleAccounts()).contains(accountId(userId(STUDENT)), accountId(userId(ASSISTANT)));
    }

    @Test
    void shouldKeepEveryRoleWhenALaterRosterPageCannotBeReadUntilAWholeListingLowersIt() {
        answer(
                "GetGroupMembers " + COURSE,
                page(false, member(STUDENT, "student", 30), member(ASSISTANT, "assistant", 40)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);

        // Page one lists only the assistant's lower direct grant; page two, with the inherited one,
        // is unreadable.
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
        assertThat(eligibleAccounts()).contains(accountId(tutor.getId()));

        answerTeamSync(page(false), page(false));
        assertThat(teamSync.syncTeamsForGroup(workspace.getId(), COURSE).complete())
                .isTrue();
        assertThat(relationsAsked.get("GetGroupMembers " + COURSE + "/team-1"))
                .as("a team's own grants; the team hierarchy carries what it inherits")
                .isEqualTo(GitLabGroupMemberResponse.TEAM_RELATIONS);
        assertThat(workspaceRoleOf(TUTOR)).isEmpty();
        assertThat(eligibleAccounts()).doesNotContain(accountId(tutor.getId()));
        assertThat(workspaceRoleOf(STUDENT)).contains(WorkspaceRole.MEMBER);
    }

    @Test
    void shouldRemoveAHiddenStudentGitLabNoLongerListsAndHideThemAgainWhenTheyReturn() {
        answer("GetGroupMembers " + COURSE, page(false, member(STUDENT, "student", 30)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);
        Membership hidden = memberships
                .findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId(userId(STUDENT)))
                .orElseThrow();
        hidden.setHidden(true);
        memberships.save(hidden);

        answer("GetGroupMembers " + COURSE, page(false));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);
        assertThat(workspaceRoleOf(STUDENT)).isEmpty();
        assertThat(eligibleAccounts()).doesNotContain(accountId(userId(STUDENT)));
        assertThat(memberships
                        .findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId(userId(STUDENT)))
                        .map(member -> member.isHidden() && member.getLeftAt() != null)
                        .orElse(false))
                .isTrue();

        answer("GetGroupMembers " + COURSE, page(false, member(STUDENT, "student", 30)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);
        assertThat(memberships
                        .findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId(userId(STUDENT)))
                        .map(Membership::isHidden))
                .contains(true);
        assertThat(memberships
                        .findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId(userId(STUDENT)))
                        .map(member -> member.isHidden() && member.getLeftAt() != null)
                        .orElse(false))
                .isFalse();
    }

    @Test
    void shouldKeepTheOwnerAndCountNeitherTheirOtherProviderProfileNorASameNamedAccount() {
        IdentityProvider gitHub = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(
                        () -> providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        User ownerOnGitHub = users.save(TestUserFactory.createUser(OWNER_ID, "owner", gitHub));
        membershipService.createMembership(workspace, ensureAccount(ownerOnGitHub), WorkspaceRole.OWNER);
        User studentOnGitHub = users.save(TestUserFactory.createUser(STUDENT, "student", gitHub));
        // An earlier GitLab account under the same username; GitLab identifies people by id, not by
        // name.
        User formerStudentName = users.save(TestUserFactory.createUser(2001L, "student", gitLab));
        answer("GetGroupMembers " + COURSE, page(false, member(STUDENT, "student", 30)));

        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);

        assertThat(workspaceRoleOf(owner))
                .as("an owner stays, whatever the roster says")
                .contains(WorkspaceRole.OWNER);
        assertThat(workspaceRoleOf(ownerOnGitHub)).contains(WorkspaceRole.OWNER);
        assertThat(workspaceRoleOf(studentOnGitHub)).isEmpty();
        assertThat(workspaceRoleOf(formerStudentName)).isEmpty();
        assertThat(eligibleAccounts())
                .as("the owner's GitHub profile authors no GitLab work")
                .containsExactlyInAnyOrder(accountId(userId(STUDENT)), accountId(owner.getId()));
        assertThat(coverage.summary(workspace, 0).eligiblePeople()).isEqualTo(2);
    }

    @Test
    void shouldAdmitAnInheritedAuthorAtTheReadyOccasionOnASelectedRepositoryAndBaseBranch() {
        String project = COURSE + "/team-1/project";
        var repository = new Repository();
        repository.setNativeId(7002L);
        repository.setProvider(gitLab);
        repository.setName("project");
        repository.setNameWithOwner(project);
        repository.setDefaultBranch("main");
        repository = repositories.saveAndFlush(repository);
        long repositoryItemId = itemWriter
                .upsert(new ItemRegistration(
                        workspace.getId(), ScmItemEvents.REPOSITORY, repository.getId(), null, null))
                .itemId();
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setNameWithOwner(project);
        monitor.setNativeId(7002L);
        monitor.setWorkspace(workspace);
        monitors.save(monitor);
        coverage.replace(
                workspace,
                new WorkspaceReviewScope(
                        ReviewScopeMode.SELECTED,
                        ReviewScopeMode.ALL,
                        List.of(new ReviewRepositoryTarget(repositoryItemId, List.of("main"))),
                        List.of()));
        workspace = workspaces.save(workspace);
        User student = users.save(TestUserFactory.createUser(STUDENT, "student", gitLab));
        ensureAccount(student);
        PullRequest ready = mergeRequest(student, "main");

        assertThat(coverage.admits(
                        workspace, repositoryItemId, ready.getBaseRefName(), accountId(student.getId()), true))
                .isFalse();

        answer("GetGroupMembers " + COURSE, page(false, member(STUDENT, "student", 30)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);

        assertThat(coverage.admits(
                        workspace, repositoryItemId, ready.getBaseRefName(), accountId(student.getId()), true))
                .isTrue();
        assertThat(coverage.admits(workspace, repositoryItemId, "develop", accountId(student.getId()), true))
                .as("the base branch still has to be selected")
                .isFalse();
        assertThat(coverage.admits(workspace, repositoryItemId, "main", Long.MAX_VALUE, true))
                .isFalse();
    }

    @Test
    void shouldRemoveAStudentWhoLostInheritedAccessDespiteAPendingInvitation() {
        answer(
                "GetGroupMembers " + COURSE,
                page(false, member(STUDENT, "student", 30), member(ASSISTANT, "assistant", 40)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);
        assertThat(workspaceRoleOf(STUDENT)).contains(WorkspaceRole.MEMBER);

        // GraphQL lists an e-mail invitation nobody has accepted with no user; REST /members/all
        // leaves it out.
        answer("GetGroupMembers " + COURSE, page(false, member(ASSISTANT, "assistant", 40), """
            {"user":null,"accessLevel":{"stringValue":"DEVELOPER","integerValue":30}}
            """));
        assertThat(memberSync.syncGroupMemberships(workspace.getId(), COURSE, course))
                .isEqualTo(1);

        assertThat(groupRoleOf(STUDENT)).isEmpty();
        assertThat(workspaceRoleOf(STUDENT)).isEmpty();
        assertThat(eligibleAccounts()).doesNotContain(accountId(userId(STUDENT)));
        assertThat(workspaceRoleOf(ASSISTANT)).contains(WorkspaceRole.ADMIN);
        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);
    }

    @Test
    void shouldRecordAProjectOnlyStudentDespiteAPendingInvitationToTheProject() {
        String path = COURSE + "/team-1/demo";
        Repository project = new Repository();
        project.setNativeId(7001L);
        project.setProvider(gitLab);
        project.setName("demo");
        project.setNameWithOwner(path);
        project.setHtmlUrl(GITLAB_URL + "/" + path);
        project.setDefaultBranch("main");
        project = repositories.save(project);
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setNameWithOwner(path);
        monitor.setNativeId(7001L);
        monitor.setWorkspace(workspace);
        monitors.save(monitor);
        answer("GetProjectMembers " + path, """
            {"data":{"project":{"projectMembers":{"pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[
             {"user":null,"accessLevel":{"stringValue":"DEVELOPER","integerValue":30}},%s]}}}}
            """.formatted(member(STUDENT, "student", 30)));

        assertThat(collaboratorSync
                        .syncCollaboratorsForRepository(workspace.getId(), project)
                        .isCompleted())
                .isTrue();

        assertThat(collaborators
                        .findByRepositoryIdAndUserId(project.getId(), userId(STUDENT))
                        .map(RepositoryCollaborator::getPermission))
                .contains(RepositoryCollaborator.Permission.WRITE);
    }

    @Test
    void shouldKeepAProjectOnlyStudentTheConnectedGroupsOwnTeamLists() {
        User student = users.save(TestUserFactory.createUser(STUDENT, "student", gitLab));
        Team root = teams.save(team(COURSE_ID, "intro", null));
        teamMemberships.save(new TeamMembership(root, student, TeamMembership.Role.MEMBER));
        answer("GetGroupMembers " + COURSE, page(false, member(ASSISTANT, "assistant", 40)));

        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);

        assertThat(workspaceRoleOf(STUDENT)).contains(WorkspaceRole.MEMBER);

        reportNoAccess(STUDENT, COURSE_ID);
        assertThat(teamMemberships.existsByTeam_IdAndUser_Id(root.getId(), student.getId()))
                .as("no access to the group is no direct grant in it either")
                .isFalse();
        assertThat(workspaceRoleOf(STUDENT)).isEmpty();
    }

    @Test
    void shouldChangeNothingWhenAMemberEventArrivesWithoutAnAdmittedRoute() {
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
        Membership hidden = memberships
                .findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId(userId(STUDENT)))
                .orElseThrow();
        hidden.setHidden(true);
        memberships.save(hidden);

        reportNoAccess(STUDENT, COURSE_ID);
        reportNoAccess(OWNER_ID, COURSE_ID);

        assertThat(groupRoleOf(STUDENT)).isEmpty();
        assertThat(workspaceRoleOf(STUDENT)).isEmpty();
        assertThat(memberships
                        .findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId(userId(STUDENT)))
                        .map(member -> member.isHidden() && member.getLeftAt() != null)
                        .orElse(false))
                .isTrue();
        assertThat(workspaceRoleOf(ASSISTANT)).as("nobody else is reconciled").contains(WorkspaceRole.ADMIN);
        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);

        // What the handler signals after storing a reported grant.
        roster.upsertMembership(course.getId(), userId(STUDENT), OrganizationMemberRole.MEMBER);
        organizationMembershipListener.onMemberAdded(
                new MembershipChangedEvent(course.getId(), COURSE, userId(STUDENT), "student", "DEVELOPER"));
        assertThat(memberships
                        .findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId(userId(STUDENT)))
                        .map(Membership::isHidden))
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

    @Test
    void shouldNotEnrollParentGroupMembersInASelectedWorkspaceFromRosterSync() {
        selectProjectsOnly();
        answer(
                "GetGroupMembers " + COURSE,
                page(false, member(STUDENT, "student", 30), member(ASSISTANT, "assistant", 40)));

        assertThat(memberSync.syncGroupMemberships(workspace.getId(), COURSE, course))
                .isEqualTo(2);

        assertThat(groupRoleOf(STUDENT))
                .as("the shared roster still records them")
                .contains(OrganizationMemberRole.MEMBER);
        assertThat(groupRoleOf(ASSISTANT)).contains(OrganizationMemberRole.ADMIN);
        assertThat(workspaceRoleOf(STUDENT))
                .as("a group grant alone selects no project")
                .isEmpty();
        assertThat(workspaceRoleOf(ASSISTANT)).isEmpty();
        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);
    }

    @Test
    void shouldNotEnrollUnselectedTeamMembersInASelectedWorkspace() {
        selectProjectsOnly();
        User tutor = users.save(TestUserFactory.createUser(TUTOR, "tutor", gitLab));
        Team root = teams.save(team(COURSE_ID, "intro", null));
        Team teamOne = teams.save(team(TEAM_1, "team-1", root.getId()));
        teamMemberships.save(new TeamMembership(teamOne, tutor, TeamMembership.Role.MAINTAINER));

        // A whole team graph whose team-1 listing is unreadable: the stored team rows stay.
        answerTeamSync(page(false), "{\"data\":{\"group\":null}}");
        assertThat(teamSync.syncTeamsForGroup(workspace.getId(), COURSE).complete())
                .isFalse();

        assertThat(teamMemberships.existsByTeam_IdAndUser_Id(teamOne.getId(), tutor.getId()))
                .as("the shared team still lists them")
                .isTrue();
        assertThat(workspaceRoleOf(tutor))
                .as("a team of no selected project grants nothing")
                .isEmpty();
        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);
    }

    @Test
    void shouldNotEnrollParentGroupMembersInASelectedWorkspaceFromMemberEvents() {
        selectProjectsOnly();
        User student = users.save(TestUserFactory.createUser(STUDENT, "student", gitLab));
        roster.upsertMembership(course.getId(), student.getId(), OrganizationMemberRole.MEMBER);

        // What the handler signals after storing a reported grant.
        organizationMembershipListener.onMemberAdded(
                new MembershipChangedEvent(course.getId(), COURSE, student.getId(), "student", "DEVELOPER"));

        assertThat(groupRoleOf(STUDENT)).contains(OrganizationMemberRole.MEMBER);
        assertThat(workspaceRoleOf(student))
                .as("a group grant alone selects no project")
                .isEmpty();
        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);
    }

    @Test
    void shouldEnrollSelectedProjectAndSubgroupGranteesAndKeepAdminOnlyFromTheRoster() {
        selectProjectsOnly();
        String project = COURSE + "/team-1/p";
        String nested = COURSE + "/a/b/q";
        select(project, 7101L);
        select(nested, 7102L);
        answer(
                "GetGroupMembers " + COURSE,
                page(false, member(ASSISTANT, "assistant", 40), member(ANCESTOR, "ancestor", 50)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);
        Workspace other = WorkspaceTestFixtures.persistGitLabWorkspace(
                workspaces, connections, WorkspaceTestFixtures.gitLabPatWorkspace("course/other"), GITLAB_URL);
        long ancestorAccount = ensureAccount(users.findById(userId(ANCESTOR)).orElseThrow());
        membershipService.createMembership(other, ancestorAccount, WorkspaceRole.MEMBER);

        answer(
                "GetProjectMembers " + project,
                projectPage(7101L, false, member(STUDENT, "student", 30), member(GUEST, "guest", 10)));
        answer("GetProjectMembers " + nested, projectPage(7102L, false));
        answer("GetGroupMembers " + COURSE + "/a", page(false, member(ASSISTANT, "assistant", 30)));
        answer("GetGroupMembers " + COURSE + "/a/b", page(false, member(TA, "ta", 30), PENDING_INVITATION));
        answer("GetGroupMembers " + COURSE + "/team-1", page(false, member(TUTOR, "tutor", 40)));

        assertThat(projection.project(workspace.getId())).isEqualTo(GitLabSelectedMembershipProjection.Outcome.APPLIED);

        for (String selected : List.of(project, nested)) {
            assertThat(relationsAsked.get("GetProjectMembers " + selected))
                    .as("the project's own grants, not what it inherits")
                    .isEqualTo(List.of("DIRECT", "INVITED_GROUPS"));
        }
        for (String subgroup : List.of(COURSE + "/a", COURSE + "/a/b", COURSE + "/team-1")) {
            assertThat(relationsAsked.get("GetGroupMembers " + subgroup))
                    .isEqualTo(GitLabGroupMemberResponse.TEAM_RELATIONS);
        }
        assertThat(relationsAsked.get("GetGroupMembers " + COURSE))
                .as("only the roster sync asks the connected group; the projection never does")
                .isEqualTo(GitLabGroupMemberResponse.EFFECTIVE_RELATIONS);
        assertThat(workspaceRoleOf(STUDENT)).contains(WorkspaceRole.MEMBER);
        assertThat(workspaceRoleOf(TA)).contains(WorkspaceRole.MEMBER);
        assertThat(workspaceRoleOf(TUTOR))
                .as("a subgroup maintainer administers no workspace")
                .contains(WorkspaceRole.MEMBER);
        assertThat(workspaceRoleOf(ASSISTANT))
                .as("a roster admin keeps admin while a subgroup grants them access")
                .contains(WorkspaceRole.ADMIN);
        assertThat(workspaceRoleOf(ANCESTOR))
                .as("a group grant alone selects no project")
                .isEmpty();
        assertThat(users.findByNativeIdAndProviderId(GUEST, Objects.requireNonNull(gitLab.getId())))
                .as("a guest grant reads no code")
                .isEmpty();
        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);
        assertThat(groupRoleOf(ANCESTOR)).as("the shared roster").contains(OrganizationMemberRole.ADMIN);
        assertThat(memberships
                        .findByWorkspace_IdAndId_AccountId(other.getId(), ancestorAccount)
                        .map(member -> member.getLeftAt() == null))
                .as("another workspace's members")
                .contains(true);
    }

    @Test
    void shouldChangeNobodyWhenASelectedListingIsIncomplete() {
        selectProjectsOnly();
        String project = COURSE + "/team-1/p";
        select(project, 7101L);
        answer("GetProjectMembers " + project, projectPage(7101L, false, member(STUDENT, "student", 30)));
        answer("GetGroupMembers " + COURSE + "/team-1", page(false));
        assertThat(projection.project(workspace.getId())).isEqualTo(GitLabSelectedMembershipProjection.Outcome.APPLIED);

        // A later project page GitLab could not give.
        answer("GetProjectMembers " + project, projectPage(7101L, true), "{\"data\":{\"project\":null}}");
        assertThat(projection.project(workspace.getId())).isEqualTo(GitLabSelectedMembershipProjection.Outcome.UNKNOWN);

        // A subgroup GitLab no longer lists.
        answer("GetProjectMembers " + project, projectPage(7101L, false));
        answer("GetGroupMembers " + COURSE + "/team-1", "{\"data\":{\"group\":null}}");
        assertThat(projection.project(workspace.getId())).isEqualTo(GitLabSelectedMembershipProjection.Outcome.UNKNOWN);

        // An entry that does not say who the member is.
        answer(
                "GetProjectMembers " + project,
                projectPage(7101L, false, "{\"user\":{\"id\":\"gid://gitlab/User/9\"},\"accessLevel\":null}"));
        assertThat(projection.project(workspace.getId())).isEqualTo(GitLabSelectedMembershipProjection.Outcome.UNKNOWN);

        assertThat(workspaceRoleOf(STUDENT)).contains(WorkspaceRole.MEMBER);
        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);
    }

    @Test
    void shouldChangeNobodyWhenGitLabAnswersForAnotherOrNoProject() {
        selectProjectsOnly();
        String project = COURSE + "/p";
        select(project, 7101L);

        answer("GetProjectMembers " + project, projectPage(7999L, false, member(STUDENT, "student", 30)));
        assertThat(projection.project(workspace.getId())).isEqualTo(GitLabSelectedMembershipProjection.Outcome.UNKNOWN);

        answer("GetProjectMembers " + project, """
            {"data":{"project":{"projectMembers":{"pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[%s]}}}}
            """.formatted(member(STUDENT, "student", 30)));
        assertThat(projection.project(workspace.getId())).isEqualTo(GitLabSelectedMembershipProjection.Outcome.UNKNOWN);

        assertThat(workspaceRoleOf(STUDENT)).isEmpty();
    }

    @Test
    void shouldLeaveOnlyTheOwnerWithoutAskingGitLabWhenNoProjectIsSelected() {
        answer("GetGroupMembers " + COURSE, page(false, member(STUDENT, "student", 30)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);
        assertThat(workspaceRoleOf(STUDENT)).contains(WorkspaceRole.MEMBER);
        selectProjectsOnly();
        relationsAsked.clear();

        assertThat(projection.project(workspace.getId())).isEqualTo(GitLabSelectedMembershipProjection.Outcome.APPLIED);

        assertThat(relationsAsked)
                .as("nothing is selected, so nothing is asked")
                .isEmpty();
        assertThat(workspaceRoleOf(STUDENT)).isEmpty();
        assertThat(groupRoleOf(STUDENT)).contains(OrganizationMemberRole.MEMBER);
        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);
    }

    @Test
    void shouldDiscardAReadWhenTheSelectionOrConnectionChangesWhileGitLabIsAsked() {
        selectProjectsOnly();
        String project = COURSE + "/p";
        String key = "GetProjectMembers " + project;
        RepositoryToMonitor stopped = select(project, 7101L);

        whileAnswering.put(key, () -> monitors.delete(stopped));
        answer(key, projectPage(7101L, false, member(STUDENT, "student", 30)));
        assertThat(projection.project(workspace.getId()))
                .as("Stop")
                .isEqualTo(GitLabSelectedMembershipProjection.Outcome.STALE);

        RepositoryToMonitor readded = select(project, 7101L);
        whileAnswering.put(key, () -> {
            monitors.delete(readded);
            select(project, 7101L);
        });
        answer(key, projectPage(7101L, false, member(STUDENT, "student", 30)));
        assertThat(projection.project(workspace.getId()))
                .as("Stop and Add again")
                .isEqualTo(GitLabSelectedMembershipProjection.Outcome.STALE);

        whileAnswering.put(key, () -> {
            Connection connection = connections
                    .findActive(workspace.getId(), IntegrationKind.GITLAB)
                    .orElseThrow();
            connection.setDisplayName("renamed");
            connections.save(connection);
        });
        answer(key, projectPage(7101L, false, member(STUDENT, "student", 30)));
        assertThat(projection.project(workspace.getId()))
                .as("a connection change")
                .isEqualTo(GitLabSelectedMembershipProjection.Outcome.STALE);
        assertThat(workspaceRoleOf(STUDENT)).isEmpty();

        answer(key, projectPage(7101L, false, member(STUDENT, "student", 30)));
        assertThat(projection.project(workspace.getId())).isEqualTo(GitLabSelectedMembershipProjection.Outcome.APPLIED);
        assertThat(workspaceRoleOf(STUDENT)).contains(WorkspaceRole.MEMBER);

        whileAnswering.put(key, () -> {
            workspace.setRepositorySelection(RepositorySelection.ALL);
            workspace = workspaces.save(workspace);
        });
        answer(key, projectPage(7101L, false));
        assertThat(projection.project(workspace.getId()))
                .as("a selection change")
                .isEqualTo(GitLabSelectedMembershipProjection.Outcome.STALE);
        assertThat(workspaceRoleOf(STUDENT))
                .as("a discarded read removes nobody")
                .contains(WorkspaceRole.MEMBER);
    }

    @Test
    void shouldReturnToTheGroupRosterOnceTheWorkspaceFollowsEveryProjectAgain() {
        selectProjectsOnly();
        answer("GetGroupMembers " + COURSE, page(false, member(STUDENT, "student", 30)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);
        assertThat(workspaceRoleOf(STUDENT)).isEmpty();

        workspace.setRepositorySelection(RepositorySelection.ALL);
        workspace = workspaces.save(workspace);
        assertThat(projection.project(workspace.getId()))
                .isEqualTo(GitLabSelectedMembershipProjection.Outcome.NOT_SELECTED);
        answer("GetGroupMembers " + COURSE, page(false, member(STUDENT, "student", 30)));
        memberSync.syncGroupMemberships(workspace.getId(), COURSE, course);

        assertThat(workspaceRoleOf(STUDENT)).contains(WorkspaceRole.MEMBER);
    }

    @Test
    void shouldNotEnrollFromARosterSyncThatReadTheWorkspaceBeforeItSelectedItsProjects() throws Exception {
        User student = users.save(TestUserFactory.createUser(STUDENT, "student", gitLab));
        roster.upsertMembership(course.getId(), student.getId(), OrganizationMemberRole.MEMBER);
        assertThat(workspaceRoleOf(student)).isEmpty();
        long workspaceId = workspace.getId();
        long connectionId = Objects.requireNonNull(connections
                .findActive(workspaceId, IntegrationKind.GITLAB)
                .orElseThrow()
                .getId());

        // The selection change holds the connection's lifecycle lock and the workspace row, uncommitted,
        // while a roster sync that started from the committed ALL state runs into it.
        var release = new CountDownLatch(1);
        var holder = new CompletableFuture<Integer>();
        try (var threads = Executors.newFixedThreadPool(2)) {
            var selecting = threads.submit(() -> {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        connections.acquireLifecycleLock(connectionId, workspaceId);
                        Workspace selected = workspaces.findById(workspaceId).orElseThrow();
                        selected.setRepositorySelection(RepositorySelection.SELECTED);
                        workspaces.saveAndFlush(selected);
                        holder.complete(
                                Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                        awaitRelease(release);
                    });
                } catch (RuntimeException error) {
                    holder.completeExceptionally(error);
                    throw error;
                }
            });
            try {
                int holderPid = holder.get(20, SECONDS);
                var contender = new CompletableFuture<Integer>();
                var syncing = threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                    contender.complete(
                            Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                    organizationMembershipListener.onOrganizationMembershipsSynced(
                            new OrganizationSyncedEvent(course.getId(), COURSE, true));
                }));
                int contenderPid = contender.get(20, SECONDS);
                await().atMost(Duration.ofSeconds(20))
                        .untilAsserted(
                                () -> assertThat(jdbc.queryForObject("""
                                        WITH RECURSIVE blockers(pid) AS (
                                            SELECT unnest(pg_blocking_pids(?))
                                            UNION
                                            SELECT unnest(pg_blocking_pids(b.pid)) FROM blockers b
                                        )
                                        SELECT EXISTS(SELECT 1 FROM blockers WHERE pid=?)
                                        """, Boolean.class, contenderPid, holderPid))
                                        .isTrue());
                release.countDown();
                selecting.get(20, SECONDS);
                syncing.get(20, SECONDS);
            } finally {
                release.countDown();
            }
        }

        assertThat(workspaces
                        .findMonitorPolicy(workspaceId)
                        .map(WorkspaceRepository.MonitorPolicy::getRepositorySelection))
                .contains(RepositorySelection.SELECTED);
        assertThat(workspaceRoleOf(student))
                .as("a roster read before the selection committed grants nothing after it")
                .isEmpty();
        assertThat(groupRoleOf(STUDENT)).as("the shared roster").contains(OrganizationMemberRole.MEMBER);
        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);
    }

    @Test
    void shouldNotEnrollFromAGroupRosterOnceTheConnectionNamesAnotherGroup() {
        User student = users.save(TestUserFactory.createUser(STUDENT, "student", gitLab));
        roster.upsertMembership(course.getId(), student.getId(), OrganizationMemberRole.MEMBER);
        assertThat(workspaceRoleOf(student)).isEmpty();
        OrganizationSyncedEvent queued = new OrganizationSyncedEvent(course.getId(), COURSE, true);

        // The active connection now reads another GitLab group; a roster of the linked one is an old source.
        connectToGroup(COURSE_ID + 100);
        organizationMembershipListener.onOrganizationMembershipsSynced(queued);

        assertThat(workspaceRoleOf(student))
                .as("a roster of a group the connection no longer names grants nothing")
                .isEmpty();
        assertThat(groupRoleOf(STUDENT)).as("the shared roster").contains(OrganizationMemberRole.MEMBER);
        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);

        connectToGroup(COURSE_ID);
        organizationMembershipListener.onOrganizationMembershipsSynced(queued);

        assertThat(workspaceRoleOf(student)).contains(WorkspaceRole.MEMBER);
    }

    /** Records the GitLab group the workspace's active connection reads, as a connection lifecycle update does. */
    private void connectToGroup(long groupId) {
        assertThat(connectionService.updateConfig(
                        workspace.getId(),
                        IntegrationKind.GITLAB,
                        config -> config instanceof ConnectionConfig.GitLabConfig gitLabConfig
                                ? gitLabConfig.withGitlabGroupId(groupId)
                                : config))
                .isPresent();
    }

    @ParameterizedTest(name = "connection names the new group by id: {0}")
    @ValueSource(booleans = {true, false})
    void shouldNotEnrollFromTheOldGroupsRosterOnceTheWorkspaceIsRelinkedWhileItWaits(boolean connectionNamesGroup)
            throws Exception {
        User student = users.save(TestUserFactory.createUser(STUDENT, "student", gitLab));
        roster.upsertMembership(course.getId(), student.getId(), OrganizationMemberRole.MEMBER);
        assertThat(workspaceRoleOf(student)).isEmpty();
        long replacementNativeId = COURSE_ID + 100;
        Organization replacement = new Organization();
        replacement.setNativeId(replacementNativeId);
        replacement.setLogin(COURSE + "-next");
        replacement.setName("intro");
        replacement.setAvatarUrl("");
        replacement.setHtmlUrl(GITLAB_URL + "/" + COURSE + "-next");
        replacement.setCreatedAt(Instant.now());
        replacement.setUpdatedAt(Instant.now());
        replacement.setProvider(gitLab);
        long replacementId = organizations.save(replacement).getId();
        long workspaceId = workspace.getId();
        long connectionId = Objects.requireNonNull(connections
                .findActive(workspaceId, IntegrationKind.GITLAB)
                .orElseThrow()
                .getId());

        // The relink holds the connection's lifecycle lock, uncommitted: the old group moves aside, the new group
        // takes its path, and the workspace and its connection now name the new group. A roster sync of the old
        // group that started from the committed state runs into it.
        var release = new CountDownLatch(1);
        var holder = new CompletableFuture<Integer>();
        try (var threads = Executors.newFixedThreadPool(2)) {
            var relinking = threads.submit(() -> {
                try {
                    transactionTemplate.executeWithoutResult(status -> {
                        connections.acquireLifecycleLock(connectionId, workspaceId);
                        Organization retired =
                                organizations.findById(course.getId()).orElseThrow();
                        retired.setLogin(COURSE + "-retired");
                        organizations.saveAndFlush(retired);
                        Organization next =
                                organizations.findById(replacementId).orElseThrow();
                        next.setLogin(COURSE);
                        organizations.saveAndFlush(next);
                        Workspace relinked = workspaces.findById(workspaceId).orElseThrow();
                        relinked.setOrganizationId(replacementId);
                        workspaces.saveAndFlush(relinked);
                        assertThat(connectionService.updateConfig(
                                        workspaceId,
                                        IntegrationKind.GITLAB,
                                        config -> config instanceof ConnectionConfig.GitLabConfig gitLabConfig
                                                ? gitLabConfig.withGitlabGroupId(
                                                        connectionNamesGroup ? replacementNativeId : null)
                                                : config))
                                .isPresent();
                        holder.complete(
                                Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                        awaitRelease(release);
                    });
                } catch (RuntimeException | AssertionError error) {
                    holder.completeExceptionally(error);
                    throw error;
                }
            });
            try {
                int holderPid = holder.get(20, SECONDS);
                var contender = new CompletableFuture<Integer>();
                var syncing = threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                    contender.complete(
                            Objects.requireNonNull(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
                    organizationMembershipListener.onOrganizationMembershipsSynced(
                            new OrganizationSyncedEvent(course.getId(), COURSE, true));
                }));
                int contenderPid = contender.get(20, SECONDS);
                await().atMost(Duration.ofSeconds(20))
                        .untilAsserted(
                                () -> assertThat(jdbc.queryForObject("""
                                        WITH RECURSIVE blockers(pid) AS (
                                            SELECT unnest(pg_blocking_pids(?))
                                            UNION
                                            SELECT unnest(pg_blocking_pids(b.pid)) FROM blockers b
                                        )
                                        SELECT EXISTS(SELECT 1 FROM blockers WHERE pid=?)
                                        """, Boolean.class, contenderPid, holderPid))
                                        .isTrue());
                release.countDown();
                relinking.get(20, SECONDS);
                syncing.get(20, SECONDS);
            } finally {
                release.countDown();
            }
        }

        assertThat(workspaces.findById(workspaceId).map(Workspace::getOrganizationId))
                .contains(replacementId);
        assertThat(workspaceRoleOf(student))
                .as("the old group's roster grants nothing once the workspace reads another group")
                .isEmpty();
        assertThat(organizations.findById(course.getId()))
                .as("the old group stays")
                .isPresent();
        assertThat(groupRoleOf(STUDENT)).as("the old group's shared roster").contains(OrganizationMemberRole.MEMBER);
        assertThat(workspaceRoleOf(owner)).contains(WorkspaceRole.OWNER);

        roster.upsertMembership(replacementId, student.getId(), OrganizationMemberRole.MEMBER);
        organizationMembershipListener.onOrganizationMembershipsSynced(
                new OrganizationSyncedEvent(replacementId, COURSE, true));

        assertThat(workspaceRoleOf(student)).contains(WorkspaceRole.MEMBER);
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(20, SECONDS)) {
                throw new IllegalStateException("Selection transaction was not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Selection transaction interrupted", e);
        }
    }

    /** A monitor that selects the project with this id at this path. */
    private RepositoryToMonitor select(String path, long nativeId) {
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setNameWithOwner(path);
        monitor.setNativeId(nativeId);
        monitor.setWorkspace(workspace);
        return monitors.save(monitor);
    }

    private static String projectPage(long projectId, boolean hasNextPage, String... members) {
        return """
            {"data":{"project":{"id":"gid://gitlab/Project/%d","projectMembers":{"pageInfo":{"hasNextPage":%s,
             "endCursor":%s},"nodes":[%s]}}}}
            """.formatted(projectId, hasNextPage, hasNextPage ? "\"next\"" : "null", String.join(",", members));
    }

    /** The workspace follows only the projects it monitors, as an admin stores it. */
    private void selectProjectsOnly() {
        workspace.setRepositorySelection(RepositorySelection.SELECTED);
        workspace = workspaces.save(workspace);
    }

    private static PullRequest mergeRequest(User author, String baseBranch) {
        PullRequest mergeRequest = new PullRequest();
        mergeRequest.setAuthor(author);
        mergeRequest.setBaseRefName(baseBranch);
        mergeRequest.setDraft(false);
        return mergeRequest;
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

    private Set<Long> eligibleAccounts() {
        return reviewSelection.eligibleAccountIds(workspace.getId());
    }

    private Optional<WorkspaceRole> workspaceRoleOf(long nativeId) {
        return users.findByNativeIdAndProviderId(nativeId, Objects.requireNonNull(gitLab.getId()))
                .flatMap(user -> workspaceRoleOf(user));
    }

    private Optional<WorkspaceRole> workspaceRoleOf(User user) {
        return accountId(user)
                .flatMap(id -> memberships.findByWorkspace_IdAndId_AccountId(workspace.getId(), id))
                .filter(member -> member.getLeftAt() == null)
                .map(Membership::getRole);
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
            Optional.ofNullable(whileAnswering.remove(lastRequest)).ifPresent(Runnable::run);
            String answer = Optional.ofNullable(answers.get(lastRequest))
                    .map(Deque::poll)
                    .orElse(null);
            assertThat(answer)
                    .as("GitLab was asked more than the test answered:" + " %s", lastRequest)
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
                lastRequest = (name.find() ? name.group(1) : "")
                        + " "
                        + request.getVariables().get("fullPath");
                Object relations = request.getVariables().get("relations");
                if (relations != null) {
                    relationsAsked.put(lastRequest, relations);
                }
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

    @Autowired
    private AccountIdentityQuery accountIdentities;

    @Autowired
    private PeopleRegistry people;

    private long ensureAccount(User user) {
        return people.ensureHuman(
                new PersonIdentity(
                        Objects.requireNonNull(user.getProvider().getId()), Long.toString(user.getNativeId()), null),
                user.getLogin());
    }

    private long accountId(long mirrorId) {
        var user = users.findById(mirrorId).orElseThrow();
        return accountId(user).orElseThrow();
    }

    private Optional<Long> accountId(User user) {
        return accountIdentities.resolveAccountId(
                Objects.requireNonNull(user.getProvider().getId()), Long.toString(user.getNativeId()), null);
    }
}
