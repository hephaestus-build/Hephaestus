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
import de.tum.cit.aet.hephaestus.agent.catalog.LlmCallPermit;
import de.tum.cit.aet.hephaestus.core.Lifecycle;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.privacy.PersonDataRequest;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.practices.review.model.ReviewItem;
import de.tum.cit.aet.hephaestus.practices.review.schedule.ReviewBatch;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import jakarta.persistence.Entity;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class LifecycleSetterArchTest extends HephaestusArchitectureTest {
    private static final List<Debt> REMAINING = List.of(
            new Debt(ReviewItem.class, "state", "DEBT-STATE", "S-D9: ReviewItem needs guarded lifecycle transitions"),
            new Debt(
                    PersonDataRequest.class,
                    "state",
                    "DEBT-STATE",
                    "S-D10: PersonDataRequest needs guarded lifecycle transitions"),
            new Debt(ReviewBatch.class, "status", "RUN", "S-D12: ReviewBatch needs guarded lifecycle transitions"),
            new Debt(Account.class, "status", "DDD", "Account needs guarded lifecycle transitions"),
            new Debt(LlmCallPermit.class, "state", "RUN-6", "LlmCallPermit stores its lifecycle as a String"),
            new Debt(
                    Connection.class,
                    "state",
                    "INBOX",
                    "Guarded UNINSTALLED revival lives in the service; moving it into Connection transitions needs the lifecycle owner"));

    @Test
    void entitiesChangeLifecyclesThroughNamedTransitions() {
        classes()
                .that()
                .areAnnotatedWith(Entity.class)
                .should(new ArchCondition<JavaClass>("not set a lifecycle field") {
                    @Override
                    public void check(JavaClass aggregate, ConditionEvents events) {
                        for (var method : lifecycleSetters(aggregate)) {
                            if (REMAINING.stream().noneMatch(debt -> debt.covers(aggregate, method)))
                                events.add(SimpleConditionEvent.violated(
                                        method, method.getFullName() + " must use a named aggregate transition"));
                        }
                    }
                })
                .check(classes);
    }

    @Test
    void shouldKeepOnlyOutstandingLifecycleDebtWithOneOwnerPerEntry() {
        assertThat(REMAINING.stream()
                        .map(debt -> debt.aggregate().getName() + "." + debt.field())
                        .toList())
                .doesNotHaveDuplicates();
        for (var debt : REMAINING) {
            assertThat(debt.owner()).as("%s owner", debt).matches("[A-Z]+(?:-[A-Z0-9]+)*");
            assertThat(debt.reason()).as("%s reason", debt).isNotBlank();
            var aggregate = classes.get(debt.aggregate());
            assertThat(aggregate.isAnnotatedWith(Entity.class))
                    .as("%s is an aggregate", debt)
                    .isTrue();
            var field = aggregate.getField(debt.field());
            boolean outstanding = !field.getRawType().isAssignableTo(Lifecycle.class)
                    || lifecycleSetters(aggregate).stream().anyMatch(method -> debt.covers(aggregate, method));
            assertThat(outstanding)
                    .as("Remove completed lifecycle debt: %s", debt)
                    .isTrue();
        }
    }

    @Test
    @Tag("release-ratchet")
    void shouldHaveNoLifecycleDebtAtRelease() {
        ReleaseRatchet.assertEmptyForRelease("ADR 0099 lifecycle debt", REMAINING);
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

    private record Debt(Class<?> aggregate, String field, String owner, String reason) {
        boolean covers(JavaClass type, JavaMethod method) {
            if (!type.isEquivalentTo(aggregate) || method.getRawParameterTypes().size() != 1) return false;
            var setter = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            return method.getName().equals(setter)
                    && method.getRawParameterTypes()
                            .getFirst()
                            .equals(type.getField(field).getRawType());
        }

        @Override
        public String toString() {
            return aggregate.getSimpleName() + "." + field + " -> " + owner + ": " + reason;
        }
    }
}
