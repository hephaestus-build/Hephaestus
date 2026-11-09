package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class ReviewJobEvidenceRefusalIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ReviewJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void evidenceRefusalFailsOnlyTheOwningAttempt() {
        databaseTestUtils.cleanDatabase();
        var job = new ReviewJob();
        RuntimeJobTestData.initialize(
                job,
                workspaces
                        .save(TestEntities.activeWorkspace("evidence-refusal"))
                        .getId());

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setConfigSnapshot(mapper.createObjectNode());
        var id = jobs.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job)).getId();
        var output = mapper.createObjectNode().put("outcome", "INSUFFICIENT_EVIDENCE");
        Integer refused = transactions.execute(
                status -> jobs.transitionToEvidenceRefused(id, UUID.randomUUID(), 0, Instant.now(), output));
        assertThat(refused).isZero();
        Integer stale = transactions.execute(
                status -> jobs.transitionToEvidenceRefused(id, job.getRunId(), 1, Instant.now(), output));
        assertThat(stale).isZero();
        // The run ends with its job, as the executor's refusal ends it.
        Integer settled = transactions.execute(status -> {
            int ended = jobs.transitionToEvidenceRefused(id, job.getRunId(), 0, Instant.now(), output);
            RuntimeJobTestData.agree(jdbc, id);
            return ended;
        });
        assertThat(settled).isEqualTo(1);
        var saved = jobs.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(ReviewJobStatus.FAILED);
        assertThat(saved.getProcessingStatus()).isEqualTo(ProcessingStatus.NONE);
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(saved.getExecutionStartedAt()).isNull();
        assertThat(saved.getOutput()).isEqualTo(output);
        assertThat(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM result WHERE workspace_id=? AND review_id=?)",
                        Boolean.class,
                        job.getWorkspaceId(),
                        job.getReviewId()))
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM feedback WHERE workspace_id=? AND review_id=?)",
                        Boolean.class,
                        job.getWorkspaceId(),
                        job.getReviewId()))
                .isFalse();
    }
}
