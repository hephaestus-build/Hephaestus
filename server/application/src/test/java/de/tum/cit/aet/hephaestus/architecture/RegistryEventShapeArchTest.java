package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import de.tum.cit.aet.hephaestus.core.event.Identifier;
import de.tum.cit.aet.hephaestus.testconfig.IdempotentListenerIntegrationTest;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * An event that the publication registry stores carries ids only (ADR 0138): the registry writes each one to
 * {@code event_publication}, where no erasure reaches words. A {@code String} component is an id only when it carries
 * {@link Identifier}. Every listener of a mentoring module also has a test on the idempotent listener harness.
 */
class RegistryEventShapeArchTest extends HephaestusArchitectureTest {
    private static final Set<Class<?>> ID_TYPES = Set.of(
            long.class,
            int.class,
            short.class,
            boolean.class,
            Long.class,
            Integer.class,
            Short.class,
            Boolean.class,
            UUID.class,
            Instant.class,
            LocalDate.class);

    /**
     * Components of events that main stored before this rule. The list is closed: a new event carries ids only. A
     * removed component leaves the list in the same change.
     */
    private static final Map<String, String> KNOWN_TEXT = Map.of(
            "de.tum.cit.aet.hephaestus.activity.ActivityFact.sourceKey", "a provider event key, not marked",
            "de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent.noteBody",
                    "the words of a provider comment that asks for a review",
            "de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent.noteAuthor",
                    "the provider login of the comment author",
            "de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent.replyAddress",
                    "the adapter's address of the thread to answer in",
            "de.tum.cit.aet.hephaestus.conversation.ConversationLedgerEvents$AttemptTakenBack.admission",
                    "the admission of a taken-back attempt, as JSON",
            "de.tum.cit.aet.hephaestus.notification.ProductFeedbackUpdateEmailRequested.issueUrl",
                    "the URL of the product feedback issue",
            "de.tum.cit.aet.hephaestus.notification.ProductFeedbackUpdateEmailRequested.detail",
                    "the update text of a product feedback email",
            "de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationReceiptsDue.unconfirmedReason",
                    "the reason that a receipt records",
            "de.tum.cit.aet.hephaestus.agent.conversation.turn.ConversationReceiptsDue.failedReason",
                    "the reason that a receipt records");

    @Test
    void shouldCarryOnlyIdsInEveryRegistryEvent() {
        // A nested record that two events share reports its components once.
        var violations = new TreeSet<String>();
        for (JavaClass event : registryEvents()) violations.addAll(textComponents(event.reflect()));
        assertThat(violations)
                .as("Carry an id and read the content from its owner. Mark a String id with @Identifier")
                .containsExactlyInAnyOrderElementsOf(KNOWN_TEXT.keySet());
    }

    @Test
    void shouldRefuseAnEventWithAnUnannotatedStringComponent() {
        record Shared(@Identifier String channelId, UUID entryId) {}
        record Leaky(long workspaceId, String excerpt, List<Shared> shared) {}
        assertThat(textComponents(Shared.class)).isEmpty();
        assertThat(textComponents(Leaky.class)).containsExactly(Leaky.class.getName() + ".excerpt");
    }

    @Test
    void shouldTestEveryMentoringListenerWithTheIdempotentHarness() {
        Set<String> tested = new TreeSet<>();
        for (JavaClass test : classesWithTests) {
            if (inProduction(test) || test.isInterface()) continue;
            for (Type contract : test.reflect().getGenericInterfaces()) {
                if (contract instanceof ParameterizedType harness
                        && harness.getRawType() == IdempotentListenerIntegrationTest.class)
                    tested.add(harness.getActualTypeArguments()[0].getTypeName());
            }
        }
        var untested = classes.stream()
                .filter(type -> MentoringModules.holds(type.getPackageName()))
                .filter(type -> type.getMethods().stream().anyMatch(RegistryEventShapeArchTest::isListener))
                .map(JavaClass::getName)
                .filter(listener -> !tested.contains(listener))
                .toList();
        assertThat(untested)
                .as("A listener of a mentoring module needs a test that implements IdempotentListenerIntegrationTest")
                .isEmpty();
    }

    /** The parameter types of the listeners that the registry records, which are the ones it stores. */
    private static Set<JavaClass> registryEvents() {
        var events = new TreeMap<String, JavaClass>();
        for (JavaClass type : classes) {
            for (JavaMethod method : type.getMethods()) {
                if (method.isAnnotatedWith(ApplicationModuleListener.class)
                        && method.getRawParameterTypes().size() == 1) {
                    JavaClass event = method.getRawParameterTypes().getFirst();
                    events.put(event.getName(), event);
                }
            }
        }
        return Set.copyOf(events.values());
    }

    private static boolean isListener(JavaMethod method) {
        return method.isAnnotatedWith(ApplicationModuleListener.class)
                || method.isAnnotatedWith(TransactionalEventListener.class)
                || method.isAnnotatedWith(EventListener.class);
    }

    private static List<String> textComponents(Class<?> event) {
        var violations = new ArrayList<String>();
        if (!event.isRecord()) {
            violations.add(event.getName() + " is not a record");
            return violations;
        }
        for (RecordComponent component : event.getRecordComponents()) {
            if (component.getType() == String.class && component.isAnnotationPresent(Identifier.class)) continue;
            if (!isId(component.getGenericType(), violations))
                violations.add(event.getName() + "." + component.getName());
        }
        return violations;
    }

    /** Whether a component type holds ids only. A nested record adds its own text components to the violations. */
    private static boolean isId(Type type, List<String> violations) {
        if (type instanceof ParameterizedType generic
                && generic.getRawType() instanceof Class<?> raw
                && Collection.class.isAssignableFrom(raw)) return isId(generic.getActualTypeArguments()[0], violations);
        if (!(type instanceof Class<?> raw)) return false;
        if (ID_TYPES.contains(raw) || raw.isEnum()) return true;
        if (raw.isRecord()) {
            violations.addAll(textComponents(raw));
            return true;
        }
        return false;
    }
}
