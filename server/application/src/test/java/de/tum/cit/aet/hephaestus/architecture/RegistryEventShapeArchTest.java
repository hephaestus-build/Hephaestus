package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import de.tum.cit.aet.hephaestus.core.event.ContentFree;
import de.tum.cit.aet.hephaestus.testconfig.IdempotentListenerIntegrationTest;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.events.ApplicationModuleListener;
import tools.jackson.databind.JsonNode;

/**
 * An event carries content only by reference when the event publication registry stores it, in every module, and
 * when it crosses a boundary of a mentoring module (ADR 0138). The registry keeps each event it delivers in
 * {@code event_publication}, where no erasure reaches words. A free-form component, such as a {@code String} or JSON,
 * needs {@link ContentFree}. Listeners are found through merged annotations, so a composed annotation does not hide
 * one. Every listener of a mentoring module also has a test on the idempotent listener harness for each event type
 * that it handles.
 *
 * <p>Main's source-control domain events still carry provider text from {@code integration.scm} to other modules in
 * memory. The registry does not store them, and no mentoring module takes part in them.
 */
class RegistryEventShapeArchTest extends HephaestusArchitectureTest {
    /** Values that hold no words: numbers, flags, ids and times. An enum and a record of such values qualify too. */
    private static final Set<Class<?>> VALUE_TYPES = Set.of(
            long.class,
            int.class,
            short.class,
            boolean.class,
            Long.class,
            Integer.class,
            Short.class,
            Boolean.class,
            BigDecimal.class,
            UUID.class,
            Instant.class,
            LocalDate.class,
            Duration.class);

    @Test
    void shouldCarryContentOnlyByReferenceInEveryStoredEventAndEveryEventAcrossAMentoringBoundary() {
        var modules = ModulithVerificationTest.applicationModules();
        Function<Class<?>, Optional<String>> moduleOf = type -> modules.getModuleByType(type.getName())
                .map(module -> module.getIdentifier().toString());
        var types = productionTypes();
        var violations = new TreeSet<String>();
        for (Handler handler : handlers(types)) violations.addAll(violations(handler, types, moduleOf));
        assertThat(violations)
                .as("Carry an id and read the content from its owner. Mark a free-form id or code with @ContentFree")
                .isEmpty();
    }

    @Test
    void shouldFindAListenerBehindAComposedAnnotationAndRefuseItsFreeFormComponents() {
        record Shared(@ContentFree String channelId, UUID entryId) {}
        record Leaky(
                long workspaceId,
                String excerpt,
                List<Shared> shared,
                @ContentFree List<String> keys,
                JsonNode raw) {}
        assertThat(freeFormComponents(Shared.class)).isEmpty();
        assertThat(freeFormComponents(Leaky.class))
                .containsExactly(Leaky.class.getName() + ".excerpt", Leaky.class.getName() + ".raw");
        Function<Class<?>, Optional<String>> noModule = type -> Optional.empty();
        var composed = handlers(List.of(ComposedListener.class));
        assertThat(composed).containsExactly(new Handler(ComposedListener.class, "on", Typed.class, true));
        assertThat(violations(composed.getFirst(), List.of(Typed.class), noModule))
                .containsExactly(Typed.class.getName() + ".words");
        // A stored payload that belongs to no module is checked too: the registry stores the String itself.
        var stored = handlers(List.of(StoredTextListener.class)).getFirst();
        assertThat(violations(stored, List.of(), noModule))
                .containsExactly("java.lang.String is not a record of values");
    }

    @Test
    void shouldTestEveryMentoringHandlerWithTheIdempotentHarness() {
        Set<String> tested = harnessTests(classesWithTests);
        var mentoring = handlers(productionTypes()).stream()
                .filter(handler -> MentoringModules.holds(handler.listener().getPackageName()))
                .toList();
        assertThat(handledTwice(mentoring))
                .as("A mentoring listener handles each event type once, so its harness test names the handler")
                .isEmpty();
        var untested = mentoring.stream()
                .map(handler ->
                        handler.listener().getName() + " <- " + handler.event().getName())
                .filter(handler -> !tested.contains(handler))
                .toList();
        assertThat(untested)
                .as(
                        "A mentoring handler needs a test that implements IdempotentListenerIntegrationTest<Listener, Event>")
                .isEmpty();
    }

    @Test
    void shouldCheckATransientMentoringPayloadThatAListenerReceivesThroughASharedInterface() {
        Function<Class<?>, Optional<String>> moduleOf = type ->
                Optional.of(type == MentoringPayload.class || type == MentoringNote.class ? "mentoring" : "core");
        var handler = handlers(List.of(SharedListener.class)).getFirst();
        assertThat(violations(handler, List.of(SharedEvent.class, MentoringPayload.class, Typed.class), moduleOf))
                .containsExactly(MentoringPayload.class.getName() + ".text");
        assertThat(violations(handler, List.of(SharedEvent.class, MentoringNote.class, AbstractNote.class), moduleOf))
                .as("a concrete class behind the interface is a payload too, and it is no record of values")
                .containsExactly(MentoringNote.class.getName() + " is not a record of values");
        Function<Class<?>, Optional<String>> allCore = type -> Optional.of("core");
        assertThat(violations(handler, List.of(SharedEvent.class, MentoringPayload.class), allCore))
                .as("a transient event inside one module is no boundary crossing")
                .isEmpty();
    }

    @Test
    void shouldReportAListenerThatHandlesOneEventTypeTwice() {
        assertThat(handledTwice(handlers(List.of(TwoHandlersOfOneEvent.class, ComposedListener.class))))
                .containsExactly(TwoHandlersOfOneEvent.class.getName() + " <- " + Typed.class.getName());
    }

    @Test
    void shouldCountOnlyAConcreteTestAsAHarnessTest() {
        assertThat(harnessTests(classesWithTests.that(DescribedPredicate.describe(
                        "harness fixtures",
                        type -> type.getName().startsWith(RegistryEventShapeArchTest.class.getName() + "$")))))
                .containsExactly(ComposedListener.class.getName() + " <- " + Typed.class.getName());
    }

    /** The listener and event pairs that a test on the idempotent listener harness covers, which JUnit runs. */
    private static Set<String> harnessTests(JavaClasses scope) {
        Set<String> tested = new TreeSet<>();
        for (var test : scope) {
            // JUnit runs the harness only in a concrete, visible and enabled test class.
            if (inProduction(test)
                    || test.isInterface()
                    || test.getModifiers().contains(JavaModifier.ABSTRACT)
                    || test.getModifiers().contains(JavaModifier.PRIVATE)
                    || test.isAnnotatedWith(Disabled.class)
                    || overridesHarnessTests(test.reflect())) continue;
            for (Type contract : test.reflect().getGenericInterfaces()) {
                if (contract instanceof ParameterizedType harness
                        && harness.getRawType() == IdempotentListenerIntegrationTest.class)
                    tested.add(harness.getActualTypeArguments()[0].getTypeName() + " <- "
                            + harness.getActualTypeArguments()[1].getTypeName());
            }
        }
        return tested;
    }

    /** Whether a test class overrides a test of the harness, which could disable or weaken it. */
    private static boolean overridesHarnessTests(Class<?> test) {
        var harnessTests = new TreeSet<String>();
        for (Method method : IdempotentListenerIntegrationTest.class.getDeclaredMethods())
            if (method.isAnnotationPresent(Test.class)) harnessTests.add(method.getName());
        for (Class<?> type = test; type != null && type != Object.class; type = type.getSuperclass())
            for (Method method : type.getDeclaredMethods()) if (harnessTests.contains(method.getName())) return true;
        return false;
    }

    /** The pairs of a listener class and an event type that more than one of its methods handles. */
    private static List<String> handledTwice(List<Handler> handlers) {
        var seen = new TreeSet<String>();
        var twice = new TreeSet<String>();
        for (Handler handler : handlers) {
            String pair =
                    handler.listener().getName() + " <- " + handler.event().getName();
            if (!seen.add(pair)) twice.add(pair);
        }
        return List.copyOf(twice);
    }

    private record Handler(Class<?> listener, String method, Class<?> event, boolean stored) {}

    private static List<Class<?>> productionTypes() {
        List<Class<?>> types = new ArrayList<>();
        for (var type : classes) types.add(type.reflect());
        return types;
    }

    /** Every event listener method and each event type it takes, by the merged {@link EventListener}. */
    private static List<Handler> handlers(List<Class<?>> types) {
        var handlers = new ArrayList<Handler>();
        for (Class<?> type : types) {
            for (Method method : type.getDeclaredMethods()) {
                EventListener listener = AnnotatedElementUtils.findMergedAnnotation(method, EventListener.class);
                if (listener == null) continue;
                boolean stored = AnnotatedElementUtils.hasAnnotation(method, ApplicationModuleListener.class);
                Class<?>[] events = listener.classes().length > 0 ? listener.classes() : method.getParameterTypes();
                for (Class<?> event : events) handlers.add(new Handler(type, method.getName(), event, stored));
            }
        }
        return handlers;
    }

    /**
     * The free-form components of each payload that a handler can receive and that must carry content only by
     * reference. A payload is the parameter type itself, or any concrete class of the application that a listener of an
     * interface or a superclass receives. The registry stores every payload of a stored handler, whatever its type. A
     * transient payload is checked when its module and the listener's differ and one of them is a mentoring module.
     */
    private static List<String> violations(
            Handler handler, List<Class<?>> types, Function<Class<?>, Optional<String>> moduleOf) {
        var violations = new ArrayList<String>();
        var payloads = new ArrayList<Class<?>>();
        payloads.add(handler.event());
        for (Class<?> type : types)
            if (type != handler.event()
                    && !type.isInterface()
                    && !Modifier.isAbstract(type.getModifiers())
                    && handler.event().isAssignableFrom(type)) payloads.add(type);
        for (Class<?> payload : payloads) {
            boolean checked = handler.stored()
                    && (payload == handler.event() || moduleOf.apply(payload).isPresent());
            var payloadModule = moduleOf.apply(payload);
            if (!checked && payloadModule.isPresent()) {
                String listener = moduleOf.apply(handler.listener()).orElse(payloadModule.get());
                checked = !payloadModule.get().equals(listener)
                        && MentoringModules.touches(payloadModule.get(), listener);
            }
            if (checked) violations.addAll(freeFormComponents(payload));
        }
        return violations;
    }

    private static List<String> freeFormComponents(Class<?> event) {
        var violations = new ArrayList<String>();
        if (!event.isRecord()) {
            violations.add(event.getName() + " is not a record of values");
            return violations;
        }
        for (RecordComponent component : event.getRecordComponents()) {
            if (component.isAnnotationPresent(ContentFree.class)) continue;
            if (!holdsValues(component.getGenericType(), violations))
                violations.add(event.getName() + "." + component.getName());
        }
        return violations;
    }

    /** Whether a type holds values only. A nested record adds its own free-form components to the violations. */
    private static boolean holdsValues(Type type, List<String> violations) {
        if (type instanceof ParameterizedType generic && generic.getRawType() instanceof Class<?> raw) {
            if (Collection.class.isAssignableFrom(raw) || raw == Optional.class)
                return holdsValues(generic.getActualTypeArguments()[0], violations);
            if (Map.class.isAssignableFrom(raw))
                return holdsValues(generic.getActualTypeArguments()[0], violations)
                        && holdsValues(generic.getActualTypeArguments()[1], violations);
            return false;
        }
        if (!(type instanceof Class<?> raw)) return false;
        if (VALUE_TYPES.contains(raw) || raw.isEnum()) return true;
        if (raw.isRecord()) {
            violations.addAll(freeFormComponents(raw));
            return true;
        }
        return false;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @ApplicationModuleListener
    private @interface ComposedHandler {}

    private record Typed(long workspaceId, String words) {}

    private interface SharedEvent {}

    private record MentoringPayload(long threadId, String text) implements SharedEvent {}

    @SuppressWarnings("unused") // Read by reflection only.
    private abstract static class AbstractNote implements SharedEvent {}

    @SuppressWarnings("unused") // Read by reflection only.
    private static final class MentoringNote extends AbstractNote {
        private final String text = "";
    }

    @SuppressWarnings("unused") // Read by reflection only.
    private static final class SharedListener {
        @EventListener
        void on(SharedEvent event) {}
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private abstract static class AbstractHarness
            implements IdempotentListenerIntegrationTest<TwoHandlersOfOneEvent, Typed> {}

    @Disabled("A fixture: a disabled test enrols no listener")
    static final class DisabledHarness implements IdempotentListenerIntegrationTest<StoredTextListener, String> {
        @Override
        public Events<String> newEvents() {
            return new Events<>("a", "b");
        }

        @Override
        public void deliver(String event) {}

        @Override
        public Object stateAfter(Events<String> events) {
            return 0;
        }
    }

    /** A fixture that disables one harness test by overriding it, so it enrols no listener. */
    static final class OverridingHarness implements IdempotentListenerIntegrationTest<TwoHandlersOfOneEvent, Typed> {
        @Override
        public Events<Typed> newEvents() {
            return new Events<>(new Typed(1L, ""), new Typed(2L, ""));
        }

        @Override
        public void deliver(Typed event) {}

        @Override
        public Object stateAfter(Events<Typed> events) {
            return 0;
        }

        @Test
        @Disabled("A fixture: an overridden harness test enrols no listener")
        @Override
        public void shouldReachTheSameStateWhenTwoEventsArriveInSwappedOrder() {}
    }

    /** A fixture that JUnit discovers and runs: a listener that keeps no state passes the harness. */
    static final class ConcreteHarness implements IdempotentListenerIntegrationTest<ComposedListener, Typed> {
        @Override
        public Events<Typed> newEvents() {
            return new Events<>(new Typed(1L, ""), new Typed(2L, ""));
        }

        @Override
        public void deliver(Typed event) {}

        @Override
        public Object stateAfter(Events<Typed> events) {
            return 0;
        }
    }

    @SuppressWarnings("unused") // Read by reflection only.
    private static final class TwoHandlersOfOneEvent {
        @EventListener
        void first(Typed typed) {}

        @EventListener
        void second(Typed typed) {}
    }

    @SuppressWarnings("unused") // Read by reflection only.
    private static final class StoredTextListener {
        @ApplicationModuleListener
        void on(String text) {}
    }

    @SuppressWarnings("unused") // Read by reflection only.
    private static final class ComposedListener {
        @ComposedHandler
        void on(Typed typed) {}
    }
}
