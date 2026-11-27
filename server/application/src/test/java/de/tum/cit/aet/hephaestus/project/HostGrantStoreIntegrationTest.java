package de.tum.cit.aet.hephaestus.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.auth.AuthSessionService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.testconfig.AccountFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every way that a host's team grant and its pairing request end before anything writes them (ADR 0166 rule 6). The
 * rows are written with SQL, because nothing writes them yet.
 */
class HostGrantStoreIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private MembershipService memberships;

    @Autowired
    private PersonDataService personData;

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    @Autowired
    private AuthSessionService sessions;

    @Autowired
    private AccountService accountService;

    @Autowired
    private HostGrantRepository grantRepository;

    private Workspace course;
    private Workspace otherCourse;
    private long student;
    private long classmate;
    private UUID team;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        course = workspace("course");
        otherCourse = workspace("other-course");
        student = account();
        classmate = account();
        memberships.createMembership(course, student, WorkspaceRole.MEMBER);
        memberships.createMembership(course, classmate, WorkspaceRole.MEMBER);
        team = team(course, student, classmate);
    }

    @Test
    void shouldRevokeTheGrantsAndRefuseTheApprovedPairingsOfAnAccountThatSignsOutEverywhere() {
        grant(course, team, student);
        approvedPairing(student);
        UUID pending = pendingPairing();
        UUID classmatesGrant = grant(course, team, classmate);
        UUID classmatesPairing = approvedPairing(classmate);

        sessions.revokeAllExcept(student, UUID.randomUUID(), null);

        assertThat(openGrants(student)).isZero();
        assertThat(approvedPairings(student)).isZero();
        assertThat(pairingExists(pending))
                .as("a pending request names no account")
                .isTrue();
        assertThat(isOpen(classmatesGrant)).isTrue();
        assertThat(pairingExists(classmatesPairing)).isTrue();
    }

    @Test
    void shouldRevokeTheHostGrantAlsoWhenTheCallerKeepsItsOwnInstalledClientSession() {
        grant(course, team, student);
        approvedPairing(student);

        sessions.revokeAllExcept(student, UUID.randomUUID(), UUID.randomUUID());

        assertThat(openGrants(student)).isZero();
        assertThat(approvedPairings(student)).isZero();
    }

    @Test
    void shouldRevokeTheGrantsAndRefuseTheApprovedPairingsOfABlockedAccount() {
        long admin = account();
        grant(course, team, student);
        approvedPairing(student);

        accountService.adminSetStatus(student, Account.Status.BLOCKED, admin);

        assertThat(openGrants(student)).isZero();
        assertThat(approvedPairings(student)).isZero();
    }

    @Test
    void shouldDeleteTheGrantsOfAMemberWhoLeavesTheTeamOrTheWorkspace() {
        memberships.createMembership(otherCourse, student, WorkspaceRole.MEMBER);
        UUID elsewhere = team(otherCourse, student);
        UUID otherTeam = team(course, student);
        grant(course, team, student);
        UUID kept = grant(course, otherTeam, student);
        UUID keptElsewhere = grant(otherCourse, elsewhere, student);

        jdbc.update(
                "DELETE FROM project_team_member WHERE team_id = ? AND workspace_id = ? AND account_id = ?",
                team,
                id(course),
                student);

        assertThat(grants("team_id = ?", team)).isZero();
        assertThat(isOpen(kept)).isTrue();

        memberships.leave(id(course), student);

        assertThat(grants("workspace_id = ?", id(course))).isZero();
        assertThat(isOpen(keptElsewhere)).isTrue();
    }

    @Test
    void shouldRefuseAnApprovedPairingWhenItsApproverLeavesTheWorkspace() {
        UUID approved = approvedPairing(student);
        UUID classmatesPairing = approvedPairing(classmate);

        memberships.leave(id(course), student);

        assertThat(pairingExists(approved)).isFalse();
        assertThat(pairingExists(classmatesPairing)).isTrue();
    }

    @Test
    void shouldRefuseAnApprovedPairingWhenItsApproverLeavesATeamAndKeepAPendingOne() {
        UUID approved = approvedPairing(student);
        UUID classmatesPairing = approvedPairing(classmate);
        UUID pending = pendingPairing();

        jdbc.update(
                "DELETE FROM project_team_member WHERE team_id = ? AND workspace_id = ? AND account_id = ?",
                team,
                id(course),
                student);

        assertThat(pairingExists(approved)).isFalse();
        assertThat(pairingExists(classmatesPairing)).isTrue();
        assertThat(pairingExists(pending))
                .as("a pending request names no account")
                .isTrue();
    }

    @Test
    void shouldDeleteTheGrantsAndPairingsOfAnErasedAccountAndNoOthers() {
        grant(course, team, student);
        approvedPairing(student);
        UUID classmatesGrant = grant(course, team, classmate);
        UUID classmatesPairing = approvedPairing(classmate);

        personData.eraseForAccount(student);

        assertThat(grants("account_id = ?", student)).isZero();
        assertThat(approvedPairings(student)).isZero();
        assertThat(isOpen(classmatesGrant)).isTrue();
        assertThat(pairingExists(classmatesPairing)).isTrue();
    }

    @Test
    void shouldDeleteThePurgedWorkspacesGrantsAndNoOthers() {
        memberships.createMembership(otherCourse, student, WorkspaceRole.MEMBER);
        grant(course, team, student);
        UUID elsewhere = grant(otherCourse, team(otherCourse, student), student);

        lifecycle.purgeWorkspace(course.getWorkspaceSlug());

        assertThat(grants("workspace_id = ?", id(course))).isZero();
        assertThat(isOpen(elsewhere)).isTrue();
    }

    @Test
    void shouldLetAGrantStandOnlyInItsWorkspaceWhileItIsOpenAndItsMemberStaysInAStandingTeam() {
        memberships.createMembership(otherCourse, student, WorkspaceRole.MEMBER);
        UUID open = grant(course, team, student);
        UUID revoked = grant(course, team, student);
        UUID expired = grant(course, team, student);
        UUID removedTeam = team(course, student);
        UUID ofRemovedTeam = grant(course, removedTeam, student);
        UUID elsewhere = grant(otherCourse, team(otherCourse, student), student);
        jdbc.update("UPDATE host_grant SET revoked_at = now() WHERE id = ?", revoked);
        jdbc.update("UPDATE host_grant SET expires_at = now() - interval '1 second' WHERE id = ?", expired);
        jdbc.update("UPDATE project_team SET removed_at = now() WHERE id = ?", removedTeam);

        assertThat(isStanding(course, open)).isTrue();
        assertThat(isStanding(otherCourse, open))
                .as("asked in another workspace")
                .isFalse();
        assertThat(isStanding(course, elsewhere))
                .as("a grant of another workspace")
                .isFalse();
        assertThat(isStanding(course, revoked)).isFalse();
        assertThat(isStanding(course, expired)).isFalse();
        assertThat(isStanding(course, ofRemovedTeam)).as("a removed team").isFalse();
        assertThat(isStanding(course, UUID.randomUUID())).isFalse();
    }

    @Test
    void shouldRefuseAGrantForANonMemberOrAcrossWorkspacesAndAnApprovalWithoutAnAccount() {
        long outsider = account();
        memberships.createMembership(course, outsider, WorkspaceRole.MEMBER);
        memberships.createMembership(otherCourse, student, WorkspaceRole.MEMBER);
        UUID elsewhere = team(otherCourse, student);

        assertThatThrownBy(() -> grant(course, team, outsider))
                .as("a workspace member outside the team")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> grant(course, elsewhere, student))
                .as("a team of another workspace")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO host_pairing(id, code_hash, code_challenge, host_kind, status,"
                                + " created_at, expires_at) VALUES (?, ?, 'challenge', 'FIGJAM', 'APPROVED', now(),"
                                + " now() + interval '5 minutes')",
                        UUID.randomUUID(),
                        hash()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO host_pairing(id, code_hash, code_challenge, host_kind, status, approved_account_id,"
                                + " created_at, expires_at) VALUES (?, ?, 'challenge', 'FIGJAM', 'PENDING', ?, now(),"
                                + " now() + interval '5 minutes')",
                        UUID.randomUUID(),
                        hash(),
                        student))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private boolean isStanding(Workspace workspace, UUID grant) {
        return grantRepository.isStanding(id(workspace), grant, Instant.now());
    }

    private Workspace workspace(String name) {
        return workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                name + "-" + UUID.randomUUID().toString().substring(0, 8)));
    }

    private long account() {
        return Objects.requireNonNull(accounts.save(AccountFixtures.withStatus("Student", Account.Status.ACTIVE))
                .getId());
    }

    private static long id(Workspace workspace) {
        return Objects.requireNonNull(workspace.getId());
    }

    private UUID team(Workspace workspace, long... accountIds) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO project_team(id, workspace_id, name, created_at) VALUES (?, ?, 'Cup return', now())",
                id,
                id(workspace));
        for (long account : accountIds) {
            jdbc.update(
                    "INSERT INTO project_team_member(id, workspace_id, team_id, account_id, joined_at)"
                            + " VALUES (?, ?, ?, ?, now())",
                    UUID.randomUUID(),
                    id(workspace),
                    id,
                    account);
        }
        return id;
    }

    private UUID grant(Workspace workspace, UUID teamId, long account) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO host_grant(id, workspace_id, team_id, account_id, host_kind, token_hash, created_at,"
                        + " expires_at) VALUES (?, ?, ?, ?, 'FIGJAM', ?, now(), now() + interval '60 minutes')",
                id,
                id(workspace),
                teamId,
                account,
                hash());
        return id;
    }

    private UUID approvedPairing(long account) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO host_pairing(id, code_hash, code_challenge, host_kind, status, approved_account_id,"
                        + " created_at, expires_at) VALUES (?, ?, 'challenge', 'FIGMA_DESIGN', 'APPROVED', ?, now(),"
                        + " now() + interval '5 minutes')",
                id,
                hash(),
                account);
        return id;
    }

    private UUID pendingPairing() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO host_pairing(id, code_hash, code_challenge, host_kind, status, created_at, expires_at)"
                        + " VALUES (?, ?, 'challenge', 'APOLLON', 'PENDING', now(), now() + interval '5 minutes')",
                id,
                hash());
        return id;
    }

    private static String hash() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private long openGrants(long account) {
        return count("SELECT count(*) FROM host_grant WHERE account_id = ? AND revoked_at IS NULL", account);
    }

    private boolean isOpen(UUID grant) {
        return count("SELECT count(*) FROM host_grant WHERE id = ? AND revoked_at IS NULL", grant) == 1;
    }

    private long grants(String where, Object argument) {
        return count("SELECT count(*) FROM host_grant WHERE " + where, argument);
    }

    private long approvedPairings(long account) {
        return count("SELECT count(*) FROM host_pairing WHERE approved_account_id = ?", account);
    }

    private boolean pairingExists(UUID pairing) {
        return count("SELECT count(*) FROM host_pairing WHERE id = ?", pairing) == 1;
    }

    private long count(String sql, Object argument) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, argument));
    }
}
