package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ReviewJobLifecycleServiceTest extends BaseUnitTest {

    @Mock
    private ReviewJobRepository jobRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private ReviewJobLifecycleService service;

    private Workspace workspace;

    @BeforeEach
    void setUp() {
        service = new ReviewJobLifecycleService(
                jobRepository,
                transactionTemplate,
                new ReviewJobTelemetry(new SimpleMeterRegistry(), Tracer.NOOP),
                mock(AgentRuns.class));

        workspace = new Workspace();
        workspace.setId(1L);
        workspace.setWorkspaceSlug("test-ws");

        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        lenient()
                .doAnswer(inv -> {
                    Consumer<TransactionStatus> action = inv.getArgument(0);
                    action.accept(mock(TransactionStatus.class));
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
    }

    private ReviewJob createJobWithStatus(ReviewJobStatus status) {
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());
        job.setStatus(status);

        ConfigSnapshot snapshot = new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        new FrozenModel(
                                LlmApiProtocol.OPENAI_COMPLETIONS,
                                "https://api.openai.com/v1",
                                "test-model",
                                UsageFundingSource.INSTANCE,
                                1L,
                                1L,
                                null,
                                null,
                                null,
                                null,
                                false),
                        null,
                        null,
                        600)
                .withPriceSnapshot(new LlmPriceSnapshot(
                        UsageFundingSource.INSTANCE,
                        UsagePricingState.PRICED,
                        1L,
                        null,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE,
                        BigDecimal.ONE));
        job.setConfigSnapshot(snapshot.toJson(objectMapper));
        job.prePersist();
        return job;
    }

    @Nested
    class Cancel {

        @Test
        void shouldBeIdempotentForCancelledJob() {
            ReviewJob job = createJobWithStatus(ReviewJobStatus.CANCELLED);
            UUID jobId = job.getId();

            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));

            ReviewJob result = service.cancel(1L, jobId);

            assertThat(result.getStatus()).isEqualTo(ReviewJobStatus.CANCELLED);
            verify(jobRepository, never()).transitionToCancelled(any(), any(), any(), any(), any());
        }

        @ParameterizedTest
        @EnumSource(
                value = ReviewJobStatus.class,
                names = {"COMPLETED", "FAILED", "TIMED_OUT"})
        void shouldThrow409ForAnAlreadyTerminalJob(ReviewJobStatus terminalStatus) {
            ReviewJob job = createJobWithStatus(terminalStatus);
            UUID jobId = job.getId();

            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> service.cancel(1L, jobId))
                    .isInstanceOf(ReviewJobStateConflictException.class)
                    .hasMessage("This review already finished. You cannot cancel it.");
        }

        @Test
        void shouldThrow404ForNonExistentJob() {
            UUID jobId = UUID.randomUUID();
            when(jobRepository.findByIdAndWorkspaceId(jobId, 1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancel(1L, jobId)).isInstanceOf(EntityNotFoundException.class);
        }
    }
}
