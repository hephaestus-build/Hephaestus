package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
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
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.metrics.ReviewStageTelemetry;
import de.tum.cit.aet.hephaestus.agent.practice.PracticePiAdapter;
import de.tum.cit.aet.hephaestus.agent.practice.PracticeSandboxSpec;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunAuthority;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunFence;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxTestLimits;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityBoundary;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusal;
import de.tum.cit.aet.hephaestus.core.authority.AuthorityRefusedException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Answers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class ReviewPreparationFailureTest extends BaseUnitTest {

    @Test
    void shouldEndTheCaptureAsAnAuthorityChangeWhenTheResumeRecheckRefusesOnceTheFolderIsHeld() {
        var job = RuntimeReviewTestData.job();
        job.setRunId(UUID.randomUUID());
        var fence = new AgentRunFence(job.getRunId(), job.getWorkspaceId(), job.getAttempt() + 1);
        var jobs = mock(ReviewJobRepository.class);
        when(jobs.findById(job.getId())).thenReturn(Optional.of(job));
        var files = mock(JobEvidenceFiles.class);
        when(files.preparation(job)).thenReturn(JobEvidenceFiles.Preparation.PUBLISHED);
        when(files.resume(eq(job), any())).thenAnswer(call -> {
            call.<Runnable>getArgument(1).run();
            throw new AssertionError("the resume went on after its recheck refused");
        });
        var refused = new AuthorityRefusedException(new AuthorityRefusal(
                AuthorityBoundary.CONTEXT_READ, AuthorityRefusal.Reason.WORKSPACE_AUTHORITY_NARROWED));
        var authority = mock(AgentRunAuthority.class);
        // The entry recheck passes; the narrowing commits while the resume waits for the folder.
        doNothing().doThrow(refused).when(authority).check(fence, AuthorityBoundary.CONTEXT_READ);
        var capture = mock(ReviewStageTelemetry.Running.class);
        var telemetry = mock(ReviewJobTelemetry.class);
        when(telemetry.stage(ReviewStageTelemetry.Stage.CAPTURE)).thenReturn(capture);
        var handler = mock(DefaultReviewExecutionHandler.class);
        var preparation = new ReviewAttemptPreparation(
                jobs,
                handler,
                mock(ResultAdmissionService.class),
                mock(PracticePiAdapter.class),
                mock(AttemptTokenIssuer.class),
                SandboxTestLimits.PROPERTIES,
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                new JsonMapper(),
                telemetry,
                files,
                authority,
                mock(ReviewPoolAdmission.class));

        assertThatThrownBy(() -> preparation.prepare(
                        job.getId(), job, fence, mock(ConfigSnapshot.class), new AtomicReference<>()))
                .isSameAs(refused);

        verify(capture).end(ReviewStageTelemetry.Outcome.AUTHORITY_CHANGED);
        verify(capture, never()).end(ReviewStageTelemetry.Outcome.FAILED);
        verify(handler, never()).prepareInputs(any());
    }

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
        var ready = RuntimeJobTestData.readyReport("docs.document", Instant.parse("2026-11-13T12:00:00Z"));
        when(handler.prepareInputs(job)).thenReturn(new PreparedJobInputs(Map.of(), List.of(), List.of(), null, ready));
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
                                // The provenance snapshot keeps the captured manifest next to the readiness report.
                                Map.of(SandboxLayout.MANIFEST_PATH, "{}".getBytes(StandardCharsets.UTF_8)),
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
                mock(ResultAdmissionService.class),
                adapter,
                issuer,
                SandboxTestLimits.PROPERTIES,
                new TransactionTemplate(manager),
                mapper,
                mock(ReviewJobTelemetry.class, Answers.RETURNS_DEEP_STUBS),
                files,
                mock(AgentRunAuthority.class),
                mock(ReviewPoolAdmission.class));
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
