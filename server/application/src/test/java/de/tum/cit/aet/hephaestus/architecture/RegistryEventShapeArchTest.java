package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.event.ContentFree;
import de.tum.cit.aet.hephaestus.testconfig.IdempotentListenerIntegrationTest;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
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
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.core.ApplicationModules;
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
        var violations = new TreeSet<String>();
        for (Handler handler : handlers(productionTypes())) {
            if (storedOrCrossesAMentoringBoundary(handler, modules))
                violations.addAll(freeFormComponents(handler.event()));
        }
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
        assertThat(handlers(List.of(ComposedListener.class)))
                .containsExactly(new Handler(ComposedListener.class, "on", Typed.class, true));
        assertThat(freeFormComponents(Typed.class)).containsExactly(Typed.class.getName() + ".words");
    }

    @Test
    void shouldTestEveryMentoringHandlerWithTheIdempotentHarness() {
        Set<String> tested = new TreeSet<>();
        for (var test : classesWithTests) {
            if (inProduction(test) || test.isInterface()) continue;
            for (Type contract : test.reflect().getGenericInterfaces()) {
                if (contract instanceof ParameterizedType harness
                        && harness.getRawType() == IdempotentListenerIntegrationTest.class)
                    tested.add(harness.getActualTypeArguments()[0].getTypeName() + " <- "
                            + harness.getActualTypeArguments()[1].getTypeName());
            }
        }
        var untested = handlers(productionTypes()).stream()
                .filter(handler -> MentoringModules.holds(handler.listener().getPackageName()))
                .map(handler ->
                        handler.listener().getName() + " <- " + handler.event().getName())
                .filter(handler -> !tested.contains(handler))
                .toList();
        assertThat(untested)
                .as(
                        "A mentoring handler needs a test that implements IdempotentListenerIntegrationTest<Listener, Event>")
                .isEmpty();
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

    private static boolean storedOrCrossesAMentoringBoundary(Handler handler, ApplicationModules modules) {
        var eventModule = modules.getModuleByType(handler.event().getName());
        if (eventModule.isEmpty()) return false; // A framework event, such as ApplicationReadyEvent.
        if (handler.stored()) return true;
        String event = eventModule.get().getIdentifier().toString();
        String listener = modules.getModuleByType(handler.listener().getName())
                .map(module -> module.getIdentifier().toString())
                .orElse(event);
        return !event.equals(listener) && MentoringModules.touches(event, listener);
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

    @SuppressWarnings("unused") // Read by reflection only.
    private static final class ComposedListener {
        @ComposedHandler
        void on(Typed typed) {}
    }
}
