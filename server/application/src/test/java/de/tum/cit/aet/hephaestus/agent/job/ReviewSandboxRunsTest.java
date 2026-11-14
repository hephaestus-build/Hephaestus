package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.ReviewAttemptCustody;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRun;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxRunKind;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunKind;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.run.StaleRunException;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxKind;
import de.tum.cit.aet.hephaestus.agent.sandbox.claim.AgentSandboxes;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class ReviewSandboxRunsTest extends BaseUnitTest {
    @TempDir
    Path root;

    @Test
    void shouldRemoveTheGatewayArchiveBeforeReleasingTheAttemptsInputFolder() throws Exception {
        var mapper = JsonMapper.builder().build();
        var runs = mock(AgentRuns.class);
        var custody = new ReviewAttemptCustody(runs);
        var jobs = mock(ReviewJobRepository.class);
        var preparation = mock(ReviewAttemptPreparation.class);
        var workspaces = mock(SandboxWorkspaceManager.class);
        var sessions = new SandboxGatewaySessions();
        var transactions = mock(TransactionTemplate.class);
        UUID jobId = UUID.randomUUID();
        var fence = new AgentRunFence(UUID.randomUUID(), 7, 1);
        var job = new ReviewJob();
        job.setId(jobId);
        job.setWorkspaceId(7);
        job.setRunId(fence.runId());
        job.setReviewId(UUID.randomUUID());
        job.setStatus(ReviewJobStatus.RUNNING);
        var snapshot = new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                new FrozenModel(
                        LlmApiProtocol.OPENAI_COMPLETIONS,
                        "https://model.invalid/v1",
                        "model",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false),
                null,
                null,
                600);
        job.setConfigSnapshot(snapshot.toJson(mapper));
        when(jobs.findByRunIdAndWorkspaceId(fence.runId(), 7)).thenReturn(Optional.of(job));
        Path archive = Files.writeString(root.resolve("input.tar"), "personal evidence archive");
        Path copiedPerson = Files.writeString(root.resolve("person.json"), "personal evidence");
        var inputs = new PreparedJobInputs(
                Map.of("context/person.json", copiedPerson),
                List.of(),
                List.of(() -> {
                    assertThat(archive)
                            .as("archive removal precedes release of the original folder")
                            .doesNotExist();
                    Files.delete(copiedPerson);
                }),
                null,
                null);
        var spec = mock(SandboxSpec.class);
        when(spec.image()).thenReturn("agent:test");
        when(preparation.prepare(eq(jobId), eq(job), eq(fence), any(), any()))
                .thenReturn(new ReviewAttemptPreparation.PreparedSandbox(spec, inputs));
        when(transactions.execute(any())).thenReturn(1);
        when(workspaces.createInputTar(any(), anyMap(), anyMap(), anyList())).thenReturn(archive);
        var runtime = new ReviewSandboxRuns(
                mock(ReviewJobClaimService.class),
                preparation,
                custody,
                mock(ReviewJobTerminal.class),
                jobs,
                workspaces,
                sessions,
                transactions,
                mapper,
                mock(DefaultReviewExecutionHandler.class),
                mock(ReviewAttemptResults.class),
                mock(ReviewRunKind.class),
                runs,
                mock(JobEvidenceFiles.class));
        var sandbox = new AgentSandboxes.LiveSandbox(
                UUID.randomUUID(), 7, "review:" + UUID.randomUUID(), AgentSandboxKind.REVIEW);
        var run = new SandboxRun(sandbox, fence, AgentRunKind.REVIEW, 0);

        runtime.context(run);
        var session = runtime.session(run);
        assertThat(archive).exists();
        assertThat(copiedPerson).exists();
        custody.releaseJob(jobId);

        assertThat(archive).doesNotExist();
        assertThat(copiedPerson).doesNotExist();
        assertThatThrownBy(session::download).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldEndAResultWhoseAttemptThisReplicaDidNotPrepareWithoutPreparingIt() {
        var jobs = mock(ReviewJobRepository.class);
        var preparation = mock(ReviewAttemptPreparation.class);
        var fence = new AgentRunFence(UUID.randomUUID(), 7, 1);
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        job.setWorkspaceId(7);
        job.setRunId(fence.runId());
        job.setReviewId(UUID.randomUUID());
        when(jobs.findByRunIdAndWorkspaceId(fence.runId(), 7)).thenReturn(Optional.of(job));
        var runtime = new ReviewSandboxRuns(
                mock(ReviewJobClaimService.class),
                preparation,
                mock(ReviewAttemptCustody.class),
                mock(ReviewJobTerminal.class),
                jobs,
                mock(SandboxWorkspaceManager.class),
                new SandboxGatewaySessions(),
                mock(TransactionTemplate.class),
                JsonMapper.builder().build(),
                mock(DefaultReviewExecutionHandler.class),
                mock(ReviewAttemptResults.class),
                mock(ReviewRunKind.class),
                mock(AgentRuns.class),
                mock(JobEvidenceFiles.class));
        var sandbox = new AgentSandboxes.LiveSandbox(
                UUID.randomUUID(), 7, ReviewRunKind.subject(job.getReviewId()), AgentSandboxKind.REVIEW);
        var run = new SandboxRun(sandbox, fence, AgentRunKind.REVIEW, 0);

        assertThatThrownBy(() -> runtime.preparedSession(run)).isInstanceOf(StaleRunException.class);
        verifyNoInteractions(preparation);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldCorrelateRunCompletionAndRestoreItsCallersContext(boolean fail) {
        var jobs = mock(ReviewJobRepository.class);
        var reviewRuns = mock(ReviewRunKind.class);
        var fence = new AgentRunFence(UUID.randomUUID(), 7, 1);
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        job.setWorkspaceId(7);
        job.setRunId(fence.runId());
        job.setReviewId(UUID.randomUUID());
        when(jobs.findByRunIdAndWorkspaceId(fence.runId(), 7)).thenReturn(Optional.of(job));
        when(reviewRuns.requeue(any(), any(), any(), any(), any())).thenAnswer(call -> {
            assertThat(MDC.getCopyOfContextMap())
                    .containsEntry("workspace.id", "7")
                    .containsEntry("job.id", job.getId().toString())
                    .containsEntry("job.attempt", "0")
                    .containsEntry("review.id", job.getReviewId().toString())
                    .containsEntry("run.id", fence.runId().toString())
                    .containsEntry("run.epoch", "1")
                    .containsEntry("subject.key", ReviewRunKind.subject(job.getReviewId()));
            if (fail) throw new IllegalStateException("Run completion failed");
            return true;
        });
        var runtime = new ReviewSandboxRuns(
                mock(ReviewJobClaimService.class),
                mock(ReviewAttemptPreparation.class),
                mock(ReviewAttemptCustody.class),
                mock(ReviewJobTerminal.class),
                jobs,
                mock(SandboxWorkspaceManager.class),
                new SandboxGatewaySessions(),
                mock(TransactionTemplate.class),
                JsonMapper.builder().build(),
                mock(DefaultReviewExecutionHandler.class),
                mock(ReviewAttemptResults.class),
                reviewRuns,
                mock(AgentRuns.class),
                mock(JobEvidenceFiles.class));
        var sandbox = new AgentSandboxes.LiveSandbox(
                UUID.randomUUID(), 7, ReviewRunKind.subject(job.getReviewId()), AgentSandboxKind.REVIEW);
        var run = new SandboxRun(sandbox, fence, AgentRunKind.REVIEW, 0);
        var previous = MDC.getCopyOfContextMap();
        var parent = Map.of("run.id", "parent-run", "review.id", "parent-review", "job.attempt", "parent-attempt");
        MDC.setContextMap(parent);
        try {
            if (fail) {
                assertThatThrownBy(() -> runtime.end(run, SandboxRunKind.Outcome.HANDED_ON))
                        .isInstanceOf(IllegalStateException.class);
            } else {
                assertThat(runtime.end(run, SandboxRunKind.Outcome.HANDED_ON)).isTrue();
            }
            assertThat(MDC.getCopyOfContextMap()).isEqualTo(parent);
        } finally {
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }
}
