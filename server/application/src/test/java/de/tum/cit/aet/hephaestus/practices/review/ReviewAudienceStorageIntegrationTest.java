package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewAudienceDTO;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAudience;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewVersionKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * What the database keeps of a review's audience and kind of version (ADR 0167 decisions 2 and 3), and what the read
 * model makes of it. The rows are written with SQL, because nothing writes these columns yet.
 */
class ReviewAudienceStorageIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ReviewRepository reviews;

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
        repository = item(course, "scm.repository");
    }

    @Test
    void shouldKeepATeamAudienceOnlyForAStandingTeamProjectOfTheReviewsOwnWorkspace() {
        UUID teamReview = review(course, repository, "SCAN", "REPOSITORY_COMMIT", "TEAM", team);
        UUID removedTeam = team(course);
        jdbc.update("UPDATE project_team SET removed_at = now() WHERE id = ?", removedTeam);

        assertThat(audienceOf(teamReview)).isEqualTo(new ReviewAudienceDTO(ReviewAudience.TEAM, team));
        assertThat(load(teamReview).getVersionKind()).isEqualTo(ReviewVersionKind.REPOSITORY_COMMIT);
        for (var refused : List.of(team(otherCourse), removedTeam, UUID.randomUUID())) {
            assertThatThrownBy(() -> review(course, repository, "SCAN", "REPOSITORY_COMMIT", "TEAM", refused))
                    .as("team %s", refused)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("a team review names a standing team project of its workspace");
        }
    }

    @Test
    void shouldRefuseAnAudienceThatDoesNotPairWithItsTeamOrItsKindOfVersion() {
        long pullRequest = item(course, "scm.pull_request");
        record Row(
                String why,
                String constraint,
                long item,
                String kind,
                @Nullable String versionKind,
                @Nullable String audience,
                @Nullable UUID teamId) {}
        for (var row : List.of(
                new Row(
                        "a team audience without its team",
                        "ck_review_audience_team",
                        repository,
                        "SCAN",
                        "REPOSITORY_COMMIT",
                        "TEAM",
                        null),
                new Row(
                        "a team for another audience",
                        "ck_review_audience_team",
                        repository,
                        "SCAN",
                        "REPOSITORY_COMMIT",
                        "OPERATIONAL",
                        team),
                new Row("a team without an audience", "ck_review_audience_team", repository, "SCAN", null, null, team),
                new Row(
                        "an audience without a kind of version",
                        "ck_review_audience_version_kind",
                        repository,
                        "SCAN",
                        null,
                        "OPERATIONAL",
                        null),
                new Row(
                        "a kind of version without an audience",
                        "ck_review_audience_version_kind",
                        repository,
                        "SCAN",
                        "REPOSITORY_COMMIT",
                        null,
                        null),
                new Row(
                        "a scan of something else",
                        "ck_review_version_kind_scan",
                        repository,
                        "SCAN",
                        "SUBMITTED_VERSION",
                        "TEAM",
                        team),
                new Row(
                        "a repository commit outside a scan",
                        "ck_review_version_kind_scan",
                        pullRequest,
                        "ITEM",
                        "REPOSITORY_COMMIT",
                        "PERSONAL",
                        null),
                new Row(
                        "an unknown audience",
                        "ck_review_audience",
                        pullRequest,
                        "ITEM",
                        "ITEM_REVISION",
                        "STAFF",
                        null),
                new Row(
                        "an unknown kind of version",
                        "ck_review_version_kind",
                        pullRequest,
                        "ITEM",
                        "DRAFT",
                        "PERSONAL",
                        null))) {
            assertThatThrownBy(() ->
                            review(course, row.item(), row.kind(), row.versionKind(), row.audience(), row.teamId()))
                    .as(row.why())
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining(row.constraint());
        }
    }

    @Test
    void shouldKeepTheAudienceAndTheKindOfVersionThatAReviewWasCreatedWith() {
        UUID teamReview = review(course, repository, "SCAN", "REPOSITORY_COMMIT", "TEAM", team);
        UUID secondTeam = team(course);

        for (String change : List.of(
                "audience = 'OPERATIONAL', audience_team_id = NULL",
                "audience_team_id = '" + secondTeam + "'",
                "audience = NULL, version_kind = NULL, audience_team_id = NULL")) {
            assertThatThrownBy(() -> jdbc.update("UPDATE review SET " + change + " WHERE id = ?", teamReview))
                    .as(change)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("a review keeps the audience");
        }
        assertThat(audienceOf(teamReview)).isEqualTo(new ReviewAudienceDTO(ReviewAudience.TEAM, team));
    }

    @Test
    void shouldPointAtASetupOfItsOwnWorkspaceAndLeaveItWhenTheSetupGoes() {
        UUID setup = setup(course);
        UUID started = review(course, repository, "SCAN", "REPOSITORY_COMMIT", "OPERATIONAL", null, setup);

        // An item review: a workspace has one open scan at a time.
        long pullRequest = item(course, "scm.pull_request");
        assertThatThrownBy(() ->
                        review(course, pullRequest, "ITEM", "ITEM_REVISION", "PERSONAL", null, setup(otherCourse)))
                .as("a setup of another workspace")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("sfk_review_setup");
        assertThatThrownBy(() -> jdbc.update("UPDATE review SET setup_id = ? WHERE id = ?", setup(course), started))
                .as("another setup")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("a review keeps the audience");

        jdbc.update("DELETE FROM review_setup WHERE id = ?", setup);
        assertThat(load(started).getSetupId()).isNull();
        assertThat(audienceOf(started)).isEqualTo(new ReviewAudienceDTO(ReviewAudience.OPERATIONAL, null));
    }

    @Test
    void shouldAllowCancellationOfAnExistingReviewAfterItsTeamIsRemoved() {
        UUID teamReview = review(course, repository, "SCAN", "REPOSITORY_COMMIT", "TEAM", team);
        jdbc.update("UPDATE project_team SET removed_at = now() WHERE id = ?", team);

        var existing = load(teamReview);
        assertThat(existing.requestCancel()).isTrue();
        reviews.saveAndFlush(existing);

        assertThat(audienceOf(teamReview)).isEqualTo(new ReviewAudienceDTO(ReviewAudience.TEAM, team));
    }

    @Test
    void shouldKeepAClearedSetupClearedWhenAReviewLoadedBeforeTheDeleteIsSaved() {
        UUID setup = setup(course);
        UUID started = review(course, repository, "SCAN", "REPOSITORY_COMMIT", "OPERATIONAL", null, setup);
        var loaded = load(started);

        jdbc.update("DELETE FROM review_setup WHERE id = ?", setup);
        assertThat(loaded.requestCancel()).isTrue();
        reviews.saveAndFlush(loaded);

        assertThat(load(started).getSetupId()).isNull();
    }

    @Test
    void shouldRefuseRebindingAReviewAfterItsSetupIsDeleted() {
        UUID original = setup(course);
        UUID replacement = setup(course);
        UUID started = review(course, repository, "SCAN", "REPOSITORY_COMMIT", "OPERATIONAL", null, original);

        jdbc.update("DELETE FROM review_setup WHERE id = ?", original);
        assertThat(load(started).getSetupId()).isNull();

        assertThatThrownBy(() -> jdbc.update("UPDATE review SET setup_id = ? WHERE id = ?", replacement, started))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("a review keeps the audience");
    }

    @Test
    void shouldReadAReviewFromBeforeAsOperationalOrPersonalAndGiveItItsValuesOnce() {
        UUID olderScan = review(course, repository, "SCAN", null, null, null);
        UUID olderItemReview = review(course, item(course, "scm.pull_request"), "ITEM", null, null, null);

        assertThat(audienceOf(olderScan)).isEqualTo(new ReviewAudienceDTO(ReviewAudience.OPERATIONAL, null));
        assertThat(audienceOf(olderItemReview)).isEqualTo(new ReviewAudienceDTO(ReviewAudience.PERSONAL, null));
        assertThat(ReviewSummaryQueryService.versionKind(load(olderItemReview)))
                .isEqualTo(ReviewVersionKind.ITEM_REVISION);

        // A later backfill writes the derived values once; after that they are fixed like any other.
        jdbc.update(
                "UPDATE review SET audience = 'OPERATIONAL', version_kind = 'REPOSITORY_COMMIT' WHERE id = ?",
                olderScan);
        assertThat(load(olderScan).getAudience()).isEqualTo(ReviewAudience.OPERATIONAL);
        assertThatThrownBy(() -> jdbc.update("UPDATE review SET audience = 'PERSONAL' WHERE id = ?", olderScan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ReviewAudienceDTO audienceOf(UUID review) {
        return ReviewSummaryQueryService.audience(load(review));
    }

    private Review load(UUID review) {
        return reviews.findById(review).orElseThrow();
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

    private long item(Workspace workspace, String kind) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO item(workspace_id, kind, source_id, created_at) VALUES (?, ?, ?, now()) RETURNING id",
                Long.class,
                id(workspace),
                kind,
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE)));
    }

    private UUID review(
            Workspace workspace,
            long item,
            String kind,
            @Nullable String versionKind,
            @Nullable String audience,
            @Nullable UUID teamId) {
        return review(workspace, item, kind, versionKind, audience, teamId, null);
    }

    private UUID review(
            Workspace workspace,
            long item,
            String kind,
            @Nullable String versionKind,
            @Nullable String audience,
            @Nullable UUID teamId,
            @Nullable UUID setupId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO review(id, workspace_id, item_id, kind, item_version, origin, ordered_at, version_kind,"
                        + " audience, audience_team_id, setup_id, budget_usd)"
                        + " VALUES (?, ?, ?, ?, 'v1', 'REQUEST', now(), ?, ?, ?, ?, ?)",
                id,
                id(workspace),
                item,
                kind,
                versionKind,
                audience,
                teamId,
                setupId,
                // The database requires a budget of every scan.
                "SCAN".equals(kind) ? BigDecimal.TEN : null);
        return id;
    }

    private UUID setup(Workspace workspace) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO review_setup(id, workspace_id, name, item_kind, audience, evidence_slots, enabled,"
                        + " created_at) VALUES (?, ?, 'Weekly scan', 'scm.repository', 'OPERATIONAL', '[]'::jsonb, true, now())",
                id,
                id(workspace));
        return id;
    }

    private static long id(Workspace workspace) {
        return Objects.requireNonNull(workspace.getId());
    }
}
