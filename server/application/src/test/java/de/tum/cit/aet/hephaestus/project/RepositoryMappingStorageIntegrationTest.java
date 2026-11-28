package de.tum.cit.aet.hephaestus.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Which repository mappings the database keeps, and when they go (ADR 0167 decision 3). The rows are written with SQL,
 * because nothing writes them yet. The repository item is a plain id (ADR 0138), so its writer, not the database,
 * checks that it is a repository of the same workspace.
 */
class RepositoryMappingStorageIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    private Workspace course;
    private Workspace otherCourse;
    private UUID team;
    private long repository;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        course = workspace("course");
        otherCourse = workspace("other-course");
        team = team(course);
        repository = repository(course);
    }

    @Test
    void shouldKeepOneMappingOfARepositoryToATeamOfTheSameWorkspace() {
        UUID mapping = map(course, team, repository);

        assertThat(mappings(team)).isEqualTo(1);
        assertThat(mapping).isNotNull();
        assertThatThrownBy(() -> map(course, team, repository))
                .as("the same repository twice for one team")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_project_team_repository");
    }

    @Test
    void shouldRefuseATeamProjectOfAnotherWorkspace() {
        UUID otherTeam = team(otherCourse);

        assertThatThrownBy(() -> map(course, otherTeam, repository))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sfk_project_team_repository_team");
        assertThat(mappings(otherTeam)).isZero();
    }

    @Test
    void shouldDeleteAMappingWithItsTeam() {
        UUID mapping = map(course, team, repository);

        jdbc.update("DELETE FROM project_team WHERE id = ?", team);

        assertThat(exists(mapping)).isFalse();
    }

    @Test
    void shouldDeleteTheMappingsOfAPurgedWorkspaceOnly() {
        UUID purged = map(course, team, repository);
        UUID kept = map(otherCourse, team(otherCourse), repository(otherCourse));

        lifecycle.purgeWorkspace(course.getWorkspaceSlug());

        assertThat(exists(purged)).isFalse();
        assertThat(exists(kept)).isTrue();
    }

    private Workspace workspace(String name) {
        return workspaces.save(WorkspaceTestFixtures.activeWorkspace(
                name + "-" + UUID.randomUUID().toString().substring(0, 8)));
    }

    private UUID team(Workspace workspace) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO project_team(id, workspace_id, name, created_at) VALUES (?, ?, 'Cups', now())",
                id,
                id(workspace));
        return id;
    }

    private long repository(Workspace workspace) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO item(workspace_id, kind, source_id, created_at) VALUES (?, 'scm.repository', ?, now())"
                        + " RETURNING id",
                Long.class,
                id(workspace),
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)));
    }

    private UUID map(Workspace workspace, UUID teamId, long repositoryItemId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO project_team_repository(id, workspace_id, team_id, repository_item_id, mapped_at)"
                        + " VALUES (?, ?, ?, ?, now())",
                id,
                id(workspace),
                teamId,
                repositoryItemId);
        return id;
    }

    private int mappings(UUID teamId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM project_team_repository WHERE team_id = ?", Integer.class, teamId));
    }

    private boolean exists(UUID mapping) {
        return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT count(*) FROM project_team_repository WHERE id = ?", Integer.class, mapping))
                == 1;
    }

    private static long id(Workspace workspace) {
        return Objects.requireNonNull(workspace.getId());
    }
}
