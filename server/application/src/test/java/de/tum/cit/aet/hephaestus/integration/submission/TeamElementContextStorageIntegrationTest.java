package de.tum.cit.aet.hephaestus.integration.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The elements of a snapshot and the note of a version: when the database accepts them, that they never change, and that
 * they go with their version. The rows are written with SQL, because nothing writes them yet.
 */
class TeamElementContextStorageIntegrationTest extends BaseIntegrationTest {

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

    private Workspace course;
    private long student;
    private long classmate;
    private UUID team;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        course = workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                "course-" + UUID.randomUUID().toString().substring(0, 8)));
        student = member();
        classmate = member();
        team = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO project_team(id, workspace_id, name, created_at) VALUES (?, ?, 'Cups', now())",
                team,
                workspace());
        for (long account : new long[] {student, classmate}) {
            jdbc.update(
                    "INSERT INTO project_team_member(id, workspace_id, team_id, account_id, joined_at)"
                            + " VALUES (gen_random_uuid(), ?, ?, ?, now())",
                    workspace(),
                    team,
                    account);
        }
    }

    @Test
    void shouldDeleteTheElementsAndTheNoteOfAVersionThatEndsAndNoOther() {
        List<Consumer<Submitted>> endings = List.of(
                ended -> end(ended.version(), "WITHDRAWN"),
                ended -> end(ended.version(), "EXPIRED"),
                ended -> jdbc.update(
                        "UPDATE team_item SET removed_at = now() WHERE id = ? AND workspace_id = ?",
                        ended.item(),
                        workspace()),
                ended -> jdbc.update(
                        "DELETE FROM team_item_version WHERE id = ? AND workspace_id = ?",
                        ended.version(),
                        workspace()));
        for (Consumer<Submitted> ending : endings) {
            Submitted ended = submitted(student);
            Submitted kept = submitted(classmate);
            assertThat(elements(ended)).isEqualTo(2);
            assertThat(notes(ended)).isOne();

            ending.accept(ended);

            assertThat(elements(ended)).isZero();
            assertThat(notes(ended)).isZero();
            assertThat(elements(kept)).isEqualTo(2);
            assertThat(notes(kept)).isOne();
        }
    }

    @Test
    void shouldDeleteTheElementsAndTheNoteOfAnErasedAccountAndOfALeavingMember() {
        Submitted erased = submitted(student);
        Submitted left = submitted(classmate);

        personData.eraseForAccount(student);

        assertThat(elements(erased)).isZero();
        assertThat(notes(erased)).isZero();
        assertThat(elements(left)).isEqualTo(2);
        assertThat(notes(left)).isOne();

        memberships.leave(workspace(), classmate);

        assertThat(elements(left)).isZero();
        assertThat(notes(left)).isZero();
    }

    @Test
    void shouldDeleteTheElementsAndNotesOfAPurgedWorkspace() {
        submitted(student);

        lifecycle.purgeWorkspace(course.getWorkspaceSlug());

        assertThat(count("SELECT count(*) FROM team_element WHERE workspace_id = ?", workspace()))
                .isZero();
        assertThat(count("SELECT count(*) FROM team_context WHERE workspace_id = ?", workspace()))
                .isZero();
    }

    @Test
    void shouldAcceptElementsAndANoteOnlyInTheTransactionThatCreatesTheirVersion() {
        Submitted earlier = Objects.requireNonNull(transactions.execute(status -> version(student)));

        assertThatThrownBy(() -> element(earlier.snapshot(), "late", 1, Map.of()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> note(earlier.version(), "'Late'")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    Submitted next = version(student);
                    end(next.version(), "WITHDRAWN");
                    element(next.snapshot(), "late", 1, Map.of());
                }))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    Submitted next = version(student);
                    end(next.version(), "WITHDRAWN");
                    note(next.version(), "'Late'");
                }))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldNeverChangeAnElementOrANote() {
        Submitted submitted = submitted(student);

        for (String change : List.of("label = 'other'", "text = 'other'", "parent_element_id = NULL")) {
            assertThatThrownBy(() -> jdbc.update(
                            "UPDATE team_element SET " + change + " WHERE snapshot_id = ? AND workspace_id = ?",
                            submitted.snapshot(),
                            workspace()))
                    .as(change)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        for (String change : List.of("text = 'other'", "shared_with_review = true")) {
            assertThatThrownBy(() -> jdbc.update(
                            "UPDATE team_context SET " + change + " WHERE version_id = ? AND workspace_id = ?",
                            submitted.version(),
                            workspace()))
                    .as(change)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void shouldRefuseToDeleteAnElementWhileItsVersionIsAvailable() {
        Submitted submitted = submitted(student);

        assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM team_element WHERE snapshot_id = ? AND element_id = 'cell' AND workspace_id = ?",
                        submitted.snapshot(),
                        workspace()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(elements(submitted)).isEqualTo(2);
    }

    @Test
    void shouldRefuseElementsThatCannotBeCitedAndNotesThatSayNothing() {
        for (Map<String, String> override : List.of(
                Map.of("element_id", "''"),
                Map.of("element_id", "'frame'"),
                Map.of("kind", "' '"),
                Map.of("kind", "chr(9)"),
                Map.of("parent_element_id", "'absent'"),
                Map.of("parent_element_id", "'cell'"),
                Map.of("first_line", "1", "last_line", "1"),
                Map.of("first_line", "0", "last_line", "0"),
                Map.of("last_line", "3"),
                Map.of("text", "'two' || chr(10) || 'lines'"),
                Map.of("text", "'line' || chr(13)"))) {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                        Submitted next = version(student);
                        element(next.snapshot(), "frame", 1, Map.of());
                        element(next.snapshot(), "cell", 2, override);
                    }))
                    .as(override.toString())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        for (String text : List.of("'  '", "chr(9) || chr(10)", "repeat('a', 10001)")) {
            assertThatThrownBy(() -> transactions.executeWithoutResult(
                            status -> note(version(student).version(), text)))
                    .as(text)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    Submitted next = version(student);
                    note(next.version(), "'First'");
                    note(next.version(), "'Second'");
                }))
                .isInstanceOf(DataIntegrityViolationException.class);

        // An element may hold more than one line, and it may be written before its parent.
        transactions.executeWithoutResult(status -> {
            Submitted next = version(student);
            element(
                    next.snapshot(),
                    "cell",
                    2,
                    Map.of("parent_element_id", "'frame'", "text", "'two' || chr(10) || 'lines'", "last_line", "3"));
            element(next.snapshot(), "frame", 1, Map.of());
        });
    }

    private long member() {
        long account =
                Objects.requireNonNull(accounts.save(AccountFixtures.withStatus("Student", Account.Status.ACTIVE))
                        .getId());
        memberships.createMembership(course, account, WorkspaceRole.MEMBER);
        return account;
    }

    private long workspace() {
        return Objects.requireNonNull(course.getId());
    }

    private record Submitted(long item, UUID version, UUID snapshot) {}

    /** A new version with a snapshot, two elements and a note, written as a submission writes them. */
    private Submitted submitted(long account) {
        return Objects.requireNonNull(transactions.execute(status -> {
            Submitted next = version(account);
            element(next.snapshot(), "frame", 1, Map.of());
            element(next.snapshot(), "cell", 2, Map.of("parent_element_id", "'frame'"));
            note(next.version(), "'Why we chose this'");
            return next;
        }));
    }

    /** A new item with one version and one snapshot. It must run in a transaction. */
    private Submitted version(long account) {
        long item = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO team_item(workspace_id, team_id, kind, created_at)"
                        + " VALUES (?, ?, 'team.board', now()) RETURNING id",
                Long.class,
                workspace(),
                team));
        UUID version = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO team_item_version(id, workspace_id, item_id, sequence_number, submitted_by, submitted_at,"
                        + " request_key, state, expires_at) VALUES (?, ?, ?, 1, ?, now(), gen_random_uuid(),"
                        + " 'AVAILABLE', now() + interval '30 days')",
                version,
                workspace(),
                item,
                account);
        UUID snapshot = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO team_snapshot(id, workspace_id, version_id, snapshot_key, role, entry_route, content,"
                        + " media_type, content_sha256, source_how, limitations) VALUES (?, ?, ?, 'board', 'MAIN',"
                        + " 'PLUGIN_CAPTURE', 'board'::bytea, 'application/json',"
                        + " encode(sha256('board'::bytea), 'hex'), 'MEMBER_SUPPLIED', '[]')",
                snapshot,
                workspace(),
                version);
        return new Submitted(item, version, snapshot);
    }

    /** Inserts a one-line element; {@code overrides} replaces the SQL of single columns. */
    private void element(UUID snapshot, String id, int line, Map<String, String> overrides) {
        var columns = new LinkedHashMap<String, String>();
        columns.put("element_id", "'" + id + "'");
        columns.put("kind", "'cell'");
        columns.put("label", "'A cell'");
        columns.put("text", "'A line'");
        columns.put("first_line", Integer.toString(line));
        columns.put("last_line", Integer.toString(line));
        columns.putAll(overrides);
        jdbc.update(
                "INSERT INTO team_element(id, workspace_id, snapshot_id, " + String.join(", ", columns.keySet())
                        + ") VALUES (gen_random_uuid(), ?, ?, " + String.join(", ", columns.values()) + ")",
                workspace(),
                snapshot);
    }

    private void note(UUID version, String text) {
        jdbc.update(
                "INSERT INTO team_context(id, workspace_id, version_id, text, shared_with_review)"
                        + " VALUES (gen_random_uuid(), ?, ?, " + text + ", false)",
                workspace(),
                version);
    }

    private void end(UUID version, String state) {
        jdbc.update(
                "UPDATE team_item_version SET state = ? WHERE id = ? AND workspace_id = ?",
                state,
                version,
                workspace());
    }

    private long elements(Submitted submitted) {
        return count(
                "SELECT count(*) FROM team_element WHERE snapshot_id = ? AND workspace_id = " + workspace(),
                submitted.snapshot());
    }

    private long notes(Submitted submitted) {
        return count(
                "SELECT count(*) FROM team_context WHERE version_id = ? AND workspace_id = " + workspace(),
                submitted.version());
    }

    private long count(String sql, Object argument) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, argument));
    }
}
