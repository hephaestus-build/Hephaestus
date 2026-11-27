package de.tum.cit.aet.hephaestus.project;

import static de.tum.cit.aet.hephaestus.core.auth.oauth.VerifiedAccountClaimFixture.verifiedClaim;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.PeopleRegistryService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.project.spi.ProjectTeams;
import de.tum.cit.aet.hephaestus.testconfig.AccountFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Who receives a project team's work, and every way a membership ends or moves. The rows are written with SQL, because
 * nothing writes them yet.
 */
class ProjectTeamAuthorityIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ProjectTeams teams;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private MembershipService memberships;

    @Autowired
    private PersonDataService personData;

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    @Autowired
    private PeopleRegistryService people;

    private Workspace course;
    private Workspace otherCourse;
    private long student;
    private long classmate;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        course = workspace("course");
        otherCourse = workspace("other-course");
        student = account(Account.Status.ACTIVE);
        classmate = account(Account.Status.ACTIVE);
        join(course, student, WorkspaceRole.MEMBER);
        join(course, classmate, WorkspaceRole.MEMBER);
    }

    @Test
    void shouldGrantATeamOnlyToItsOwnCurrentMembers() {
        long owner = account(Account.Status.ACTIVE);
        long admin = account(Account.Status.ACTIVE);
        join(course, owner, WorkspaceRole.OWNER);
        join(course, admin, WorkspaceRole.ADMIN);
        join(otherCourse, student, WorkspaceRole.MEMBER);
        UUID team = team(course, student);
        UUID neighbours = team(course, classmate);
        UUID elsewhere = team(otherCourse, student);

        assertThat(isMember(course, team, student)).isTrue();
        assertThat(isMember(otherCourse, elsewhere, student)).isTrue();

        assertThat(isMember(course, team, classmate)).isFalse();
        assertThat(isMember(course, neighbours, student)).isFalse();
        assertThat(isMember(course, team, owner)).as("a workspace owner").isFalse();
        assertThat(isMember(course, team, admin)).as("a workspace admin").isFalse();
        assertThat(isMember(course, UUID.randomUUID(), student)).isFalse();
        assertThat(isMember(course, elsewhere, student)).as("a team of another workspace").isFalse();
        assertThat(isMember(otherCourse, team, student)).isFalse();
        assertThat(isMember(otherCourse, team, classmate)).isFalse();
    }

    @Test
    void shouldGrantNothingForARemovedTeamOrToAPersonRemovedFromIt() {
        UUID removed = team(course, student);
        UUID left = team(course, student);

        jdbc.update("UPDATE project_team SET removed_at = now() WHERE id = ? AND workspace_id = ?", removed, id(course));
        jdbc.update(
                "DELETE FROM project_team_member WHERE team_id = ? AND workspace_id = ? AND account_id = ?",
                left,
                id(course),
                student);

        assertThat(isMember(course, removed, student)).isFalse();
        assertThat(isMember(course, left, student)).isFalse();
    }

    @Test
    void shouldEndTeamMembershipsWithTheWorkspaceMembershipAndNotRestoreThem() {
        join(otherCourse, student, WorkspaceRole.MEMBER);
        UUID team = team(course, student, classmate);
        UUID elsewhere = team(otherCourse, student);

        memberships.leave(id(course), student);

        assertThat(isMember(course, team, student)).isFalse();
        assertThat(rows(course, student)).isZero();
        assertThat(isMember(course, team, classmate)).isTrue();
        assertThat(isMember(otherCourse, elsewhere, student)).isTrue();

        memberships.createMembership(course, student, WorkspaceRole.MEMBER);

        assertThat(isMember(course, team, student)).as("a return is a new act").isFalse();
        assertThat(rows(course, student)).isZero();
    }

    @Test
    void shouldEndTeamMembershipsWhenAnyOtherWriterEndsTheWorkspaceMembership() {
        UUID team = team(course, student);

        // As a provider sync departs a member.
        jdbc.update(
                "UPDATE membership SET left_at = now(), role_source = 'PROVIDER' WHERE workspace_id = ? AND account_id = ?",
                id(course),
                student);

        assertThat(isMember(course, team, student)).isFalse();
        assertThat(rows(course, student)).isZero();
    }

    @Test
    void shouldDeleteAnErasedAccountsTeamMembershipsAndNoOthers() {
        UUID team = team(course, student, classmate);

        personData.eraseForAccount(student);

        assertThat(rows(course, student)).isZero();
        assertThat(isMember(course, team, classmate)).isTrue();
    }

    @Test
    void shouldDeleteThePurgedWorkspacesTeamsAndNoOthers() {
        join(otherCourse, student, WorkspaceRole.MEMBER);
        team(course, student, classmate);
        UUID elsewhere = team(otherCourse, student);

        lifecycle.purgeWorkspace(course.getWorkspaceSlug());

        assertThat(count("SELECT count(*) FROM project_team WHERE workspace_id = ?", id(course)))
                .isZero();
        assertThat(count("SELECT count(*) FROM project_team_member WHERE workspace_id = ?", id(course)))
                .isZero();
        assertThat(isMember(otherCourse, elsewhere, student)).isTrue();
    }

    @Test
    void shouldDeleteTheTeamsOfAnErasedAccountsPersonalSpaceWithTheSpace() {
        Long space = jdbc.queryForObject(
                "INSERT INTO workspace (slug, display_name, account_login, account_type, status, kind,"
                        + " personal_account_id) VALUES ('me--aaaaaaaaaaaaaaaa', 'Personal space',"
                        + " 'me--aaaaaaaaaaaaaaaa', 'USER', 'ACTIVE', 'PERSONAL', ?) RETURNING id",
                Long.class,
                student);
        Workspace personal =
                workspaces.findById(Objects.requireNonNull(space)).orElseThrow();
        join(personal, student, WorkspaceRole.MEMBER);
        team(personal, student);
        UUID shared = team(course, student, classmate);

        personData.eraseForAccount(student);

        assertThat(count("SELECT count(*) FROM workspace WHERE id = ?", space)).isZero();
        assertThat(count("SELECT count(*) FROM project_team WHERE workspace_id = ?", space))
                .isZero();
        assertThat(isMember(course, shared, classmate)).isTrue();
    }

    @Test
    void shouldGiveAClaimingAccountTheTeamsOfAMembershipThatTheClaimMakesCurrentAgain() {
        long unclaimed = account(Account.Status.UNCLAIMED);
        var identity = identity(unclaimed);
        join(course, unclaimed, WorkspaceRole.MEMBER);
        UUID absorbed = team(course, unclaimed);
        UUID own = team(course, student);
        memberships.leave(id(course), student);

        people.absorbUnclaimed(verifiedClaim(student, unclaimed, identity));

        assertThat(isMember(course, absorbed, student)).isTrue();
        assertThat(isMember(course, own, student)).as("ended with the membership").isFalse();
    }

    @Test
    void shouldKeepAClaimingAccountsTeamsWhenTheAbsorbedMembershipHadEnded() {
        long unclaimed = account(Account.Status.UNCLAIMED);
        var identity = identity(unclaimed);
        join(course, unclaimed, WorkspaceRole.MEMBER);
        UUID absorbed = team(course, unclaimed);
        UUID own = team(course, student);
        memberships.leave(id(course), unclaimed);

        people.absorbUnclaimed(verifiedClaim(student, unclaimed, identity));

        assertThat(isMember(course, own, student)).isTrue();
        assertThat(isMember(course, absorbed, student)).isFalse();
    }

    @Test
    void shouldMoveAnAbsorbedAccountsTeamsToTheAccountThatClaimsIt() {
        long unclaimed = account(Account.Status.UNCLAIMED);
        var identity = identity(unclaimed);
        join(course, unclaimed, WorkspaceRole.MEMBER);
        join(otherCourse, unclaimed, WorkspaceRole.MEMBER);
        UUID shared = team(course, student, unclaimed);
        UUID onlyAbsorbed = team(course, unclaimed);
        UUID elsewhere = team(otherCourse, unclaimed);

        people.absorbUnclaimed(verifiedClaim(student, unclaimed, identity));

        assertThat(isMember(course, shared, student)).isTrue();
        assertThat(isMember(course, onlyAbsorbed, student)).isTrue();
        assertThat(isMember(otherCourse, elsewhere, student)).isTrue();
        assertThat(count("SELECT count(*) FROM project_team_member WHERE team_id = ? AND workspace_id = " + id(course), shared))
                .isOne();
        assertThat(rows(course, unclaimed)).isZero();
        assertThat(rows(otherCourse, unclaimed)).isZero();
    }

    @Test
    void shouldRefuseARowWithoutACurrentMembershipOrAcrossWorkspaces() {
        long outsider = account(Account.Status.ACTIVE);
        join(otherCourse, outsider, WorkspaceRole.MEMBER);
        UUID team = team(course);
        UUID elsewhere = team(otherCourse);

        assertThatThrownBy(() -> add(course, team, outsider)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> add(course, elsewhere, student)).isInstanceOf(DataIntegrityViolationException.class);

        add(course, team, student);
        memberships.leave(id(course), student);
        assertThatThrownBy(() -> add(course, team, student)).isInstanceOf(DataIntegrityViolationException.class);

        add(course, team, classmate);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE project_team_member SET account_id = ? WHERE workspace_id = ? AND team_id = ?",
                        student,
                        id(course),
                        team))
                .as("a row moved onto an ended membership")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRefuseARowForAMembershipThatEndsWhileTheRowIsInserted() throws Exception {
        UUID team = team(course);
        var leaving = HeldTransaction.holdIn(
                transactions,
                jdbc,
                () -> jdbc.update(
                        "UPDATE membership SET left_at = now() WHERE workspace_id = ? AND account_id = ?",
                        id(course),
                        student));

        var insert = CompletableFuture.runAsync(() -> add(course, team, student));
        leaving.commitAfter(insert);

        assertThatThrownBy(insert::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
        assertThat(rows(course, student)).isZero();
    }

    @Test
    void shouldDeleteARowThatIsInsertedWhileItsMembershipEnds() throws Exception {
        UUID team = team(course);
        var joining = HeldTransaction.holdIn(transactions, jdbc, () -> add(course, team, student));

        var leave = CompletableFuture.runAsync(() -> jdbc.update(
                "UPDATE membership SET left_at = now() WHERE workspace_id = ? AND account_id = ?",
                id(course),
                student));
        joining.commitAfter(leave);
        leave.join();

        assertThat(rows(course, student)).isZero();
    }

    private Workspace workspace(String name) {
        return workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                name + "-" + UUID.randomUUID().toString().substring(0, 8)));
    }

    private long account(Account.Status status) {
        return Objects.requireNonNull(accounts.save(AccountFixtures.withStatus("Student", status))
                .getId());
    }

    private PersonIdentity identity(long accountId) {
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() ->
                        providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        var identity = new Identity();
        identity.setAccount(accounts.findById(accountId).orElseThrow());
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey("U" + UUID.randomUUID().toString().substring(0, 8));
        identity.setTeamKey("");
        identities.save(identity);
        return PersonIdentity.of(identity.getProviderId(), identity.getProviderUserKey(), identity.getTeamKey());
    }

    private void join(Workspace workspace, long account, WorkspaceRole role) {
        memberships.createMembership(workspace, account, role);
    }

    private static long id(Workspace workspace) {
        return Objects.requireNonNull(workspace.getId());
    }

    private UUID team(Workspace workspace, long... accountIds) {
        UUID team = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO project_team(id, workspace_id, name, created_at) VALUES (?, ?, 'Cup return', now())",
                team,
                id(workspace));
        for (long account : accountIds) add(workspace, team, account);
        return team;
    }

    private void add(Workspace workspace, UUID team, long account) {
        jdbc.update(
                "INSERT INTO project_team_member(id, workspace_id, team_id, account_id, joined_at)"
                        + " VALUES (?, ?, ?, ?, now())",
                UUID.randomUUID(),
                id(workspace),
                team,
                account);
    }

    private boolean isMember(Workspace workspace, UUID team, long account) {
        return teams.isCurrentMember(id(workspace), team, account);
    }

    private long rows(Workspace workspace, long account) {
        return count(
                "SELECT count(*) FROM project_team_member WHERE workspace_id = " + id(workspace) + " AND account_id = ?",
                account);
    }

    private long count(String sql, Object argument) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, argument));
    }
}
