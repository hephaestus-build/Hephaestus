package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class AgentJobRetentionServiceTest extends BaseUnitTest {

    private static final int BATCH_SIZE = 500;

    @Mock
    private AgentJobRepository jobRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private SimpleMeterRegistry meterRegistry;
    private AgentJobRetentionService service;

    private static final AgentProperties AGENT_PROPS = new AgentProperties(
            true, Duration.ofSeconds(1), 5, 5, Duration.ofSeconds(25), Duration.ofDays(14), Duration.ofDays(90));

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> cb = inv.getArgument(0);
            return cb.doInTransaction(mock(TransactionStatus.class));
        });
        service = new AgentJobRetentionService(
                jobRepository, AGENT_PROPS, Clock.systemUTC(), transactionTemplate, meterRegistry);
    }

    @Nested
    @DisplayName("Payload stripping")
    class PayloadStripping {

        @Test
        @DisplayName("a single partial batch (< BATCH_SIZE) strips once and stops")
        void singlePartialBatchStopsAfterOnePass() {
            when(jobRepository.stripTerminalPayloads(any(), anyInt())).thenReturn(37);

            service.runRetention();

            verify(jobRepository, times(1)).stripTerminalPayloads(any(), eq(BATCH_SIZE));
            assertThat(meterRegistry.counter("agent.job.retention.stripped").count())
                    .isEqualTo(37d);
        }

        @Test
        @DisplayName("a full batch loops again; stops once a batch returns fewer rows than the batch size")
        void loopsUntilBatchIsPartial() {
            when(jobRepository.stripTerminalPayloads(any(), anyInt())).thenReturn(BATCH_SIZE, BATCH_SIZE, 10);

            service.runRetention();

            verify(jobRepository, times(3)).stripTerminalPayloads(any(), eq(BATCH_SIZE));
            assertThat(meterRegistry.counter("agent.job.retention.stripped").count())
                    .isEqualTo(BATCH_SIZE + BATCH_SIZE + 10d);
        }

        // An empty backlog takes the same loop exit as the partial batch above, and a counter
        // incremented by zero is indistinguishable from one that is not incremented at all.
    }

    @Nested
    @DisplayName("Row deletion")
    class RowDeletion {

        @Test
        @DisplayName("a single partial batch deletes once and stops")
        void singlePartialBatchStopsAfterOnePass() {
            lenient().when(jobRepository.stripTerminalPayloads(any(), anyInt())).thenReturn(0);
            when(jobRepository.deleteUnreferencedTerminalRowsOlderThan(any(), anyInt()))
                    .thenReturn(12);

            service.runRetention();

            verify(jobRepository, times(1)).deleteUnreferencedTerminalRowsOlderThan(any(), eq(BATCH_SIZE));
            assertThat(meterRegistry.counter("agent.job.retention.deleted").count())
                    .isEqualTo(12d);
        }

        @Test
        @DisplayName("a full batch loops again; stops once a batch returns fewer rows than the batch size")
        void loopsUntilBatchIsPartial() {
            lenient().when(jobRepository.stripTerminalPayloads(any(), anyInt())).thenReturn(0);
            when(jobRepository.deleteUnreferencedTerminalRowsOlderThan(any(), anyInt()))
                    .thenReturn(BATCH_SIZE, 5);

            service.runRetention();

            verify(jobRepository, times(2)).deleteUnreferencedTerminalRowsOlderThan(any(), eq(BATCH_SIZE));
            assertThat(meterRegistry.counter("agent.job.retention.deleted").count())
                    .isEqualTo(BATCH_SIZE + 5d);
        }
    }
}
