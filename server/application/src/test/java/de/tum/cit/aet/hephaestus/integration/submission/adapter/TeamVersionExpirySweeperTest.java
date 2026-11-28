package de.tum.cit.aet.hephaestus.integration.submission.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.PrivacyJobMetrics;
import de.tum.cit.aet.hephaestus.integration.core.sync.MutableClock;
import de.tum.cit.aet.hephaestus.integration.submission.TeamItemVersionRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class TeamVersionExpirySweeperTest extends BaseUnitTest {

    @Mock
    private TeamItemVersionRepository versions;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = MutableClock.atFixedInstant();
    private TeamVersionExpirySweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper = new TeamVersionExpirySweeper(versions, new PrivacyJobMetrics(registry), clock);
    }

    @Test
    void shouldRunBatchesUntilOneIsNotFullAndRecordSuccess() {
        when(versions.expireDue(TeamVersionExpirySweeper.BATCH)).thenReturn(TeamVersionExpirySweeper.BATCH, 4);

        sweeper.sweep();

        verify(versions, times(2)).expireDue(TeamVersionExpirySweeper.BATCH);
        assertThat(runs("success")).isOne();
        assertThat(ended()).isEqualTo(TeamVersionExpirySweeper.BATCH + 4);
    }

    @Test
    void shouldStartNoBatchAfterTheBudgetAndRecordThatWorkRemains() {
        when(versions.existsDue()).thenReturn(true);
        when(versions.expireDue(TeamVersionExpirySweeper.BATCH)).thenAnswer(invocation -> {
            clock.advance(Duration.ofMinutes(1));
            return TeamVersionExpirySweeper.BATCH;
        });

        sweeper.sweep();

        verify(versions, times((int) TeamVersionExpirySweeper.BUDGET.toMinutes()))
                .expireDue(TeamVersionExpirySweeper.BATCH);
        assertThat(runs("incomplete")).isOne();
        assertThat(runs("success")).isZero();
    }

    @Test
    void shouldReportSuccessWhenTheLastFullBatchDrainsTheBacklog() {
        var remaining = new AtomicInteger(TeamVersionExpirySweeper.BATCH);
        when(versions.expireDue(TeamVersionExpirySweeper.BATCH)).thenAnswer(invocation -> {
            clock.advance(TeamVersionExpirySweeper.BUDGET);
            return remaining.getAndSet(0);
        });

        sweeper.sweep();

        assertThat(remaining.get()).isZero();
        assertThat(runs("success")).isOne();
    }

    @Test
    void shouldRecordThatWorkRemainsWhenAShortBatchLeavesADueVersion() {
        when(versions.expireDue(TeamVersionExpirySweeper.BATCH)).thenReturn(1);
        when(versions.existsDue()).thenReturn(true);

        sweeper.sweep();

        assertThat(runs("incomplete")).isOne();
        assertThat(runs("success")).isZero();
    }

    @Test
    void shouldRecordAFailureAndKeepTheCountOfTheBatchesBeforeIt() {
        var failure = new IllegalStateException("database away");
        when(versions.expireDue(TeamVersionExpirySweeper.BATCH))
                .thenReturn(TeamVersionExpirySweeper.BATCH)
                .thenThrow(failure);

        assertThatThrownBy(sweeper::sweep).isSameAs(failure);

        assertThat(runs("failure")).isOne();
        assertThat(ended()).isEqualTo(TeamVersionExpirySweeper.BATCH);
    }

    private double runs(String outcome) {
        return registry.get("privacy.job.completed")
                .tags("job", "team_version_expiry", "outcome", outcome)
                .counter()
                .count();
    }

    private double ended() {
        return registry.get("privacy.job.affected")
                .tag("job", "team_version_expiry")
                .counter()
                .count();
    }
}
