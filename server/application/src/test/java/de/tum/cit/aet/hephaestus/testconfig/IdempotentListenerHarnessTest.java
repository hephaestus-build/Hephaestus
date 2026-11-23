package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.IdempotentListenerIntegrationTest.Events;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The harness passes a listener that dedupes and ignores order, and fails one that counts or keeps the last event. */
@Tag("unit")
class IdempotentListenerHarnessTest {

    @Test
    void shouldPassAListenerThatKeepsASetOfDeliveredEvents() {
        Set<UUID> seen = new HashSet<>();
        var harness = new Fixture(seen::add, events -> seen.containsAll(List.of(events.first(), events.second())));
        assertThatCode(harness::shouldReachTheSameStateWhenEachEventArrivesTwice)
                .doesNotThrowAnyException();
        assertThatCode(harness::shouldReachTheSameStateWhenTwoEventsArriveInSwappedOrder)
                .doesNotThrowAnyException();
    }

    @Test
    void shouldFailAListenerThatCountsEachDelivery() {
        Map<UUID, Integer> deliveries = new HashMap<>();
        var harness = new Fixture(
                event -> deliveries.merge(event, 1, Integer::sum),
                events -> List.of(
                        deliveries.getOrDefault(events.first(), 0), deliveries.getOrDefault(events.second(), 0)));
        assertThatThrownBy(harness::shouldReachTheSameStateWhenEachEventArrivesTwice)
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void shouldFailAListenerThatCountsTheDeliveriesOfItsSecondEvent() {
        // Delivering only the first event twice would let this listener pass: its first event shows no count.
        Map<UUID, Integer> deliveries = new HashMap<>();
        var harness = new Fixture(
                event -> deliveries.merge(event, 1, Integer::sum),
                events -> List.of(deliveries.containsKey(events.first()), deliveries.getOrDefault(events.second(), 0)));
        assertThatThrownBy(harness::shouldReachTheSameStateWhenEachEventArrivesTwice)
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void shouldFailAListenerThatKeepsTheLastEventItReceived() {
        List<UUID> received = new ArrayList<>();
        var harness = new Fixture(received::add, events -> received.getLast().equals(events.second()));
        assertThatThrownBy(harness::shouldReachTheSameStateWhenTwoEventsArriveInSwappedOrder)
                .isInstanceOf(AssertionError.class);
    }

    private record Fixture(Consumer<UUID> listener, Function<Events<UUID>, Object> state)
            implements IdempotentListenerIntegrationTest<Consumer<UUID>, UUID> {
        @Override
        public Events<UUID> newEvents() {
            return new Events<>(UUID.randomUUID(), UUID.randomUUID());
        }

        @Override
        public void deliver(UUID event) {
            listener.accept(event);
        }

        @Override
        public Object stateAfter(Events<UUID> events) {
            return state.apply(events);
        }
    }
}
