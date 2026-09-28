package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.*;

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
 * <p>Note: Code health analysis is in the separate <b>practices</b> module:
 * <ul>
 *   <li><b>practices.model</b> - Practice and Observation entities</li>
 *   <li><b>practices.spi</b> - Service provider interfaces (UserRoleChecker)</li>
 *   <li><b>practices.observation</b> - Contributor findings, detection events, and finding API</li>
 *   <li><b>practices.review</b> - Detection and delivery gate decisions</li>
 * </ul>
 *
 * <p>These tests enforce proper separation of concerns within the activity module and practices module.
 *
 * @see ArchitectureTestConstants
 */
class ActivityModuleBoundaryTest extends HephaestusArchitectureTest {

    // ACTIVITY MODULE ISOLATION

    @Nested
    class ActivityModuleIsolationTests {

        @Test
        void activityDoesNotDependOnMentor() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..activity..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..mentor..")
                    .because("Activity and mentor are independent feature modules");
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
                    .because("Activity counts what people did; it never reads practice feedback or observations");
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

    // PRACTICES MODULE ISOLATION

    @Nested
    class PracticesModuleTests {

        @Test
        void practicesModelDoesNotDependOnReview() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..practices.model..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..practices.review..")
                    .because("Model layer (practices.model) should not depend on review logic (practices.review)");
            rule.check(classes);
        }
    }

    // PRACTICES MODULE CONTROLLER ISOLATION

    @Nested
    class PracticesControllerTests {

        @Test
        void practicesHasDedicatedController() {
            ArchRule rule = classes()
                    .that()
                    .resideInAPackage("..practices..")
                    .and()
                    .haveSimpleNameEndingWith("Controller")
                    .should()
                    .haveSimpleName("PracticeCatalogController")
                    .orShould()
                    .haveSimpleName("PracticeReleaseController")
                    .orShould()
                    .haveSimpleName("PracticeGroupController")
                    .orShould()
                    .haveSimpleName("ObservationController")
                    .orShould()
                    .haveSimpleName("FeedbackResponseController")
                    .orShould()
                    .haveSimpleName("PracticeReviewSettingsController")
                    .orShould()
                    .haveSimpleName("PracticeReviewOutputController")
                    .orShould()
                    .haveSimpleName("CuratedCatalogAdminController")
                    .orShould()
                    .haveSimpleName("CuratedPracticeCatalogController")
                    .orShould()
                    .haveSimpleName("CatalogAdoptionController")
                    .orShould()
                    .haveSimpleName("ArtifactTraceController")
                    .orShould()
                    .haveSimpleName("InAppFeedbackController")
                    .orShould()
                    .haveSimpleName("PracticeGroupDetailController")
                    .orShould()
                    .haveSimpleName("PracticeGroupStandingController")
                    .orShould()
                    .haveSimpleName("PracticeStandingController")
                    .orShould()
                    .haveSimpleName("PracticeProfileOverviewController")
                    .because(
                            "Only PracticeCatalogController, PracticeReleaseController, PracticeGroupController, ObservationController, "
                                    + "FeedbackResponseController, PracticeReviewSettingsController, PracticeReviewOutputController, "
                                    + "CuratedCatalogAdminController, CuratedPracticeCatalogController, CatalogAdoptionController, "
                                    + "ArtifactTraceController, InAppFeedbackController, PracticeGroupDetailController, "
                                    + "PracticeGroupStandingController, PracticeStandingController and "
                                    + "PracticeProfileOverviewController are allowed REST entry points");
            rule.check(classes);
        }
    }
}
