package de.tum.cit.aet.hephaestus.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class AgentRunSamplerTest extends BaseUnitTest {

    private final AgentRunRepository repository = mock(AgentRunRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @Test
    void shouldStartAtZeroForEveryKindBandStateAndHold() {
        new AgentRunSampler(repository, meters);

        for (AgentRunKind kind : AgentRunKind.values()) {
            for (AgentRunBand band : AgentRunBand.values()) {
                for (String hold : List.of("none", "budget", "sandbox_room"))
                    assertThat(runs(kind, band, "queued", hold)).isZero();
                assertThat(runs(kind, band, "leased", "none")).isZero();
                assertThat(due(kind, band)).isZero();
                assertThat(oldestDue(kind, band)).isZero();
            }
        }
        assertThat(meters.get(AgentMetrics.AGENT_RUN_SAMPLER_FAILURE).counter().count())
                .isZero();
    }

    @Test
    void shouldPublishEachRowUnderItsOwnLabels() {
        var sampler = new AgentRunSampler(repository, meters);
        when(repository.open())
                .thenReturn(List.of(
                        row("REVIEW", "BATCH", "QUEUED", null, 5, 3, 900),
                        row("REVIEW", "BATCH", "QUEUED", "BUDGET", 2, 0, 0),
                        row("REVIEW_COMPOSE", "BACKGROUND", "QUEUED", "SANDBOX_ROOM", 1, 0, 0),
                        row("TURN", "INTERACTIVE", "LEASED", null, 4, 0, 0)));

        sampler.sample();

        assertThat(runs(AgentRunKind.REVIEW, AgentRunBand.BATCH, "queued", "none"))
                .isEqualTo(5);
        assertThat(runs(AgentRunKind.REVIEW, AgentRunBand.BATCH, "queued", "budget"))
                .isEqualTo(2);
        assertThat(runs(AgentRunKind.REVIEW_COMPOSE, AgentRunBand.BACKGROUND, "queued", "sandbox_room"))
                .isEqualTo(1);
        assertThat(runs(AgentRunKind.TURN, AgentRunBand.INTERACTIVE, "leased", "none"))
                .isEqualTo(4);
        assertThat(due(AgentRunKind.REVIEW, AgentRunBand.BATCH)).isEqualTo(3);
        assertThat(oldestDue(AgentRunKind.REVIEW, AgentRunBand.BATCH)).isEqualTo(900);
        assertThat(due(AgentRunKind.REVIEW, AgentRunBand.BACKGROUND)).isZero();
    }

    /** A DB blip must not read as "nothing waits" and silence an alert that was firing. */
    @Test
    void shouldKeepTheLastValuesAndCountTheFailureWhenAPassFails() {
        var sampler = new AgentRunSampler(repository, meters);
        when(repository.open()).thenReturn(List.of(row("REVIEW", "BATCH", "QUEUED", null, 4, 4, 4000)));
        sampler.sample();

        when(repository.open()).thenThrow(new QueryTimeoutException("slow"));
        sampler.sample();

        assertThat(oldestDue(AgentRunKind.REVIEW, AgentRunBand.BATCH)).isEqualTo(4000);
        assertThat(due(AgentRunKind.REVIEW, AgentRunBand.BATCH)).isEqualTo(4);
        assertThat(meters.get(AgentMetrics.AGENT_RUN_SAMPLER_FAILURE).counter().count())
                .isEqualTo(1);
    }

    @Test
    void shouldReadACombinationWithoutRunsAsZeroAgain() {
        var sampler = new AgentRunSampler(repository, meters);
        when(repository.open()).thenReturn(List.of(row("REVIEW", "BATCH", "QUEUED", "BUDGET", 2, 0, 0)));
        sampler.sample();
        when(repository.open()).thenReturn(List.of());
        sampler.sample();

        assertThat(runs(AgentRunKind.REVIEW, AgentRunBand.BATCH, "queued", "budget"))
                .isZero();
    }

    private double runs(AgentRunKind kind, AgentRunBand band, String state, String hold) {
        return meters.get(AgentMetrics.AGENT_RUN_RUNS)
                .tag("kind", AgentRuns.tag(kind))
                .tag("band", AgentRuns.tag(band))
                .tag("state", state)
                .tag("hold_reason", hold)
                .gauge()
                .value();
    }

    private double due(AgentRunKind kind, AgentRunBand band) {
        return meters.get(AgentMetrics.AGENT_RUN_DUE)
                .tag("kind", AgentRuns.tag(kind))
                .tag("band", AgentRuns.tag(band))
                .gauge()
                .value();
    }

    private double oldestDue(AgentRunKind kind, AgentRunBand band) {
        return meters.get(AgentMetrics.AGENT_RUN_OLDEST_DUE_AGE_SECONDS)
                .tag("kind", AgentRuns.tag(kind))
                .tag("band", AgentRuns.tag(band))
                .gauge()
                .value();
    }

    private static AgentRunRepository.OpenRow row(
            String kind, String band, String state, @Nullable String hold, long runs, long due, long oldestDueSeconds) {
        return new AgentRunRepository.OpenRow() {
            @Override
            public String getKind() {
                return kind;
            }

            @Override
            public String getBand() {
                return band;
            }

            @Override
            public String getState() {
                return state;
            }

            @Override
            public @Nullable String getHoldReason() {
                return hold;
            }

            @Override
            public long getRuns() {
                return runs;
            }

            @Override
            public long getDue() {
                return due;
            }

            @Override
            public long getOldestDueSeconds() {
                return oldestDueSeconds;
            }
        };
    }
}
