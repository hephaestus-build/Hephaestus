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
        assertThatCode(harness::shouldReachTheSameStateWhenTheFirstEventArrivesTwice)
                .doesNotThrowAnyException();
        assertThatCode(harness::shouldReachTheSameStateWhenTheSecondEventArrivesTwice)
                .doesNotThrowAnyException();
        assertThatCode(harness::shouldReachTheSameStateWhenTwoEventsArriveInSwappedOrder)
                .doesNotThrowAnyException();
    }

    @Test
    void shouldFailAListenerThatCountsEachDeliveryOfItsFirstEvent() {
        Map<UUID, Integer> deliveries = new HashMap<>();
        var harness = new Fixture(
                event -> deliveries.merge(event, 1, Integer::sum),
                events -> deliveries.getOrDefault(events.first(), 0));
        assertThatThrownBy(harness::shouldReachTheSameStateWhenTheFirstEventArrivesTwice)
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void shouldFailAListenerThatCountsEachDeliveryOfItsSecondEvent() {
        Map<UUID, Integer> deliveries = new HashMap<>();
        var harness = new Fixture(
                event -> deliveries.merge(event, 1, Integer::sum),
                events -> List.of(deliveries.containsKey(events.first()), deliveries.getOrDefault(events.second(), 0)));
        assertThatThrownBy(harness::shouldReachTheSameStateWhenTheSecondEventArrivesTwice)
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void shouldFailAListenerWhoseTwoEventsCancelEachOtherOut() {
        // The first event of each pair adds one and the second takes one away, so A,A,B,B and B,A both end at zero.
        Set<UUID> firsts = new HashSet<>();
        int[] balance = {0};
        var harness = new Fixture(event -> balance[0] += firsts.contains(event) ? 1 : -1, events -> balance[0]) {
            @Override
            public Events<UUID> newEvents() {
                var events = super.newEvents();
                firsts.add(events.first());
                balance[0] = 0;
                return events;
            }
        };
        assertThatThrownBy(harness::shouldReachTheSameStateWhenTheFirstEventArrivesTwice)
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void shouldFailAListenerThatKeepsTheLastEventItReceived() {
        List<UUID> received = new ArrayList<>();
        var harness = new Fixture(received::add, events -> received.getLast().equals(events.second()));
        assertThatThrownBy(harness::shouldReachTheSameStateWhenTwoEventsArriveInSwappedOrder)
                .isInstanceOf(AssertionError.class);
    }

    private static class Fixture implements IdempotentListenerIntegrationTest<Consumer<UUID>, UUID> {
        private final Consumer<UUID> listener;
        private final Function<Events<UUID>, Object> state;

        Fixture(Consumer<UUID> listener, Function<Events<UUID>, Object> state) {
            this.listener = listener;
            this.state = state;
        }

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
