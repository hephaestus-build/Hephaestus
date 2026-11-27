package de.tum.cit.aet.hephaestus.integration.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataService;
import de.tum.cit.aet.hephaestus.testconfig.AccountFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.HeldTransaction;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How a submitted version ends, and which writes the database refuses. The rows are written with SQL, because nothing
 * writes them yet.
 */
class TeamSubmissionStorageIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

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
    private TeamItemVersionRepository versions;

    private Workspace course;
    private Workspace otherCourse;
    private long student;
    private long classmate;
    private UUID team;
    private long board;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        course = workspace("course");
        otherCourse = workspace("other-course");
        student = account(Account.Status.ACTIVE);
        classmate = account(Account.Status.ACTIVE);
        team = team(course, student, classmate);
        board = item(course, team);
    }

    @Test
    void shouldEndEachVersionOnceAndNeverKeepOrRegainItsTitle() {
        UUID first = version(course, board, student);
        UUID second = version(course, board, classmate);

        assertThatThrownBy(() -> set(first, "state = 'WITHDRAWN'"))
                .as("ended, but the title kept")
                .isInstanceOf(DataIntegrityViolationException.class);
        end(first, "WITHDRAWN");

        assertThat(state(first)).isEqualTo("WITHDRAWN");
        assertThat(title(second)).isEqualTo("Cup return");
        for (String change :
                List.of("title = 'Cup return'", "state = 'AVAILABLE', title = 'Cup return'", "state = 'EXPIRED'")) {
            assertThatThrownBy(() -> set(first, change)).as(change).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> set(second, "title = 'Another'")).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** An erasure finds a person's work by the submitter, so nothing else may remove that reference. */
    @Test
    void shouldKeepTheSubmitterOfAnEndedVersionUntilTheirErasure() {
        UUID mine = version(course, board, student);
        end(mine, "WITHDRAWN");

        assertThatThrownBy(() -> set(mine, "submitted_by = NULL")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> set(mine, "state = 'ERASED'")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(submitter(mine)).isEqualTo(student);
    }

    /** The key sets the submitter to null on an account delete, and the submitter check refuses that before erasure. */
    @Test
    void shouldRefuseToDeleteAnAccountUntilItsSubmissionsAreErased() {
        UUID mine = version(course, board, student);
        // The membership key holds the account first. Without the membership the version is withdrawn, not erased.
        jdbc.update("DELETE FROM membership WHERE account_id = ?", student);
        assertThat(state(mine)).isEqualTo("WITHDRAWN");

        assertThatThrownBy(() -> deleteAccount(student))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_team_item_version_submitter");
        assertThat(submitter(mine)).isEqualTo(student);

        transactions.executeWithoutResult(status -> versions.eraseSubmitter(student));
        deleteAccount(student);

        assertThat(count("SELECT count(*) FROM account WHERE id = ?", student)).isZero();
        assertThat(state(mine)).isEqualTo("ERASED");
        assertThat(submitter(mine)).isNull();
    }

    @Test
    void shouldEndEveryVersionOfAnErasedAccountAndRemoveTheAccountFromIt() {
        join(otherCourse, student);
        long elsewhere = item(otherCourse, team(otherCourse, student));
        UUID withdrawn = version(course, board, student);
        end(withdrawn, "WITHDRAWN");
        UUID available = version(course, board, student);
        UUID theirs = version(course, board, classmate);
        UUID abroad = version(otherCourse, elsewhere, student);

        personData.eraseForAccount(student);

        for (UUID mine : List.of(withdrawn, available, abroad)) {
            assertThat(state(mine)).isEqualTo("ERASED");
            assertThat(submitter(mine)).isNull();
        }
        assertThat(state(theirs)).isEqualTo("AVAILABLE");
        assertThat(submitter(theirs)).isEqualTo(classmate);
        assertThat(title(theirs)).isEqualTo("Cup return");
        for (UUID mine : List.of(available, abroad)) assertThat(title(mine)).isNull();
    }

    @Test
    void shouldWithdrawTheVersionsOfAPersonWhoLeavesAndNoOthers() {
        join(otherCourse, student);
        long elsewhere = item(otherCourse, team(otherCourse, student));
        long other = item(course, team(course, student));
        UUID removedFromTeam = version(course, other, student);
        UUID left = version(course, board, student);
        UUID theirs = version(course, board, classmate);
        UUID abroad = version(otherCourse, elsewhere, student);

        jdbc.update(
                "DELETE FROM project_team_member WHERE workspace_id = ? AND team_id ="
                        + " (SELECT team_id FROM team_item WHERE id = ? AND workspace_id = ?) AND account_id = ?",
                id(course),
                other,
                id(course),
                student);
        assertThat(state(removedFromTeam)).isEqualTo("WITHDRAWN");
        assertThat(state(left)).isEqualTo("AVAILABLE");

        memberships.leave(id(course), student);

        assertThat(state(left)).isEqualTo("WITHDRAWN");
        assertThat(state(theirs)).isEqualTo("AVAILABLE");
        assertThat(state(abroad)).isEqualTo("AVAILABLE");
        assertThat(title(left)).isNull();
        assertThat(title(theirs)).isEqualTo("Cup return");
    }

    @Test
    void shouldWithdrawTheVersionsOfARemovedItemOrTeamAndKeepItRemoved() {
        UUID onBoard = version(course, board, student);
        long second = item(course, team);
        UUID onSecond = version(course, second, student);
        long kept = item(course, team(course, classmate));
        UUID theirs = version(course, kept, classmate);

        remove(board);
        assertThat(state(onBoard)).isEqualTo("WITHDRAWN");
        assertThat(state(onSecond)).isEqualTo("AVAILABLE");

        jdbc.update("UPDATE project_team SET removed_at = now() WHERE id = ? AND workspace_id = ?", team, id(course));

        assertThat(state(onSecond)).isEqualTo("WITHDRAWN");
        assertThat(state(theirs)).isEqualTo("AVAILABLE");
        assertThat(title(onSecond)).isNull();
        assertThatThrownBy(() -> version(course, board, student)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> item(course, team)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE team_item SET removed_at = NULL WHERE id = ? AND workspace_id = ?", board, id(course)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldDeleteThePurgedWorkspacesItemsAndNoOthers() {
        join(otherCourse, student);
        long elsewhere = item(otherCourse, team(otherCourse, student));
        version(course, board, student);
        version(course, board, classmate);
        UUID abroad = version(otherCourse, elsewhere, student);

        lifecycle.purgeWorkspace(course.getWorkspaceSlug());

        assertThat(count("SELECT count(*) FROM team_item WHERE workspace_id = ?", id(course)))
                .isZero();
        assertThat(count("SELECT count(*) FROM team_item_version WHERE workspace_id = ?", id(course)))
                .isZero();
        assertThat(state(abroad)).isEqualTo("AVAILABLE");
    }

    @Test
    void shouldRefuseAVersionThatNoCurrentMemberWithAnActiveAccountSubmits() {
        long outsider = account(Account.Status.ACTIVE);
        join(course, outsider);
        long blocked = account(Account.Status.BLOCKED);
        join(course, blocked);
        addToTeam(course, team, blocked);
        long foreignItem = item(otherCourse, team(otherCourse));

        for (long refused : new long[] {outsider, blocked}) {
            assertThatThrownBy(() -> version(course, board, refused))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> version(course, foreignItem, student))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> item(otherCourse, team)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldKeepTheIdentityOfAnItemAndAVersion() {
        UUID version = version(course, board, student);
        UUID otherTeam = team(course, student);

        for (String change : List.of(
                "submitted_by = " + classmate,
                "sequence_number = 9",
                "request_key = gen_random_uuid()",
                "submitted_at = now() + interval '1 minute'",
                "expires_at = expires_at + interval '1 day'",
                "created_in_transaction = 1",
                "submitted_by = NULL")) {
            assertThatThrownBy(() -> jdbc.update(
                            "UPDATE team_item_version SET " + change + " WHERE id = ? AND workspace_id = ?",
                            version,
                            id(course)))
                    .as(change)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE team_item SET team_id = ? WHERE id = ? AND workspace_id = ?",
                        otherTeam,
                        board,
                        id(course)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.update(
                        "UPDATE team_item_version SET expires_at = expires_at - interval '1 day'"
                                + " WHERE id = ? AND workspace_id = ?",
                        version,
                        id(course)))
                .isOne();
    }

    @Test
    void shouldRefuseAVersionWhoseSubmitterLeavesWhileItIsInserted() throws Exception {
        var leaving = HeldTransaction.holdIn(
                transactions,
                jdbc,
                () -> jdbc.update(
                        "UPDATE membership SET left_at = now() WHERE workspace_id = ? AND account_id = ?",
                        id(course),
                        student));

        var insert = CompletableFuture.runAsync(() -> version(course, board, student));
        leaving.commitAfter(insert);

        assertThatThrownBy(insert::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldWithdrawAVersionThatIsInsertedWhileItsSubmitterLeaves() throws Exception {
        UUID id = UUID.randomUUID();
        var submitting = HeldTransaction.holdIn(transactions, jdbc, () -> insertVersion(id, course, board, student));

        var leave = CompletableFuture.runAsync(() -> memberships.leave(id(course), student));
        submitting.commitAfter(leave);
        leave.join();

        assertThat(state(id)).isEqualTo("WITHDRAWN");
    }

    @Test
    void shouldEraseAVersionThatIsInsertedWhileItsAccountIsErased() throws Exception {
        UUID id = UUID.randomUUID();
        var submitting = HeldTransaction.holdIn(transactions, jdbc, () -> insertVersion(id, course, board, student));

        var erasure = CompletableFuture.runAsync(() -> personData.eraseForAccount(student));
        submitting.commitAfter(erasure);
        erasure.join();

        assertThat(state(id)).isEqualTo("ERASED");
        assertThat(submitter(id)).isNull();
    }

    private Workspace workspace(String name) {
        return workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                name + "-" + UUID.randomUUID().toString().substring(0, 8)));
    }

    private long account(Account.Status status) {
        return Objects.requireNonNull(
                accounts.save(AccountFixtures.withStatus("Student", status)).getId());
    }

    private void join(Workspace workspace, long account) {
        memberships.createMembership(workspace, account, WorkspaceRole.MEMBER);
    }

    private static long id(Workspace workspace) {
        return Objects.requireNonNull(workspace.getId());
    }

    /** A team of these accounts, who join the workspace where they are not in it yet. */
    private UUID team(Workspace workspace, long... accountIds) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO project_team(id, workspace_id, name, created_at) VALUES (?, ?, 'Cups', now())",
                id,
                id(workspace));
        for (long account : accountIds) {
            if (count(
                            "SELECT count(*) FROM membership WHERE workspace_id = " + id(workspace)
                                    + " AND account_id = ?",
                            account)
                    == 0) join(workspace, account);
            addToTeam(workspace, id, account);
        }
        return id;
    }

    private void addToTeam(Workspace workspace, UUID teamId, long account) {
        jdbc.update(
                "INSERT INTO project_team_member(id, workspace_id, team_id, account_id, joined_at)"
                        + " VALUES (gen_random_uuid(), ?, ?, ?, now())",
                id(workspace),
                teamId,
                account);
    }

    private long item(Workspace workspace, UUID teamId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO team_item(workspace_id, team_id, kind, created_at)"
                        + " VALUES (?, ?, 'team.board', now()) RETURNING id",
                Long.class,
                id(workspace),
                teamId));
    }

    private UUID version(Workspace workspace, long item, long account) {
        UUID id = UUID.randomUUID();
        insertVersion(id, workspace, item, account);
        return id;
    }

    private void insertVersion(UUID id, Workspace workspace, long item, long account) {
        jdbc.update(
                "INSERT INTO team_item_version(id, workspace_id, item_id, sequence_number, submitted_by, submitted_at,"
                        + " request_key, state, expires_at, title) VALUES (?, ?, ?, (SELECT count(*) + 1 FROM"
                        + " team_item_version WHERE item_id = ? AND workspace_id = ?), ?, now(), gen_random_uuid(),"
                        + " 'AVAILABLE', now() + interval '30 days', 'Cup return')",
                id,
                id(workspace),
                item,
                item,
                id(workspace),
                account);
    }

    /** Ends a version as every writer must: its title goes in the same statement. */
    private void end(UUID version, String state) {
        set(version, "state = '" + state + "', title = NULL");
    }

    private void set(UUID version, String change) {
        jdbc.update(
                "UPDATE team_item_version SET " + change
                        + " WHERE id = ? AND workspace_id IN (SELECT id FROM workspace)",
                version);
    }

    private void deleteAccount(long account) {
        jdbc.update("DELETE FROM account WHERE id = ?", account);
    }

    private void remove(long item) {
        jdbc.update("UPDATE team_item SET removed_at = now() WHERE id = ? AND workspace_id = ?", item, id(course));
    }

    private String state(UUID version) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT state FROM team_item_version WHERE id = ? AND workspace_id IN (SELECT id FROM workspace)",
                String.class,
                version));
    }

    private @Nullable Long submitter(UUID version) {
        return jdbc.queryForObject(
                "SELECT submitted_by FROM team_item_version WHERE id = ? AND workspace_id IN (SELECT id FROM workspace)",
                Long.class,
                version);
    }

    private @Nullable String title(UUID version) {
        return jdbc.queryForObject(
                "SELECT title FROM team_item_version WHERE id = ? AND workspace_id IN (SELECT id FROM workspace)",
                String.class,
                version);
    }

    private long count(String sql, Object argument) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, argument));
    }
}
