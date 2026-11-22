package de.tum.cit.aet.hephaestus.testconfig;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The shared, thread-safe recorder of application events. {@link BaseIntegrationTest} imports it once, so tests reuse
 * one Spring context instead of each declaring (and forking a context for) their own listener, and clears it before
 * each test. It records every event from any thread (testing.mdx § Flaky tests); {@code SharedEventRecorderConventionTest}
 * keeps it the only recorder.
 *
 * <p>It has no order among the other listeners of an event. When an earlier listener throws, the multicaster stops and
 * the recorder does not see the event.
 */
@Component
public class SharedEventRecorder {

    private final Queue<Object> events = new ConcurrentLinkedQueue<>();

    @EventListener
    void onEvent(Object event) {
        events.add(event);
    }

    /** All recorded events of the given type, in the order they arrived. */
    public <T> List<T> ofType(Class<T> type) {
        return stream(type).toList();
    }

    /** All recorded events of the given type, in the order they arrived, as {@code ApplicationEvents} streams them. */
    public <T> Stream<T> stream(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast);
    }

    /** Forgets every recorded event. {@link BaseIntegrationTest} calls it before each test. */
    public void clear() {
        events.clear();
    }
}
