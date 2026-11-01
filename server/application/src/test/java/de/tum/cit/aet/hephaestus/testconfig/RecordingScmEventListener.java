package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Shared passive recorder for SCM domain events, imported once by {@link BaseIntegrationTest} so
 * handler/processor tests reuse one Spring context instead of each declaring (and forking a context
 * for) their own listener. Read back with {@link #ofType(Class)}; {@link #clear()} between tests.
 */
@Component
public class RecordingScmEventListener {

    private final List<Object> events = new CopyOnWriteArrayList<>();

    @EventListener
    void onScmEvent(ScmDomainEvent.Event event) {
        events.add(event);
    }

    /** All recorded events of the given concrete type, in publish order. */
    public <T> List<T> ofType(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** Clear recorded events — call in {@code @BeforeEach}. */
    public void clear() {
        events.clear();
    }
}
