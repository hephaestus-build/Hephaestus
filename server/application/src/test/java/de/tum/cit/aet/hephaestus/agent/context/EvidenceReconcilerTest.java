package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog.ErasedFolders;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** One reconcile pass: erased folders first, then ended attempts, then the recorded inputs of ended attempts. */
@Tag("unit")
class EvidenceReconcilerTest {
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");

    private final JobEvidenceFiles files = mock(JobEvidenceFiles.class);
    private final EvidenceFolderPersonDataCatalog copies = mock(EvidenceFolderPersonDataCatalog.class);
    private final AttemptInputs inputs = mock(AttemptInputs.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final EvidenceReconciler reconciler =
            new EvidenceReconciler(files, copies, inputs, Clock.fixed(NOW, ZoneOffset.UTC), meters);

    @BeforeEach
    void setUp() {
        when(copies.removeErasedFolders()).thenReturn(new ErasedFolders(0, 0));
    }

    @Test
    void shouldRemoveErasedFoldersFirstAndRecordTheSuccessfulPass() {
        reconciler.reconcile();

        var order = inOrder(copies, files, inputs);
        order.verify(copies).removeErasedFolders();
        order.verify(files).cleanEndedAttempts();
        order.verify(inputs).removeEnded();
        assertThat(lastSuccess()).isEqualTo((double) NOW.getEpochSecond());
        assertThat(failures()).isZero();
    }

    @Test
    void shouldCountAFailedPassAndKeepTheLastSuccessForTheNextOne() {
        doThrow(new IllegalStateException("database unavailable")).when(copies).removeErasedFolders();

        reconciler.reconcile();

        assertThat(failures()).isEqualTo(1);
        assertThat(lastSuccess()).isZero();
    }

    @Test
    void shouldNotCallAPassThatLeftAnErasedFolderASuccess() {
        when(copies.removeErasedFolders()).thenReturn(new ErasedFolders(1, 2));

        reconciler.reconcile();

        assertThat(failures()).as("each erased folder that remains").isEqualTo(2);
        assertThat(lastSuccess())
                .as("the stalled-reconcile alert sees an erasure that does not reach this server")
                .isZero();
    }

    @Test
    void shouldNotCallAPassThatLeftADeletedJobsFolderASuccess() {
        when(files.cleanEndedAttempts()).thenReturn(1);

        reconciler.reconcile();

        assertThat(failures()).isEqualTo(1);
        assertThat(lastSuccess()).isZero();
    }

    @Test
    void shouldStateAnErasureBoundThatHoldsTheIntervalAndOnePass() {
        assertThat(EvidenceReconciler.ERASURE_BOUND)
                .isGreaterThanOrEqualTo(EvidenceReconciler.INTERVAL.plus(Duration.ofMinutes(1)));
    }

    private double lastSuccess() {
        return meters.get(AgentMetrics.EVIDENCE_RECONCILE_LAST_SUCCESS).gauge().value();
    }

    private double failures() {
        return meters.get(AgentMetrics.EVIDENCE_RECONCILE_FAILURES).counter().count();
    }
}
