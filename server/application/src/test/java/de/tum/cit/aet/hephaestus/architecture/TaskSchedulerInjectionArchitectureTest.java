package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The type behind the task scheduler bean is not fixed: the server role builds a pool
 * ({@code ServerSchedulingConfig}), and Boot's default with virtual threads is a
 * {@code SimpleAsyncTaskScheduler}. Injecting the concrete {@link ThreadPoolTaskScheduler} ties a
 * class to one of them. Use {@link org.springframework.scheduling.TaskScheduler} instead.
 *
 * <p>The rule names injection points, not every use: the configuration that builds the pool configures the pool it
 * built, such as its error handler, which only the concrete scheduler offers.
 */
class TaskSchedulerInjectionArchitectureTest extends HephaestusArchitectureTest {

    private static final ArchRule NO_FIELD = noFields()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage(BASE_PACKAGE + "..")
            .should()
            .haveRawType(ThreadPoolTaskScheduler.class);

    private static final ArchRule NO_PARAMETER = noCodeUnits()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage(BASE_PACKAGE + "..")
            .should(takeAThreadPoolTaskScheduler());

    @Test
    void productionCodeMustNotInjectTheConcreteThreadPoolTaskScheduler() {
        NO_FIELD.check(classes);
        NO_PARAMETER.check(classes);
    }

    @Test
    void shouldRefuseAFieldAndAConstructorParameterOfTheConcreteScheduler() {
        JavaClasses injecting = new ClassFileImporter().importClasses(InjectsTheConcreteScheduler.class);

        assertThatThrownBy(() -> NO_FIELD.check(injecting)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> NO_PARAMETER.check(injecting)).isInstanceOf(AssertionError.class);
    }

    static final class InjectsTheConcreteScheduler {
        private final ThreadPoolTaskScheduler scheduler;

        InjectsTheConcreteScheduler(ThreadPoolTaskScheduler scheduler) {
            this.scheduler = scheduler;
        }

        ThreadPoolTaskScheduler scheduler() {
            return scheduler;
        }
    }

    private static ArchCondition<JavaCodeUnit> takeAThreadPoolTaskScheduler() {
        return new ArchCondition<>("take a ThreadPoolTaskScheduler parameter") {
            @Override
            public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                boolean takes = codeUnit.getRawParameterTypes().stream()
                        .anyMatch(type -> type.isAssignableTo(ThreadPoolTaskScheduler.class));
                events.add(new SimpleConditionEvent(
                        codeUnit, takes, codeUnit.getFullName() + " takes a ThreadPoolTaskScheduler"));
            }
        };
    }
}
