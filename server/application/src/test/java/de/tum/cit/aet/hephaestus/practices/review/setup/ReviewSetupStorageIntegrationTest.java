package de.tum.cit.aet.hephaestus.practices.review.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** What the database accepts as a review setup. The rows are written with SQL, because nothing writes them yet. */
class ReviewSetupStorageIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    private Workspace course;
    private Workspace otherCourse;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        course = workspace("course");
        otherCourse = workspace("other-course");
    }

    @Test
    void shouldLetOnlyATeamSetupNameStandingTeamsOfItsOwnWorkspace() {
        UUID team = team(course);
        UUID elsewhere = team(otherCourse);
        UUID removed = team(course);
        jdbc.update(
                "UPDATE project_team SET removed_at = now() WHERE id = ? AND workspace_id = ?", removed, id(course));
        UUID forTeams = setup(course, "TEAM");
        UUID forAdmins = setup(course, "OPERATIONAL");

        name(course, forTeams, team);

        for (UUID refused : List.of(elsewhere, removed, UUID.randomUUID())) {
            assertThatThrownBy(() -> name(course, forTeams, refused))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> name(course, forAdmins, team)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> name(otherCourse, forTeams, elsewhere))
                .as("a setup of another workspace")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE review_setup_team SET team_id = ? WHERE setup_id = ? AND workspace_id = ?",
                        elsewhere,
                        forTeams,
                        id(course)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldKeepNamedTeamsInTheSetupsWorkspaceWhenTheTeamMoves() {
        UUID team = team(course);
        UUID setup = setup(course, "TEAM");
        name(course, setup, team);

        try {
            jdbc.update(
                    "UPDATE project_team SET workspace_id = ? WHERE id = ? AND workspace_id = ?",
                    id(otherCourse),
                    team,
                    id(course));
        } catch (DataIntegrityViolationException refusedMove) {
            // A database guard may refuse the move instead of removing the choice.
        }

        assertThat(count(
                        """
                        SELECT count(*) FROM review_setup_team choice
                        JOIN project_team team ON team.id = choice.team_id
                        WHERE choice.setup_id = ? AND choice.workspace_id <> team.workspace_id
                        """,
                        setup))
                .isZero();
    }

    @Test
    void shouldKeepTheKindAndAudienceOfASetupAndItsRemoval() {
        UUID setup = setup(course, "TEAM");

        for (String change : List.of(
                "audience = 'OPERATIONAL'",
                "item_kind = 'scm.repository'",
                "workspace_id = " + id(otherCourse),
                "created_at = now() + interval '1 day'")) {
            assertThatThrownBy(() -> set(setup, change)).as(change).isInstanceOf(DataIntegrityViolationException.class);
        }
        set(setup, "name = 'Weekly map check', enabled = false, removed_at = now()");
        assertThatThrownBy(() -> set(setup, "removed_at = NULL")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldRefuseASetupOutsideItsValues() {
        UUID setup = setup(course, "TEAM");

        for (String change : List.of("cadence = 'HOURLY'", "budget_usd = 0", "scope = '[]'", "evidence_slots = '{}'")) {
            assertThatThrownBy(() -> set(setup, change)).as(change).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> setup(course, "EVERYONE")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldDeleteThePurgedWorkspacesSetupsAndNoOthers() {
        UUID setup = setup(course, "TEAM");
        name(course, setup, team(course));
        UUID kept = setup(otherCourse, "TEAM");
        name(otherCourse, kept, team(otherCourse));

        lifecycle.purgeWorkspace(course.getWorkspaceSlug());

        for (String table : List.of("review_setup", "review_setup_team")) {
            assertThat(count("SELECT count(*) FROM " + table + " WHERE workspace_id = ?", id(course)))
                    .as(table)
                    .isZero();
            assertThat(count("SELECT count(*) FROM " + table + " WHERE workspace_id = ?", id(otherCourse)))
                    .as(table)
                    .isOne();
        }
    }

    private Workspace workspace(String name) {
        return workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                name + "-" + UUID.randomUUID().toString().substring(0, 8)));
    }

    private static long id(Workspace workspace) {
        return Objects.requireNonNull(workspace.getId());
    }

    private UUID team(Workspace workspace) {
        UUID team = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO project_team(id, workspace_id, name, created_at) VALUES (?, ?, 'Cups', now())",
                team,
                id(workspace));
        return team;
    }

    private UUID setup(Workspace workspace, String audience) {
        UUID setup = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO review_setup(id, workspace_id, name, item_kind, audience, evidence_slots, enabled,"
                        + " created_at) VALUES (?, ?, 'Map check', 'team.board', ?, '[]', true, now())",
                setup,
                id(workspace),
                audience);
        return setup;
    }

    private void name(Workspace workspace, UUID setup, UUID team) {
        jdbc.update(
                "INSERT INTO review_setup_team(setup_id, team_id, workspace_id) VALUES (?, ?, ?)",
                setup,
                team,
                id(workspace));
    }

    private void set(UUID setup, String change) {
        jdbc.update("UPDATE review_setup SET " + change + " WHERE id = ? AND workspace_id = " + id(course), setup);
    }

    private long count(String sql, Object argument) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, argument));
    }
}
