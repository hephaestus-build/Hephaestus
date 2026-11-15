package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Activity Module Boundary Tests.
 *
 * <p>The activity module has a focused internal structure:
 * <ul>
 *   <li><b>activity root</b> - The activity event ledger and its write SPI</li>
 *   <li><b>activity.overview</b> - The read model behind the Activity pages</li>
 * </ul>
 *
 * @see ArchitectureTestConstants
 */
class ActivityModuleBoundaryTest extends HephaestusArchitectureTest {

    @Nested
    class ActivityModuleIsolationTests {

        @Test
        void activityDoesNotDependOnConversation() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..activity..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..conversation..")
                    .because("Activity and conversation are independent feature modules");
            rule.check(classes);
        }

        @Test
        void activityDoesNotDependOnNotification() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..activity..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..notification..")
                    .because("Activity should use domain events to trigger notifications");
            rule.check(classes);
        }

        @Test
        void activityOverviewDoesNotDependOnPractices() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..activity.overview..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..practices..")
                    .because("Activity counts what people did; it never reads practice feedback or results");
            rule.check(classes);
        }

        @Test
        void activityDoesNotDependOnContributors() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..activity..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..contributors..")
                    .because("Activity should not depend on contributors");
            rule.check(classes);
        }
    }
}
