package de.tum.cit.aet.hephaestus.core.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.metrics.CoreMetrics;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

class ActionBacklogSamplerTest extends BaseUnitTest {

    private final AgentActionRepository repository = mock(AgentActionRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<ActionAdapter> adapters = ActionTestSupport.adapters(attempt -> new ActionResult.Done(null));
    private final String decodable = ActionAdmission.of(adapters).json();
    // Micrometer gauges hold their state object by weak reference. The fixture keeps the sampler, which owns that
    // state, for the meter's lifetime, as the Spring singleton does.
    private final ActionBacklogSampler sampler = new ActionBacklogSampler(repository, adapters, meters);

    @Test
    void shouldStartWithAnEmptyBacklogForEveryKindAndOpenState() {
        for (ActionKind kind : ActionKind.values()) {
            for (String state : List.of("PROPOSED", "ADMITTED", "EXECUTING"))
                assertThat(backlog(kind, state)).isZero();
            assertThat(oldestDue(kind)).isZero();
        }
    }

    @Test
    void shouldKeepTheLastValuesAndCountTheFailureWhenAPassFails() {
        when(repository.backlog(decodable)).thenReturn(List.of(row("EMAIL", "ADMITTED", 4, 900)));
        sampler.sample();

        when(repository.backlog(decodable)).thenThrow(new QueryTimeoutException("slow"));
        sampler.sample();

        assertThat(backlog(ActionKind.EMAIL, "ADMITTED")).isEqualTo(4);
        assertThat(oldestDue(ActionKind.EMAIL)).isEqualTo(900);
        assertThat(meters.get(CoreMetrics.ACTION_BACKLOG_SAMPLER_FAILURES)
                        .counter()
                        .count())
                .isEqualTo(1);
    }

    @Test
    void shouldReadAKindWithNoOpenActionAsAnEmptyBacklogAgain() {
        when(repository.backlog(decodable)).thenReturn(List.of(row("EMAIL", "EXECUTING", 2, 0)));
        sampler.sample();
        when(repository.backlog(decodable)).thenReturn(List.of());
        sampler.sample();

        assertThat(backlog(ActionKind.EMAIL, "EXECUTING")).isZero();
    }

    private double backlog(ActionKind kind, String state) {
        return meters.get(CoreMetrics.ACTION_BACKLOG)
                .tag("kind", kind.name())
                .tag("state", state)
                .gauge()
                .value();
    }

    private double oldestDue(ActionKind kind) {
        return meters.get(CoreMetrics.ACTION_OLDEST_DUE_SECONDS)
                .tag("kind", kind.name())
                .gauge()
                .value();
    }

    private static AgentActionRepository.BacklogRow row(String kind, String state, long actions, long oldestDue) {
        return new AgentActionRepository.BacklogRow() {
            @Override
            public String getKind() {
                return kind;
            }

            @Override
            public String getState() {
                return state;
            }

            @Override
            public long getActions() {
                return actions;
            }

            @Override
            public long getOldestDueSeconds() {
                return oldestDue;
            }
        };
    }
}
