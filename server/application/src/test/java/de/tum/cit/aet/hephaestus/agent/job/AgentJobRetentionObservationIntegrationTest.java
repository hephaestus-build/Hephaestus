package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

class AgentJobRetentionObservationIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Transactional
    void keepsAJobThatIsTheOnlyProvenanceForAStoredFinding() {
        var owner = persistUser("retention-owner");
        Workspace workspace =
                createWorkspace("retention-observation", "Retention", "retention-org", AccountType.ORG, owner);
        Practice practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(workspace);
        practice.setSlug("review-quality");
        practice.setName("Review quality");
        practice.setCriteria("Review the change");
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        practice = practiceRepository.save(practice);

        AgentJob referenced = oldTerminalJob(workspace);
        AgentJob unreferenced = oldTerminalJob(workspace);
        jobRepository.saveAllAndFlush(List.of(referenced, unreferenced));
        UUID findingId = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                findingId,
                "retention-" + findingId,
                referenced.getId(),
                referenced.getWorkspace().getId(),
                practice.getId(),
                null,
                ArtifactKinds.PULL_REQUEST.value(),
                7L,
                owner.getId(),
                "Stored finding",
                "ASSESSED",
                "ABSENT",
                "BAD",
                "MAJOR",
                "{}",
                "Reasoning",
                "retention-locus",
                Instant.now(),
                "LIVE");

        int deleted = jobRepository.deleteUnreferencedTerminalRowsOlderThan(
                Instant.now().minus(Duration.ofDays(90)), 100);

        assertThat(deleted).isEqualTo(1);
        assertThat(jobRepository.existsById(referenced.getId())).isTrue();
        assertThat(jobRepository.existsById(unreferenced.getId())).isFalse();
    }

    private AgentJob oldTerminalJob(Workspace workspace) {
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setDeliveryStatus(DeliveryStatus.DELIVERED);
        job.setCompletedAt(Instant.now().minus(Duration.ofDays(100)));
        job.setConfigSnapshot(objectMapper.valueToTree(Map.of("model", "test")));
        return job;
    }
}
