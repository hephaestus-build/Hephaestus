package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * Tests record application events only through the shared
 * {@link de.tum.cit.aet.hephaestus.testconfig.SharedEventRecorder}, which {@code BaseIntegrationTest} imports.
 * {@code @RecordApplicationEvents} loses an event that a pooled thread publishes, and a test's source cannot show which
 * threads are pooled (testing.mdx § Flaky tests). A listener bean of a test's own forks a Spring context, with its
 * Testcontainers boot, for each class that imports it. The rules read the compiled test classes outside this package,
 * whose samples break the rules on purpose.
 */
class SharedEventRecorderConventionTest extends HephaestusArchitectureTest {

    private static final JavaClasses TEST_CLASSES = classesWithTests.that(DescribedPredicate.describe(
            "test classes",
            type -> !inProduction(type)
                    && !type.getPackageName().equals(SharedEventRecorderConventionTest.class.getPackageName())));

    /**
     * The listener beans that the test tree keeps, each for a reason that the shared recorder cannot serve. Each entry
     * must still declare a listener, so a removed listener also leaves this list.
     */
    private static final Map<String, String> KEPT_LISTENERS = Map.of(
            "de.tum.cit.aet.hephaestus.testconfig.SharedEventRecorder",
            "the shared recorder itself",
            "de.tum.cit.aet.hephaestus.core.evaluation.EvaluationIntegrationTest$EvaluationRunListener",
            "holds durable module work before it queues a run, so settle must still see its incomplete publication",
            "de.tum.cit.aet.hephaestus.notification.RegistryProbeConfiguration$ProbeListener",
            "throws on demand, so the publication registry records a failed delivery",
            "de.tum.cit.aet.hephaestus.core.privacy.PersonDataRecoveryStartupIntegrationTest$Ordering",
            "observes the recovery's state at the moment each startup event fires",
            "de.tum.cit.aet.hephaestus.testconfig.ClosedTestContextReleaser",
            "a spring.factories listener that releases closed test contexts, not a context bean",
            "de.tum.cit.aet.hephaestus.observability.PersonalDataLogScan$Reattach",
            "a spring.factories listener that reattaches the log scan, not a context bean");

    @Test
    void testsRecordEventsOnlyThroughTheSharedRecorder() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .belongToAnyOf(RecordApplicationEvents.class, ApplicationEvents.class)
                .because("@RecordApplicationEvents loses an event that a pooled thread publishes. Inject the shared "
                        + "SharedEventRecorder (imported by BaseIntegrationTest) and read "
                        + "recorder.of(SomeEvent.class, SomeEvent::id, ownedId) instead")
                .check(TEST_CLASSES);
    }

    @Test
    void testsDeclareNoListenerBeansOfTheirOwn() {
        assertThat(listenerOwners())
                .as("Test listener beans fork a Spring context for each class that imports them. Read the shared "
                        + "SharedEventRecorder (imported by BaseIntegrationTest) instead. A listener that must throw "
                        + "or block is added to the ApplicationEventMulticaster during the test and removed in a "
                        + "finally block. Keep a listener bean only with a reason in KEPT_LISTENERS")
                .containsExactlyInAnyOrderElementsOf(KEPT_LISTENERS.keySet());
    }

    /**
     * The test classes that declare an event listener method, implement {@link ApplicationListener}, or define a bean
     * of that interface type. A bean of a framework type that listens for its own lifecycle, such as a task scheduler,
     * is not the test's listener.
     */
    private static Set<String> listenerOwners() {
        Set<String> owners = new TreeSet<>();
        for (JavaClass type : TEST_CLASSES) {
            if (!type.isInterface() && type.isAssignableTo(ApplicationListener.class)) {
                owners.add(type.getName());
            }
            type.getMethods().stream()
                    .filter(method -> method.isMetaAnnotatedWith(EventListener.class)
                            || (method.isAnnotatedWith(Bean.class)
                                    && method.getRawReturnType().isEquivalentTo(ApplicationListener.class)))
                    .forEach(method -> owners.add(type.getName()));
        }
        return owners;
    }
}
