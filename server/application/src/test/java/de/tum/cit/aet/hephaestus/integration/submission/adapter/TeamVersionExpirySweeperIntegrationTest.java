package de.tum.cit.aet.hephaestus.integration.submission.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.AccountFixtures;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.MembershipService;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The expiry sweep against the database: which versions it ends, and what goes with them. The rows are written with
 * SQL, because nothing writes them yet.
 */
class TeamVersionExpirySweeperIntegrationTest extends BaseIntegrationTest {

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
    private TeamVersionExpirySweeper sweeper;

    private Workspace course;
    private long student;
    private UUID team;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        course = workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                "course-" + UUID.randomUUID().toString().substring(0, 8)));
        student = Objects.requireNonNull(accounts.save(AccountFixtures.withStatus("Student", Account.Status.ACTIVE))
                .getId());
        memberships.createMembership(course, student, WorkspaceRole.MEMBER);
        team = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO project_team(id, workspace_id, name, created_at) VALUES (?, ?, 'Cups', now())",
                team,
                workspace());
        jdbc.update(
                "INSERT INTO project_team_member(id, workspace_id, team_id, account_id, joined_at)"
                        + " VALUES (gen_random_uuid(), ?, ?, ?, now())",
                workspace(),
                team,
                student);
    }

    @Test
    void shouldEndTheDueVersionsWithTheirContentAndLeaveEveryOther() {
        UUID due = submitted("-1 minute");
        UUID current = submitted("1 day");
        UUID withdrawn = submitted("-1 minute");
        jdbc.update(
                "UPDATE team_item_version SET state = 'WITHDRAWN', title = NULL WHERE id = ? AND workspace_id = ?",
                withdrawn,
                workspace());

        sweeper.sweepNow();

        assertThat(state(due)).isEqualTo("EXPIRED");
        assertThat(title(due)).isNull();
        assertThat(snapshots(due)).isZero();
        assertThat(state(current)).isEqualTo("AVAILABLE");
        assertThat(title(current)).isEqualTo("Board");
        assertThat(snapshots(current)).isOne();
        assertThat(state(withdrawn)).isEqualTo("WITHDRAWN");
    }

    @Test
    void shouldEndMoreVersionsThanOneBatchHoldsInOneRun() {
        for (int i = 0; i <= TeamVersionExpirySweeper.BATCH; i++) submitted("-1 minute");

        sweeper.sweepNow();

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM team_item_version WHERE state = 'EXPIRED' AND workspace_id = ?",
                        Long.class,
                        workspace()))
                .isEqualTo(TeamVersionExpirySweeper.BATCH + 1L);
    }

    @Test
    void shouldSkipAVersionThatAnotherTransactionHoldsAndEndItInALaterRun() {
        UUID held = submitted("-1 minute");
        UUID free = submitted("-1 minute");

        transactions.executeWithoutResult(status -> {
            jdbc.queryForList(
                    "SELECT id FROM team_item_version WHERE id = ? AND workspace_id = ? FOR NO KEY UPDATE",
                    held,
                    workspace());
            // The sweep runs on another connection and must not wait for this one.
            CompletableFuture.runAsync(sweeper::sweepNow)
                    .orTimeout(20, TimeUnit.SECONDS)
                    .join();
        });

        assertThat(state(free)).isEqualTo("EXPIRED");
        assertThat(state(held)).isEqualTo("AVAILABLE");

        sweeper.sweepNow();

        assertThat(state(held)).isEqualTo("EXPIRED");
    }

    private long workspace() {
        return Objects.requireNonNull(course.getId());
    }

    /** A new item with one version and one snapshot, expiring at the given distance from now. */
    private UUID submitted(String expiry) {
        return Objects.requireNonNull(transactions.execute(status -> {
            long item = Objects.requireNonNull(jdbc.queryForObject(
                    "INSERT INTO team_item(workspace_id, team_id, kind, created_at)"
                            + " VALUES (?, ?, 'team.board', now()) RETURNING id",
                    Long.class,
                    workspace(),
                    team));
            UUID version = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO team_item_version(id, workspace_id, item_id, sequence_number, submitted_by,"
                            + " submitted_at, request_key, state, expires_at, title) VALUES (?, ?, ?, 1, ?, now(),"
                            + " gen_random_uuid(), 'AVAILABLE', now() + CAST(? AS interval), 'Board')",
                    version,
                    workspace(),
                    item,
                    student,
                    expiry);
            jdbc.update(
                    "INSERT INTO team_snapshot(id, workspace_id, version_id, snapshot_key, role, entry_route,"
                            + " content, media_type, content_sha256, source_how, limitations) VALUES"
                            + " (gen_random_uuid(), ?, ?, 'board', 'MAIN', 'PLUGIN_CAPTURE', 'board'::bytea,"
                            + " 'application/json', encode(sha256('board'::bytea), 'hex'), 'MEMBER_SUPPLIED', '[]')",
                    workspace(),
                    version);
            return version;
        }));
    }

    private @Nullable String state(UUID version) {
        return column("state", version);
    }

    private @Nullable String title(UUID version) {
        return column("title", version);
    }

    private @Nullable String column(String column, UUID version) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM team_item_version WHERE id = ? AND workspace_id = " + workspace(),
                String.class,
                version);
    }

    private long snapshots(UUID version) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM team_snapshot WHERE version_id = ? AND workspace_id = " + workspace(),
                Long.class,
                version));
    }
}
