package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.DeliveredSignal;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.Disposition;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDispatchRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

class FeedbackDispatchStateMachineTest extends BaseUnitTest {
    @Mock
    private FeedbackDispatchRepository repository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private MeterRegistry registry;

    private FeedbackDispatchStateMachine machine() {
        return new FeedbackDispatchStateMachine(repository, transactionTemplate, registry, new ObjectMapper());
    }

    @Test
    void shouldKeepDifferentObservationDeliveriesSeparateAtIdenticalCoordinates() {
        var anchor = FeedbackAnchor.DiffAnchor.singleLine("Same.java", 10);
        var first = new DeliveredSignal("observation:first", anchor, Disposition.POSTED, "1", null);
        var second = new DeliveredSignal("observation:second", anchor, Disposition.FAILED, null, null);
        assertThat(machine().mergeSignals(List.of(first), List.of(second))).containsExactly(first, second);
    }

    @Test
    void shouldMergeRetriesOnlyWhenExactDeliveryIdentityMatches() {
        var anchor = FeedbackAnchor.DiffAnchor.singleLine("Same.java", 10);
        var posted = new DeliveredSignal("observation:first", anchor, Disposition.POSTED, "1", null);
        var failedRetry = new DeliveredSignal("observation:first", anchor, Disposition.FAILED, null, null);
        assertThat(machine().mergeSignals(List.of(posted), List.of(failedRetry)))
                .containsExactly(posted);
    }

    @Test
    void shouldPreserveUnkeyedPlacementsWithoutInferringEquivalenceFromCoordinates() {
        var anchor = FeedbackAnchor.DiffAnchor.singleLine("Same.java", 10);
        var first = new DeliveredSignal(null, anchor, Disposition.POSTED, "1", null);
        var second = new DeliveredSignal(null, anchor, Disposition.POSTED, "2", null);
        assertThat(machine().mergeSignals(List.of(first), List.of(second))).containsExactly(first, second);
    }
}
