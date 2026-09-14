package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class AgentJobEvidenceRefusalIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private AgentJobRepository jobs;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void evidenceRefusalSettlesProcessingWithoutModelExecutionOrObservations() {
        databaseTestUtils.cleanDatabase();
        var job = new AgentJob();
        job.setWorkspace(workspaces.save(TestEntities.activeWorkspace("evidence-refusal")));
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("owner");
        job.setConfigSnapshot(mapper.createObjectNode());
        var id = jobs.saveAndFlush(job).getId();
        var output = mapper.createObjectNode().put("outcome", "INSUFFICIENT_EVIDENCE");
        Integer refused =
                transactions.execute(status -> jobs.transitionToEvidenceRefused(id, "other", 0, Instant.now(), output));
        assertThat(refused).isZero();
        Integer settled =
                transactions.execute(status -> jobs.transitionToEvidenceRefused(id, "owner", 0, Instant.now(), output));
        assertThat(settled).isEqualTo(1);
        var saved = jobs.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(AgentJobStatus.COMPLETED);
        assertThat(saved.getDeliveryStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(saved.getCompletedAt()).isNotNull();
        assertThat(saved.getExecutionStartedAt()).isNull();
        assertThat(saved.getDeliveryCommentId()).isNull();
        assertThat(saved.getOutput()).isEqualTo(output);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM observation WHERE agent_job_id = ?", Long.class, id))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feedback WHERE agent_job_id = ?", Long.class, id))
                .isZero();
    }
}
