package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.action.AgentActions;
import de.tum.cit.aet.hephaestus.practices.metrics.PracticeMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

@Tag("unit")
class FeedbackDeliveryFreshnessTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FeedbackRepository feedback = mock(FeedbackRepository.class);
    private final AgentActions actions = mock(AgentActions.class);
    private final FeedbackDeliveryFreshness freshness = new FeedbackDeliveryFreshness(feedback, actions, registry);

    @Test
    void shouldReportTheAgeOfTheOldestWaitingFeedbackAndZeroWhenNoneWaits() {
        assertThat(oldest()).as("no read yet").isNaN();

        when(feedback.oldestWaitingOnWorkSeconds()).thenReturn(4_200.0);
        freshness.sample();
        assertThat(oldest()).isEqualTo(4_200.0);

        when(feedback.oldestWaitingOnWorkSeconds()).thenReturn(null);
        freshness.sample();
        assertThat(oldest()).isZero();
    }

    @Test
    void shouldReportNoValueAfterAFailedRead() {
        when(feedback.oldestWaitingOnWorkSeconds()).thenReturn(60.0);
        freshness.sample();
        when(feedback.oldestWaitingOnWorkSeconds()).thenThrow(new QueryTimeoutException("timeout"));
        freshness.sample();

        assertThat(oldest()).isNaN();
    }

    @Test
    void shouldReportSendAttemptsAndZeroWhenNoSendWaits() {
        assertThat(attempts()).isNaN();
        when(actions.maxLiveAttempts(any())).thenReturn(4, 0);
        freshness.sample();
        assertThat(attempts()).isEqualTo(4);
        freshness.sample();
        assertThat(attempts()).isZero();
    }

    @Test
    void shouldClearSendAttemptsAfterAFailedReadAndRecoverIndependentlyOfTheAge() {
        when(actions.maxLiveAttempts(any()))
                .thenReturn(4)
                .thenThrow(new QueryTimeoutException("fixture"))
                .thenReturn(2);
        freshness.sample();
        freshness.sample();
        assertThat(attempts()).isNaN();
        assertThat(oldest()).isZero();
        freshness.sample();
        assertThat(attempts()).isEqualTo(2);
    }

    private double attempts() {
        return registry.get(PracticeMetrics.FEEDBACK_SEND_ATTEMPTS).gauge().value();
    }

    private double oldest() {
        return registry.get(PracticeMetrics.FEEDBACK_OLDEST_WAITING).gauge().value();
    }
}
