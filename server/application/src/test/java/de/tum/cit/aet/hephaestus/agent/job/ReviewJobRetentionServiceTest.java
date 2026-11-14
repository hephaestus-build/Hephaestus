package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class ReviewJobRetentionServiceTest extends BaseUnitTest {

    private static final int BATCH_SIZE = 500;

    @Mock
    private ReviewJobRepository jobRepository;

    @Mock
    private ReviewCompositionBasisRepository basisRepository;

    @Mock
    private EvidenceFolderPersonDataCatalog evidenceCopies;

    @Mock
    private TransactionTemplate transactionTemplate;

    private final AgentRuns runs = mock(AgentRuns.class);
    private SimpleMeterRegistry meterRegistry;
    private ReviewJobRetentionService service;

    private static final AgentProperties AGENT_PROPS =
            new AgentProperties(true, Duration.ZERO, Duration.ofDays(14), Duration.ofDays(90));

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        service = new ReviewJobRetentionService(
                jobRepository,
                basisRepository,
                mock(PracticeStagingStore.class),
                evidenceCopies,
                AGENT_PROPS,
                Clock.systemUTC(),
                transactionTemplate,
                meterRegistry,
                runs);
    }

    @Test
    @DisplayName("original composition inputs are removed first, batch by batch, before any job payload pass")
    void removesUnusableCompositionInputsBeforeJobPayloads() {
        when(basisRepository.deleteUnusable(any(), anyInt())).thenReturn(BATCH_SIZE, 3);

        service.runRetention();

        var order = inOrder(basisRepository, jobRepository);
        order.verify(basisRepository, times(2)).deleteUnusable(any(), eq(BATCH_SIZE));
        order.verify(jobRepository).stripTerminalPayloads(any(), eq(BATCH_SIZE));
    }

    @Test
    @DisplayName("receipts of deleted review jobs are retired after the rows are deleted, batch by batch")
    void retiresReceiptsAfterDeletingOldRows() {
        when(jobRepository.deleteUnreferencedTerminalRowsOlderThan(any(), anyInt()))
                .thenReturn(List.of(deleted(null), deleted(null), deleted(null), deleted(null)));
        when(evidenceCopies.retireReceiptsOfDeletedJobs(anyInt())).thenReturn(BATCH_SIZE, 2);

        service.runRetention();

        var order = inOrder(jobRepository, evidenceCopies);
        order.verify(jobRepository).deleteUnreferencedTerminalRowsOlderThan(any(), eq(BATCH_SIZE));
        order.verify(evidenceCopies, times(2)).retireReceiptsOfDeletedJobs(BATCH_SIZE);
    }

    @Test
    @DisplayName("a full batch of jobs that handed off counts its jobs, deletes both runs of each, and continues")
    void countsDeletedJobsNotTheirRuns() {
        List<ReviewJobRepository.DeletedJob> full = IntStream.range(0, BATCH_SIZE)
                .mapToObj(ignored -> deleted(UUID.randomUUID()))
                .toList();
        var last = deleted(UUID.randomUUID());
        when(jobRepository.deleteUnreferencedTerminalRowsOlderThan(any(), anyInt()))
                .thenReturn(full)
                .thenReturn(List.of(last));

        service.runRetention();

        verify(jobRepository, times(2)).deleteUnreferencedTerminalRowsOlderThan(any(), eq(BATCH_SIZE));
        verify(runs).retireAll(List.of(last.getRunId(), Objects.requireNonNull(last.getHandedOffRunId())));
        assertThat(meterRegistry
                        .get(AgentMetrics.AGENT_JOB_RETENTION_DELETED)
                        .counter()
                        .count())
                .isEqualTo(BATCH_SIZE + 1);
    }

    private static ReviewJobRepository.DeletedJob deleted(@Nullable UUID handedOff) {
        UUID run = UUID.randomUUID();
        return new ReviewJobRepository.DeletedJob() {
            @Override
            public UUID getRunId() {
                return run;
            }

            @Override
            public @Nullable UUID getHandedOffRunId() {
                return handedOff;
            }
        };
    }
}
