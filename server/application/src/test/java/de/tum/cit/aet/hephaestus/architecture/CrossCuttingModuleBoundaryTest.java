package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.*;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Cross-Cutting Module Boundary Tests.
 *
 * <p>Tests for modules that don't have explicit isolation tests yet:
 * <ul>
 *   <li><b>contributors</b> - Contributor listing and analytics</li>
 *   <li><b>account</b> - User account management</li>
 *   <li><b>config</b> - Application configuration</li>
 *   <li><b>core</b> - Shared core utilities</li>
 *   <li><b>shared</b> - Cross-cutting shared code</li>
 * </ul>
 *
 * @see ArchitectureTestConstants
 */
class CrossCuttingModuleBoundaryTest extends HephaestusArchitectureTest {

    // CONTRIBUTORS MODULE ISOLATION

    @Nested
    class ContributorsModuleTests {

        /**
         * Contributors module should not depend on activity internals.
         */
        @Test
        void contributorsDoesNotDependOnActivityInternals() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..contributors..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..activity..service..", "..activity..repository..", "..practices..")
                    .because("Contributors module should not depend on activity internals");
            rule.check(classes);
        }

        /**
         * Contributors module should not depend on mentor module.
         */
        @Test
        void contributorsDoesNotDependOnMentor() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..contributors..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..mentor..")
                    .because("Contributors and mentor are independent modules");
            rule.check(classes);
        }
    }

    // ACCOUNT MODULE ISOLATION

    @Nested
    class AccountModuleTests {

        /**
         * Account module should not depend on feature modules.
         *
         * <p>Account is a foundational module for user management.
         * Feature modules depend on account, not vice versa.
         */
        @Test
        void accountDoesNotDependOnFeatureModules() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..account..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..activity..", "..mentor..", "..notification..", "..contributors..")
                    .because("Account is a foundational module - feature modules depend on it");
            rule.check(classes);
        }

        /**
         * Account module should not depend on integration.scm sync logic.
         *
         * <p>Account handles user sessions and preferences, not data sync.
         */
        @Test
        void accountDoesNotDependOnGitproviderSync() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..account..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..integration.scm.sync..")
                    .because("Account handles user management, not data sync");
            rule.check(classes);
        }
    }

    // CORE & SHARED PACKAGE PROTECTION

    @Nested
    class CoreSharedPackageTests {

        /**
         * Core package should not depend on feature modules.
         *
         * <p>Core contains shared utilities and base classes that feature
         * modules depend on. It should be dependency-free regarding features.
         */
        @Test
        void coreDoesNotDependOnFeatureModules() {
            // NOTE: `..core..` would also match `integration.core.*` after the integration restructure,
            // which is undesired — we want the framework-foundation `core` only. Anchor to the FQN root.
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("de.tum.cit.aet.hephaestus.core..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..activity..", "..mentor..", "..notification..", "..contributors..", "..workspace..")
                    .because("Core is a foundation layer - should not depend on feature modules");
            rule.check(classes);
        }

        /**
         * Core package should not depend on integration.scm.
         *
         * <p>Core utilities should be git-provider agnostic.
         */
        @Test
        void coreDoesNotDependOnGitprovider() {
            // Anchor to the FQN root to exclude `integration.core.*` (which is the integration core
            // and is allowed to know the scm aggregate during the migration).
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("de.tum.cit.aet.hephaestus.core..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..integration.scm..")
                    .because("Core should be infrastructure-agnostic");
            rule.check(classes);
        }

        /**
         * Shared package should have minimal dependencies.
         *
         * <p>Shared code should only depend on core and external libraries.
         */
        @Test
        void sharedHasMinimalDependencies() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..shared..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..activity..", "..mentor..", "..notification..", "..contributors..", "..integration.scm..")
                    .because("Shared code should not depend on feature modules");
            rule.check(classes);
        }
    }

    // ANALYTICS MODULE ISOLATION

    // CONFIG MODULE ISOLATION

    @Nested
    class ConfigModuleTests {

        /**
         * Config package should not depend on feature module internals.
         *
         * <p>Configuration wires up beans but should not contain feature logic.
         */
        @Test
        void configDoesNotDependOnFeatureServiceImplementations() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..config..")
                    .and()
                    .haveSimpleNameEndingWith("Config")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..activity..repository..", "..mentor..repository..")
                    .because("Config should wire up services, not access repositories directly");
            rule.check(classes);
        }
    }

    // NOTE: Feature module cycle tests removed - they used allowEmptyShould(true)
    // which means they pass even when there's nothing to check.
    // The top-level cycle test in ArchitectureTest.noCyclesBetweenModules() is sufficient.

    // WORKSPACE ADAPTER PATTERN VERIFICATION

    @Nested
    class WorkspaceCrossCuttingTests {

        /**
         * Workspace adapters should only be accessed by workspace package.
         *
         * <p>Adapters are internal implementation details of the workspace module.
         * Other modules should use workspace's public API.
         */
        @Test
        void workspaceAdaptersAreInternal() {
            ArchRule rule = noClasses()
                    .that()
                    .resideOutsideOfPackage("..workspace..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..workspace.adapter..")
                    .because("Workspace adapters are internal - use workspace public API");
            rule.check(classes);
        }

        /**
         * Workspace validation should not depend on external services.
         *
         * <p>Validation logic should be pure - it validates input without
         * making external service calls.
         */
        @Test
        void workspaceValidationHasMinimalDependencies() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..workspace.validation..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..activity..", "..mentor..", "..integration.scm.sync..")
                    .because("Validation should be pure logic without external service dependencies");
            rule.check(classes);
        }

        /**
         * Workspace context should be accessed via proper patterns.
         *
         * <p>The workspace context provides tenant isolation. Direct access
         * to context internals should be limited.
         */
        @Test
        void workspaceContextInternalsAreProtected() {
            ArchRule rule = classes()
                    .that()
                    .resideInAPackage("..workspace.context..")
                    .and()
                    .areNotMemberClasses()
                    .should()
                    .bePublic()
                    .orShould()
                    .bePackagePrivate()
                    .because("Workspace context classes should have proper visibility (inner classes excluded)");
            rule.check(classes);
        }
    }
}
