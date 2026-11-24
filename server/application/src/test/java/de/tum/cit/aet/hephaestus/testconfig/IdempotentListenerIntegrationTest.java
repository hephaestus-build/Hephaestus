package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The harness for an event listener of a mentoring module. The event publication registry delivers an event at least
 * once and in no fixed order, so a listener must reach the same state when one event arrives twice and when two
 * events arrive in swapped order. A test of listener {@code L} for event {@code E} implements this interface,
 * and {@code RegistryEventShapeArchTest} requires one for every pair of a mentoring listener and an event it handles.
 * Each event arrives twice in a run of its own, so effects of the two events cannot cancel each other out.
 *
 * <p>{@link #deliver} must run as the registry runs it: through the listener, and committed in its own transaction,
 * for example through a {@code TransactionTemplate}. A test class that is itself {@code @Transactional} would hide a
 * check that only the commit makes. Each call of {@link #newEvents()} names subjects that no other call used, so the
 * three runs share no row.
 *
 * @param <L> the listener class that this test covers
 * @param <E> the event type
 */
@Tag("integration")
public interface IdempotentListenerIntegrationTest<L, E> {

    /** Two events about subjects that no earlier call named, in the order their causes committed. */
    Events<E> newEvents();

    /** Delivers one event to the listener and commits, as the registry does. */
    void deliver(E event);

    /** The state the two events left, without their own ids, so that the runs on different subjects compare. */
    Object stateAfter(Events<E> events);

    @Test
    default void shouldReachTheSameStateWhenTheFirstEventArrivesTwice() {
        var expected = deliveredInOrder();
        var events = newEvents();
        deliver(events.first());
        deliver(events.first());
        deliver(events.second());
        assertThat(stateAfter(events))
                .as("state after the first event arrived twice")
                .isEqualTo(expected);
    }

    @Test
    default void shouldReachTheSameStateWhenTheSecondEventArrivesTwice() {
        var expected = deliveredInOrder();
        var events = newEvents();
        deliver(events.first());
        deliver(events.second());
        deliver(events.second());
        assertThat(stateAfter(events))
                .as("state after the second event arrived twice")
                .isEqualTo(expected);
    }

    @Test
    default void shouldReachTheSameStateWhenTwoEventsArriveInSwappedOrder() {
        var expected = deliveredInOrder();
        var events = newEvents();
        deliver(events.second());
        deliver(events.first());
        assertThat(stateAfter(events))
                .as("state after the events arrived in swapped order")
                .isEqualTo(expected);
    }

    private Object deliveredInOrder() {
        var events = newEvents();
        deliver(events.first());
        deliver(events.second());
        return stateAfter(events);
    }

    record Events<E>(E first, E second) {}
}
