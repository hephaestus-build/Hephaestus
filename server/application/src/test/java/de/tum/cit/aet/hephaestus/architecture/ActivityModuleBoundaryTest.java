package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.*;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Activity Module Boundary Tests.
 *
 * <p>The activity module has a focused internal structure:
 * <ul>
 *   <li><b>activity root</b> - Core activity event handling and leaderboard cache</li>
 *   <li><b>activity.scoring</b> - XP/scoring calculations</li>
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
        void activityDoesNotDependOnLeaderboardServices() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..activity..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..leaderboard..service..")
                    .orShould()
                    .dependOnClassesThat()
                    .resideInAPackage("..leaderboard..repository..")
                    .because(
                            "Activity should not depend on leaderboard - use domain events for cross-module communication");
            rule.check(classes);
        }

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
        void activityDoesNotDependOnProfile() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..activity..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..profile..")
                    .because("Profile depends on activity, not vice versa");
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

    // ACTIVITY SCORING ISOLATION

    @Nested
    @DisplayName("Activity Scoring Isolation")
    class ScoringSubmoduleTests {

        @Test
        void scoringDoesNotDependOnControllers() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..activity.scoring..")
                    .should()
                    .dependOnClassesThat()
                    .haveSimpleNameEndingWith("Controller")
                    .because("Scoring logic should be independent of presentation layer");
            rule.check(classes);
        }

        @Test
        void scoringHasMinimalExternalDependencies() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..activity.scoring..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..leaderboard..", "..mentor..", "..notification..", "..profile..")
                    .because("Scoring should be a pure calculation module");
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
                    .haveSimpleName("UserPracticeViewController")
                    .because(
                            "Only PracticeCatalogController, PracticeGroupController, ObservationController, "
                                    + "FeedbackResponseController, PracticeReviewSettingsController, PracticeReviewOutputController, "
                                    + "CuratedCatalogAdminController, CuratedPracticeCatalogController, CatalogAdoptionController, "
                                    + "ArtifactTraceController, InAppFeedbackController, PracticeGroupDetailController, "
                                    + "PracticeGroupStandingController, PracticeStandingController and UserPracticeViewController are allowed REST entry points");
            rule.check(classes);
        }
    }
}
