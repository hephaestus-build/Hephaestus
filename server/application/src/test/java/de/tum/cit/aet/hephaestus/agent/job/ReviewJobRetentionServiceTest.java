package de.tum.cit.aet.hephaestus.agent.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeStagingStore;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
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

    private SimpleMeterRegistry meterRegistry;
    private ReviewJobRetentionService service;

    private static final AgentProperties AGENT_PROPS = new AgentProperties(
            true, Duration.ofSeconds(1), 5, 5, Duration.ofSeconds(25), Duration.ofDays(14), Duration.ofDays(90), 16);

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
                meterRegistry);
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
                .thenReturn(4);
        when(evidenceCopies.retireReceiptsOfDeletedJobs(anyInt())).thenReturn(BATCH_SIZE, 2);

        service.runRetention();

        var order = inOrder(jobRepository, evidenceCopies);
        order.verify(jobRepository).deleteUnreferencedTerminalRowsOlderThan(any(), eq(BATCH_SIZE));
        order.verify(evidenceCopies, times(2)).retireReceiptsOfDeletedJobs(BATCH_SIZE);
    }
}
