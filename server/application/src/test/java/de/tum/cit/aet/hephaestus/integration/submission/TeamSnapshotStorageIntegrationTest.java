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
 * The submitted content of a version: when the database accepts it, that it never changes, and that it goes with its
 * version. The rows are written with SQL, because nothing writes them yet.
 */
class TeamSnapshotStorageIntegrationTest extends BaseIntegrationTest {

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
    private long board;

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
        board = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO team_item(workspace_id, team_id, kind, created_at)"
                        + " VALUES (?, ?, 'team.board', now()) RETURNING id",
                Long.class,
                workspace(),
                team));
    }

    @Test
    void shouldDeleteTheContentOfAVersionThatEndsAndNoOther() {
        List<Consumer<UUID>> endings = List.of(
                version -> end(version, "WITHDRAWN"),
                version -> end(version, "EXPIRED"),
                version -> jdbc.update(
                        "UPDATE team_item SET removed_at = now() WHERE id = ? AND workspace_id = ?",
                        board,
                        workspace()));
        for (Consumer<UUID> ending : endings) {
            UUID ended = submitted(student);
            UUID kept = submitted(classmate);
            assertThat(snapshots(ended)).isOne();

            ending.accept(ended);

            assertThat(snapshots(ended)).isZero();
            if (state(kept).equals("AVAILABLE")) assertThat(snapshots(kept)).isOne();
            else assertThat(snapshots(kept)).isZero();
        }
    }

    @Test
    void shouldDeleteTheContentOfAnErasedAccountAndOfALeavingMember() {
        UUID erased = submitted(student);
        UUID left = submitted(classmate);

        personData.eraseForAccount(student);

        assertThat(snapshots(erased)).isZero();
        assertThat(snapshots(left)).isOne();

        memberships.leave(workspace(), classmate);

        assertThat(snapshots(left)).isZero();
    }

    @Test
    void shouldDeleteTheContentOfAPurgedWorkspace() {
        submitted(student);

        lifecycle.purgeWorkspace(course.getWorkspaceSlug());

        assertThat(count("SELECT count(*) FROM team_snapshot WHERE workspace_id = ?", workspace()))
                .isZero();
    }

    @Test
    void shouldAcceptContentOnlyInTheTransactionThatCreatesItsVersion() {
        UUID earlier = submitted(student);

        assertThatThrownBy(() -> snapshot(earlier, Map.of("snapshot_key", "'late'")))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    UUID next = version(student);
                    end(next, "WITHDRAWN");
                    snapshot(next, Map.of());
                }))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldNeverChangeASnapshotButForTheRemovalOfItsLiveLink() {
        UUID version = submitted(student);

        for (String change : List.of(
                "snapshot_key = 'other'",
                "role = 'SUPPORTING'",
                "content = 'x'::bytea, content_sha256 = encode(sha256('x'::bytea), 'hex')",
                "limitations = '[{\"code\":\"IMAGE\",\"count\":1}]'",
                "preview = 'p'::bytea",
                "live_link = 'https://example.org/other'")) {
            assertThatThrownBy(() -> change(version, change))
                    .as(change)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        change(version, "live_link = NULL");

        assertThat(count(
                        "SELECT count(*) FROM team_snapshot WHERE version_id = ? AND live_link IS NULL"
                                + " AND workspace_id = " + workspace(),
                        version))
                .isOne();
    }

    @Test
    void shouldRefuseContentThatDoesNotMatchItsDescription() {
        for (Map<String, String> override : List.of(
                Map.of("snapshot_key", "'../escape'"),
                Map.of("content_sha256", "repeat('0', 64)"),
                Map.of("normalized_by", "'SERVER'"),
                Map.of("normalized", "'n'::bytea", "normalized_sha256", "encode(sha256('n'::bytea), 'hex')"),
                Map.of("normalized_by", "'CLIENT'"),
                Map.of("source_revision", "'{\"commit\":\"abc\"}'"),
                Map.of("live_link", "'http://example.org'"),
                Map.of("limitations", "'{}'"))) {
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> snapshot(version(student), override)))
                    .as(override.toString())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        transactions.executeWithoutResult(status -> snapshot(
                version(student),
                Map.of("normalized_by", "'CLIENT'", "normalizer_id", "'figma-selection'", "normalizer_version", "1")));
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

    /** A new version with one snapshot, written as a submission writes it. */
    private UUID submitted(long account) {
        if (count(
                        "SELECT count(*) FROM team_item WHERE id = ? AND removed_at IS NOT NULL AND workspace_id = "
                                + workspace(),
                        board)
                > 0) {
            board = Objects.requireNonNull(jdbc.queryForObject(
                    "INSERT INTO team_item(workspace_id, team_id, kind, created_at)"
                            + " VALUES (?, ?, 'team.board', now()) RETURNING id",
                    Long.class,
                    workspace(),
                    team));
        }
        return Objects.requireNonNull(transactions.execute(status -> {
            UUID version = version(account);
            snapshot(version, Map.of());
            return version;
        }));
    }

    private UUID version(long account) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO team_item_version(id, workspace_id, item_id, sequence_number, submitted_by, submitted_at,"
                        + " request_key, state, expires_at) VALUES (?, ?, ?, (SELECT count(*) + 1 FROM"
                        + " team_item_version WHERE item_id = ? AND workspace_id = ?), ?, now(), gen_random_uuid(),"
                        + " 'AVAILABLE', now() + interval '30 days')",
                id,
                workspace(),
                board,
                board,
                workspace(),
                account);
        return id;
    }

    /** Inserts a snapshot as a submission writes it; {@code overrides} replaces the SQL of single columns. */
    private void snapshot(UUID version, Map<String, String> overrides) {
        var columns = new LinkedHashMap<String, String>();
        columns.put("snapshot_key", "'board'");
        columns.put("role", "'MAIN'");
        columns.put("entry_route", "'PLUGIN_CAPTURE'");
        columns.put("content", "'board'::bytea");
        columns.put("media_type", "'application/json'");
        columns.put("content_sha256", "encode(sha256('board'::bytea), 'hex')");
        columns.put("source_how", "'MEMBER_SUPPLIED'");
        columns.put("live_link", "'https://example.org/board'");
        columns.put("limitations", "'[]'");
        columns.putAll(overrides);
        jdbc.update(
                "INSERT INTO team_snapshot(id, workspace_id, version_id, " + String.join(", ", columns.keySet())
                        + ") VALUES (gen_random_uuid(), ?, ?, " + String.join(", ", columns.values()) + ")",
                workspace(),
                version);
    }

    private void change(UUID version, String change) {
        jdbc.update(
                "UPDATE team_snapshot SET " + change + " WHERE version_id = ? AND workspace_id = " + workspace(),
                version);
    }

    private void end(UUID version, String state) {
        jdbc.update(
                "UPDATE team_item_version SET state = ? WHERE id = ? AND workspace_id = ?",
                state,
                version,
                workspace());
    }

    private String state(UUID version) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT state FROM team_item_version WHERE id = ? AND workspace_id = " + workspace(),
                String.class,
                version));
    }

    private long snapshots(UUID version) {
        return count(
                "SELECT count(*) FROM team_snapshot WHERE version_id = ? AND workspace_id = " + workspace(), version);
    }

    private long count(String sql, Object argument) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, argument));
    }
}
