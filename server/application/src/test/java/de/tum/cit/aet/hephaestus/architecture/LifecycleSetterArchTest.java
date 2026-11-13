package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaTypeVariable;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.tum.cit.aet.hephaestus.core.Lifecycle;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import jakarta.persistence.Entity;
import java.util.List;
import org.junit.jupiter.api.Test;

class LifecycleSetterArchTest extends HephaestusArchitectureTest {
    @Test
    void entitiesChangeLifecyclesThroughNamedTransitions() {
        classes()
                .that()
                .areAnnotatedWith(Entity.class)
                .should(new ArchCondition<JavaClass>("not set a lifecycle field") {
                    @Override
                    public void check(JavaClass aggregate, ConditionEvents events) {
                        for (var method : lifecycleSetters(aggregate)) {
                            events.add(SimpleConditionEvent.violated(
                                    method, method.getFullName() + " must use a named aggregate transition"));
                        }
                    }
                })
                .check(classes);
    }

    private static List<JavaMethod> lifecycleSetters(JavaClass aggregate) {
        return aggregate.getAllMethods().stream()
                .filter(method -> method.getName().startsWith("set"))
                .filter(method -> method.getParameterTypes().stream().anyMatch(LifecycleSetterArchTest::isLifecycle))
                .toList();
    }

    @Test
    void shouldFindALifecycleSetterInheritedFromAGenericBase() {
        assertThat(lifecycleSetters(classesWithTests.get(InheritsLifecycleSetter.class)))
                .extracting(JavaMethod::getName)
                .containsExactly("setState");
    }

    private static boolean isLifecycle(JavaType type) {
        return type.toErasure().isAssignableTo(Lifecycle.class)
                || (type instanceof JavaTypeVariable<?> variable
                        && variable.getUpperBounds().stream().anyMatch(LifecycleSetterArchTest::isLifecycle));
    }

    @SuppressWarnings("UnusedMethod") // ArchUnit reads these fixture signatures without invoking them.
    private abstract static class GenericLifecycle<S extends Enum<S> & Lifecycle<S>> {
        abstract void setState(S state);

        abstract void transitionTo(S state);
    }

    private abstract static class InheritsLifecycleSetter extends GenericLifecycle<ReviewStatus> {}
}
