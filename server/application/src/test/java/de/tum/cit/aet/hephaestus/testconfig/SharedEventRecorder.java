package de.tum.cit.aet.hephaestus.testconfig;

import de.tum.cit.aet.hephaestus.integration.scm.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The shared, thread-safe recorder of application events. {@link BaseIntegrationTest} imports it once, so tests reuse
 * one Spring context instead of each declaring (and forking a context for) their own listener, and clears it before
 * each test. It records every event from any thread (testing.mdx § Flaky tests); {@code SharedEventRecorderConventionTest}
 * keeps it the only recorder.
 *
 * <p>Every read names an id that the test owns, because an earlier test's work can still publish after the clear
 * (testing.mdx § Flaky tests).
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

    /** The recorded events of the type whose {@code owner} is {@code ownedId}, in the order they arrived. */
    public <T> List<T> of(Class<T> type, Function<? super T, ? extends @Nullable Object> owner, Object ownedId) {
        return stream(type, owner, ownedId).toList();
    }

    /** The recorded events of the type whose {@code owner} is {@code ownedId}, in the order they arrived. */
    public <T> Stream<T> stream(Class<T> type, Function<? super T, ? extends @Nullable Object> owner, Object ownedId) {
        Objects.requireNonNull(ownedId, "an owned id");
        return events.stream()
                .filter(type::isInstance)
                .map(type::cast)
                .filter(event -> owns(owner.apply(event), ownedId));
    }

    /** The recorded SCM events of the type in the repository with this database id, in the order they arrived. */
    public <T extends ScmDomainEvent.ContextualEvent> List<T> inRepository(Class<T> type, Long repositoryId) {
        return of(type, SharedEventRecorder::repositoryId, repositoryId);
    }

    /** Forgets every recorded event. {@link BaseIntegrationTest} calls it before each test. */
    public void clear() {
        events.clear();
    }

    private static @Nullable Long repositoryId(ScmDomainEvent.ContextualEvent event) {
        RepositoryRef repository = event.context().repository();
        return repository == null ? null : repository.id();
    }

    /**
     * A missing key, or a key of another class than the id, would never match and would make every absence check
     * pass, so both throw. Both checks need a recorded event of the type: with none, there is nothing to match.
     */
    private static boolean owns(@Nullable Object owner, Object ownedId) {
        if (owner == null) {
            throw new IllegalArgumentException(
                    "A recorded event has no value for this key, so the key cannot scope it");
        }
        if (owner.getClass() != ownedId.getClass()) {
            throw new IllegalArgumentException(
                    "An event's owner is a " + owner.getClass().getSimpleName() + ", and the owned id is a "
                            + ownedId.getClass().getSimpleName());
        }
        return ownedId.equals(owner);
    }
}
