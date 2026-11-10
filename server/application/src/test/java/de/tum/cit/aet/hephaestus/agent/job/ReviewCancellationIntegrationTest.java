package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Review cancellation over PostgreSQL")
class ReviewCancellationIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewService reviews;

    @Test
    void shouldCommitTheJobCancellationWhenTheReviewCancellationCommits() {
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace(
                "cancel-" + UUID.randomUUID().toString().substring(0, 8)));
        var job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        job.setConfigSnapshot(new JsonMapper().createObjectNode());
        job.prePersist();
        jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));

        assertThat(reviews.cancel(workspace.getId(), job.getReviewId())).isTrue();

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM review_job WHERE id=? AND workspace_id=?",
                        String.class,
                        job.getId(),
                        workspace.getId()))
                .isEqualTo("CANCELLED");
    }
}
