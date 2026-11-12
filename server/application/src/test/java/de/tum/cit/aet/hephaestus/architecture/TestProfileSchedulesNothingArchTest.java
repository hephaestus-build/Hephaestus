package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * The test profile schedules nothing. Background work that writes while a test truncates its tables can deadlock the
 * truncation, and tests run maintenance by calling it. Two kinds of registration schedule work in a context: a bean
 * that holds scheduled tasks, such as a {@code ScheduledTaskRegistrar}, and {@code @EnableScheduling}, which runs every
 * {@code @Scheduled} method. Each must carry a {@code @Profile} that excludes {@code test}, on the bean method or on
 * the class that declares it. An enclosing class's profile does not count: Spring scans a nested configuration on its
 * own.
 */
class TestProfileSchedulesNothingArchTest extends HephaestusArchitectureTest {

    private static final ArchRule SCHEDULED_TASK_BEANS = methods()
            .that()
            .areAnnotatedWith(Bean.class)
            .and()
            .haveRawReturnType(assignableTo(ScheduledTaskHolder.class))
            .should(new ArchCondition<JavaMethod>("be excluded from the test profile") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    boolean excluded = excludesTest(method) || excludesTest(method.getOwner());
                    events.add(new SimpleConditionEvent(
                            method,
                            excluded,
                            method.getFullName()
                                    + " schedules tasks in the test profile; add @Profile(\"!test & …\")"));
                }
            });

    private static final ArchRule SCHEDULING_SWITCHES = classes()
            .that()
            .areAnnotatedWith(EnableScheduling.class)
            .should(new ArchCondition<JavaClass>("be excluded from the test profile") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    events.add(new SimpleConditionEvent(
                            type,
                            excludesTest(type),
                            type.getName()
                                    + " runs @Scheduled methods in the test profile; add @Profile(\"!test & …\")"));
                }
            });

    /**
     * Whether every expression of the element's {@code @Profile} requires {@code !test}. An expression with {@code |}
     * counts as not excluding it, since one alternative may hold in the test profile.
     */
    private static boolean excludesTest(HasAnnotations<?> element) {
        return element.tryGetAnnotationOfType(Profile.class)
                .map(profile -> profile.value().length > 0
                        && Arrays.stream(profile.value())
                                .allMatch(expression -> !expression.contains("|")
                                        && Arrays.stream(expression.split("&"))
                                                .map(term -> term.replace("(", "")
                                                        .replace(")", "")
                                                        .strip())
                                                .anyMatch("!test"::equals)))
                .orElse(false);
    }

    @Test
    void shouldScheduleNoTaskInTheTestProfile() {
        SCHEDULED_TASK_BEANS.check(classes);
    }

    @Test
    void shouldRunNoScheduledMethodInTheTestProfile() {
        SCHEDULING_SWITCHES.check(classes);
    }

    // The fixtures are no @Configuration: integration contexts scan the test tree, and would register them.

    @Test
    void shouldFindEveryRegistrationThatTheTestProfileCanRun() {
        var fixtures = new ClassFileImporter()
                .importClasses(
                        Unexcluded.class,
                        ExcludedByClass.class,
                        ExcludedByMethod.class,
                        EitherProfile.class,
                        Switch.class,
                        ExcludedSwitch.class);
        assertThat(SCHEDULED_TASK_BEANS.evaluate(fixtures).getFailureReport().getDetails())
                .hasSize(2)
                .anyMatch(detail -> detail.contains(Unexcluded.class.getName()))
                .anyMatch(detail -> detail.contains(EitherProfile.class.getName()));
        assertThat(SCHEDULING_SWITCHES.evaluate(fixtures).getFailureReport().getDetails())
                .singleElement()
                .asString()
                .contains(Switch.class.getName());
    }

    static class Unexcluded {
        @Bean
        ScheduledTaskRegistrar tasks() {
            return new ScheduledTaskRegistrar();
        }
    }

    @Profile("!test & !specs")
    static class ExcludedByClass {
        @Bean
        ScheduledTaskRegistrar tasks() {
            return new ScheduledTaskRegistrar();
        }
    }

    static class ExcludedByMethod {
        @Bean
        @Profile("!test")
        ScheduledTaskRegistrar tasks() {
            return new ScheduledTaskRegistrar();
        }
    }

    static class EitherProfile {
        @Bean
        @Profile("!test | prod")
        ScheduledTaskRegistrar tasks() {
            return new ScheduledTaskRegistrar();
        }
    }

    @EnableScheduling
    static class Switch {}

    @EnableScheduling
    @Profile("!test & !specs & !cds-training")
    static class ExcludedSwitch {}
}
