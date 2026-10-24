package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The type behind the task scheduler bean is not fixed: the server role builds a pool
 * ({@code ServerSchedulingConfig}), and Boot's default with virtual threads is a
 * {@code SimpleAsyncTaskScheduler}. Injecting the concrete {@link ThreadPoolTaskScheduler} ties a
 * class to one of them. Use {@link org.springframework.scheduling.TaskScheduler} instead.
 */
class TaskSchedulerInjectionArchitectureTest extends HephaestusArchitectureTest {

    @Test
    void productionCodeMustNotDependOnConcreteThreadPoolTaskScheduler() {
        noClasses()
                .that()
                .resideInAPackage(BASE_PACKAGE + "..")
                .should()
                .dependOnClassesThat()
                .areAssignableTo(ThreadPoolTaskScheduler.class)
                .check(classes);
    }
}
