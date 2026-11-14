package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.attempt.AttemptTokenIssuer;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.handler.DefaultReviewExecutionHandler;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxTestLimits;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Answers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ReviewPreparationFailureTest extends BaseUnitTest {
    @ParameterizedTest
    @EnumSource(
            value = PreparationFailure.Stage.class,
            names = {"CREDENTIAL", "SPEC", "PROVENANCE"})
    void shouldKeepLatePreparationFailureSeparateFromExecution(PreparationFailure.Stage failedStage) {
        var job = RuntimeReviewTestData.job();
        job.setRunId(UUID.randomUUID());
        var fence = new AgentRunFence(job.getRunId(), job.getWorkspaceId(), job.getAttempt() + 1);
        var jobs = mock(ReviewJobRepository.class);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        when(jobs.databaseClock()).thenReturn(Instant.parse("2026-11-13T12:00:00Z"));
        var files = mock(JobEvidenceFiles.class);
        when(files.preparation(job)).thenReturn(JobEvidenceFiles.Preparation.NEW);
        var handler = mock(DefaultReviewExecutionHandler.class);
        when(handler.prepareInputs(job)).thenReturn(new PreparedJobInputs(Map.of(), List.of(), List.of(), null, null));
        var issuer = mock(AttemptTokenIssuer.class);
        var adapter = mock(PracticePiAdapter.class);
        var failure = new IllegalStateException("The preparation stage failed");
        if (failedStage == PreparationFailure.Stage.CREDENTIAL) {
            when(issuer.issueForReview(any(), anyLong(), anyInt(), any(), any(), any()))
                    .thenThrow(failure);
        } else {
            when(issuer.issueForReview(any(), anyLong(), anyInt(), any(), any(), any()))
                    .thenReturn("attempt-token");
            if (failedStage == PreparationFailure.Stage.SPEC) {
                when(adapter.buildSandboxSpec(any())).thenThrow(failure);
            } else {
                when(adapter.buildSandboxSpec(any()))
                        .thenReturn(new PracticeSandboxSpec(
                                "agent:test",
                                List.of("node", "program.js"),
                                Map.of(),
                                Map.of(),
                                "attempt-token",
                                null));
                when(jobs.updateCapture(any(), anyLong(), any(), anyInt(), any()))
                        .thenThrow(failure);
            }
        }
        var manager = mock(PlatformTransactionManager.class);
        if (failedStage == PreparationFailure.Stage.PROVENANCE)
            when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var mapper = new JsonMapper();
        var preparation = new ReviewAttemptPreparation(
                jobs,
                handler,
                adapter,
                issuer,
                SandboxTestLimits.PROPERTIES,
                new TransactionTemplate(manager),
                mapper,
                mock(ReviewJobTelemetry.class, Answers.RETURNS_DEEP_STUBS),
                files);
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
        var stage = new AtomicReference<>(PreparationFailure.Stage.INPUTS);
        assertThatThrownBy(() -> preparation.prepare(job.getId(), job, fence, snapshot, stage))
                .isSameAs(failure);
        assertThat(stage.get()).isEqualTo(failedStage);
        assertThat(job.getExecutionStartedAt()).isNull();
        verify(jobs, never()).markExecutionStarted(any(), any(), anyInt(), any());
    }
}
