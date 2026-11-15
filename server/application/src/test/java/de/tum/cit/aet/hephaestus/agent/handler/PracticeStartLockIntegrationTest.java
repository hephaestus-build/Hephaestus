package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.proxy.dto.PracticeStartRequestDTO;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.RunLockProbe;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.review.AbstractResultAdmissionIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewPracticeRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ProcessingStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * A practice's start checks its sources by reading the item's record, which can take a while. The run's lease renewal
 * and the reaper lock the run, review and job rows, so that check must hold none of them (docs/contributor/agent-runs.mdx
 * § Locks and slow work).
 */
class PracticeStartLockIntegrationTest extends AbstractResultAdmissionIntegrationTest {

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    ReviewPracticeRepository pinnedPractices;

    @Autowired
    ResultAdmissionService admission;

    @Autowired
    AgentRuns runs;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @BeforeEach
    void clean() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void shouldRenewTheLeaseWhileAPracticeStartChecksItsSources() throws Exception {
        var scenario = data.scenario();
        var pending = admissions.pending(
                scenario,
                Instant.now(),
                Objects.requireNonNull(scenario.account().getId()));
        long workspaceId = pending.review().getWorkspaceId();
        UUID reviewId = pending.review().getId();
        running(pending);
        var ref = productItems.require(workspaceId, scenario.item().getId());
        var kind = new SourceKind("scm.pull-request.core");
        var capture = pinnedPractices
                .findByWorkspaceIdAndReviewIdAndPracticeId(
                        workspaceId, reviewId, scenario.practice().getId())
                .orElseThrow();
        var facts = json.createObjectNode();
        facts.putObject("sourceFingerprints").put(kind.value(), "a".repeat(64));
        capture.setCaptureDecision(facts);
        pinnedPractices.saveAndFlush(capture);
        UUID runId = Objects.requireNonNull(
                jdbc.queryForObject("SELECT run_id FROM review_job WHERE id = ?", UUID.class, pending.jobId()));
        int epoch = Objects.requireNonNull(
                jdbc.queryForObject("SELECT lease_epoch FROM agent_run WHERE id = ?", Integer.class, runId));
        // A lease partway through its term, as its sandbox holds it, so that a renewal measurably extends it.
        jdbc.update("UPDATE agent_run SET leased_until = clock_timestamp() + interval '1 minute' WHERE id = ?", runId);
        var probe = RunLockProbe.of(runs, transactions, jdbc, new AgentRunFence(runId, workspaceId, epoch))
                .alsoLocks("review", "id = ?", reviewId)
                .alsoLocks("review_job", "id = ?", pending.jobId())
                .alsoLocks("review_practice", "review_id = ?", reviewId)
                .alsoLocks("review_item", "review_id = ?", reviewId);
        // The sources' check reads the item's record; the source sync before it does not.
        doAnswer(call -> {
                    probe.hold();
                    return Map.of(kind, "a".repeat(64));
                })
                .when(productItems)
                .fingerprints(eq(ref), anySet(), any());

        var started = probe.during(() -> admission.start(
                new ResultAdmissionService.AdmissionIdentity(pending.jobId(), workspaceId, 0, runId),
                new PracticeStartRequestDTO(scenario.practice().getSlug())));

        assertThat(started.current()).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM review_practice WHERE review_id = ? AND practice_id = ?",
                        String.class,
                        reviewId,
                        scenario.practice().getId()))
                .isEqualTo("RUNNING");
    }

    /** The pending review's job at attempt 0, as the runtime reports it. */
    private void running(ReviewAdmissionData.Pending pending) {
        admissions.running(pending, 0);
        long workspaceId = pending.review().getWorkspaceId();
        UUID reviewId = pending.review().getId();
        when(runtime.activeJobIds(workspaceId, reviewId)).thenReturn(Set.of(pending.jobId()));
        when(runtime.unsettledJobIds(workspaceId, reviewId)).thenReturn(Set.of());
        when(runtime.runtime(workspaceId, reviewId))
                .thenReturn(Optional.of(new ReviewRuntimeSnapshot(List.of(new ReviewJobSnapshot(
                        pending.jobId(),
                        ReviewJobStatus.RUNNING,
                        0,
                        ProcessingStatus.PENDING,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        "test-trace")))));
    }
}
