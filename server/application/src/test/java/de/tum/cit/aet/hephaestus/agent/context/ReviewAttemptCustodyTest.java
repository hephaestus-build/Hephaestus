package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService.AdmissionIdentity;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.run.AgentRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ReviewAttemptCustodyTest extends BaseUnitTest {

    @TempDir
    Path root;

    private final AgentRuns runs = mock(AgentRuns.class);
    private final ReviewAttemptCustody custody = new ReviewAttemptCustody(runs);
    private final AdmissionIdentity attempt = new AdmissionIdentity(UUID.randomUUID(), 7, 2, UUID.randomUUID());
    private final AtomicInteger prepared = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    private PreparedJobInputs inputs() {
        prepared.incrementAndGet();
        return new PreparedJobInputs(Map.of(), List.of(), List.of(closed::incrementAndGet), null, null);
    }

    @Test
    void shouldPrepareAnAttemptsInputsOnceAndHoldThemUntilTheyAreReleased() {
        PreparedJobInputs first = custody.hold(attempt, this::inputs);

        assertThat(custody.hold(attempt, this::inputs)).isSameAs(first);
        assertThat(prepared).hasValue(1);
        assertThat(closed).hasValue(0);

        custody.release(attempt);
        assertThat(closed).hasValue(1);
        custody.release(attempt);
        assertThat(closed).as("a second release closes nothing").hasValue(1);
    }

    @Test
    void shouldHoldNothingWhenItsPreparationFailsSoTheNextHoldPreparesAgain() {
        assertThatThrownBy(() -> custody.hold(attempt, () -> {
                    throw new IllegalStateException("the inputs were erased");
                }))
                .isInstanceOf(IllegalStateException.class);

        custody.hold(attempt, this::inputs);
        assertThat(prepared).hasValue(1);
    }

    @Test
    void shouldReleaseOnlyWhenTheTransactionThatEndsTheRunCommits() {
        custody.hold(attempt, this::inputs);
        TransactionSynchronizationManager.initSynchronization();
        custody.release(attempt);
        assertThat(closed).as("not before the commit").hasValue(0);

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(closed).as("a rollback keeps the inputs").hasValue(0);

        synchronizations.forEach(TransactionSynchronization::afterCommit);
        assertThat(closed).hasValue(1);
    }

    @Test
    void shouldReleaseTheInputsOfAnAttemptWhoseRunNoLongerHoldsThem() {
        custody.hold(attempt, this::inputs);
        var later = new AdmissionIdentity(UUID.randomUUID(), 7, 0, UUID.randomUUID());
        custody.hold(later, this::inputs);
        var moved = mock(AgentRun.class);
        when(moved.getState()).thenReturn(AgentRunState.LEASED);
        when(moved.getLeaseEpoch()).thenReturn(4);
        when(runs.find(new AgentRunRef(attempt.runId(), 7))).thenReturn(Optional.of(moved));
        var current = mock(AgentRun.class);
        when(current.getState()).thenReturn(AgentRunState.LEASED);
        when(current.getLeaseEpoch()).thenReturn(1);
        when(runs.find(new AgentRunRef(later.runId(), 7))).thenReturn(Optional.of(current));

        custody.releaseEnded();

        assertThat(closed).as("only the attempt whose run moved on").hasValue(1);
        assertThat(custody.hold(later, this::inputs))
                .as("the current attempt keeps its inputs")
                .isNotNull();
        assertThat(prepared).as("without preparing them again").hasValue(2);
    }

    @Test
    void shouldReleaseWithoutWaitingForPreparationAndCloseItsLateInputs() throws Exception {
        var preparing = new CountDownLatch(1);
        var admission = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var holder = executor.submit(() -> custody.hold(attempt, () -> {
                preparing.countDown();
                try {
                    if (!admission.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("Admission stayed closed");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                try {
                    Path late = Files.writeString(root.resolve("late-input"), "personal evidence");
                    prepared.incrementAndGet();
                    return new PreparedJobInputs(
                            Map.of("context/person.json", late),
                            List.of(),
                            List.of(() -> {
                                Files.delete(late);
                                closed.incrementAndGet();
                            }),
                            null,
                            null);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }));
            try {
                assertThat(preparing.await(5, TimeUnit.SECONDS)).isTrue();
                // Erasure can release custody while the capture cannot yet obtain admission.
                executor.submit(() -> custody.releaseJob(attempt.jobId())).get(5, TimeUnit.SECONDS);
            } finally {
                admission.countDown();
            }
            assertThatThrownBy(() -> holder.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(prepared).hasValue(1);
            assertThat(closed)
                    .as("the cancelled preparation cannot retain its late inputs")
                    .hasValue(1);
            assertThat(root.resolve("late-input")).doesNotExist();
        }
    }
}
