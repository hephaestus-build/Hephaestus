package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import de.tum.cit.aet.hephaestus.core.Lifecycle;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;

class LifecycleSetterArchTest extends HephaestusArchitectureTest {
    @Test
    void entitiesChangeLifecyclesThroughNamedTransitions() {
        methods()
                .that()
                .arePublic()
                .and()
                .haveNameMatching("set.*")
                .and()
                .areDeclaredInClassesThat()
                .areAnnotatedWith(Entity.class)
                .should(new ArchCondition<JavaMethod>("not set a lifecycle field") {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        if (method.getRawParameterTypes().stream()
                                .anyMatch(type -> type.isAssignableTo(Lifecycle.class)))
                            events.add(SimpleConditionEvent.violated(
                                    method, method.getFullName() + " must use a named aggregate transition"));
                    }
                })
                .check(classes);
    }
}
