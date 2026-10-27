package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static de.tum.cit.aet.hephaestus.architecture.conditions.HephaestusConditions.beFinalOrHaveOnlyPrivateConstructors;
import static de.tum.cit.aet.hephaestus.architecture.conditions.HephaestusConditions.beImmutable;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

/**
 * Module Boundary Tests - Architecture Enforcement.
 *
 * <h2>Architecture: Modular Monolith with Multi-Provider Support</h2>
 * <ul>
 *   <li><b>integration.scm</b> - Shared kernel for git provider data sync (GitHub and GitLab)</li>
 *   <li><b>workspace</b> - Cross-cutting context (multi-tenancy)</li>
 *   <li><b>Feature modules</b> - activity, mentor, practices depend on both</li>
 *   <li><b>Provider subpackages</b> - github/ and (future) gitlab/ isolate provider-specific logic</li>
 * </ul>
 *
 * <h2>Key Boundaries Enforced</h2>
 * <ol>
 *   <li><b>integration.scm isolation</b> - Cannot depend on feature modules (uses SPI for callbacks)</li>
 *   <li><b>SPI pattern</b> - integration.spi defines contracts; workspace.adapter implements</li>
 *   <li><b>Provider isolation</b> - GitHub-specific classes must be in github/ subpackages</li>
 *   <li><b>Domain events</b> - Cross-cutting concerns via events, not direct coupling</li>
 * </ol>
 *
 * @see ArchitectureTestConstants
 */
@DisplayName("Module Boundaries")
class ModuleBoundaryTest extends HephaestusArchitectureTest {

    @Nested
    class SpiPatternTests {

        /**
         * SPI classes in integration.spi should be interfaces.
         *
         * <p>SPIs define contracts that external modules implement to provide
         * workspace context, authentication, and sync targets to the core.
         */
        @Test
        void spiClassesAreInterfaces() {
            ArchRule rule = classes()
                    .that()
                    .resideInAPackage("..integration.core.spi..")
                    .and()
                    .areNotRecords()
                    .and()
                    .areNotEnums()
                    .and()
                    .areNotMemberClasses()
                    .and()
                    .haveSimpleNameNotEndingWith("Exception")
                    .should()
                    .beInterfaces()
                    .because("SPI classes define contracts and should be interfaces (exceptions excepted)");
            rule.check(classes);
        }

        /**
         * Adapters in workspace.adapter should implement SPI interfaces.
         *
         * <p>The workspace module bridges workspace-specific data (tokens, targets)
         * to the integration.scm sync engine via adapter implementations.
         */
        @Test
        void workspaceAdaptersImplementSpis() {
            ArchRule rule = classes()
                    .that()
                    .resideInAPackage("..workspace.adapter..")
                    .and()
                    .areNotInterfaces()
                    .and()
                    .areNotMemberClasses() // Exclude anonymous inner classes
                    .and()
                    .areNotAnonymousClasses() // Exclude anonymous classes
                    .and()
                    .haveSimpleNameNotContaining("$") // Exclude generated inner classes
                    .and()
                    .haveSimpleNameNotEndingWith("Test")
                    .should()
                    .implement(JavaClass.Predicates.resideInAPackage("..spi.."))
                    .because("Workspace adapters bridge workspace context to integration.scm via SPIs");
            rule.check(classes);
        }

        /**
         * SPI interfaces should be framework-agnostic.
         *
         * <p>SPIs define pure contracts without Spring dependencies to ensure
         * the core sync engine remains portable.
         */
        @Test
        @DisplayName("SPIs do not depend on Spring framework")
        void spiInterfacesAreFrameworkAgnostic() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..integration.core.spi..")
                    .and()
                    .areInterfaces()
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework.stereotype..",
                            "org.springframework.beans..",
                            "org.springframework.context..")
                    .because("SPIs should be framework-agnostic contracts");
            rule.check(classes);
        }
    }

    @Nested
    @DisplayName("Gitprovider Bounded Context")
    class GitproviderBoundaryTests {

        /**
         * Vendor-neutral SCM domain should not depend on feature modules.
         *
         * <p>The vendor-neutral SCM layer (integration.scm.* minus the per-vendor
         * adapters scm.github/scm.gitlab) is the core ETL engine for git data sync.
         * It should not have direct dependencies on workspace, activity, or other
         * feature modules - only on SPIs in {@code integration.core.spi}.
         *
         * <p>Vendor adapters under scm.github/scm.gitlab are exempt because their job
         * is to bridge workspace-aware concerns into the neutral domain.
         */
        @Test
        void scmDoesNotDependOnWorkspace() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("de.tum.cit.aet.hephaestus.integration.scm.domain..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("de.tum.cit.aet.hephaestus.workspace..")
                    .because("Vendor-neutral SCM domain must depend on SPIs, not workspace implementation. "
                            + "Vendor adapters (scm.github/scm.gitlab) are exempt — they bridge workspace concerns.");
            rule.check(classes);
        }

        /**
         * Vendor-neutral SCM domain should not depend on feature modules.
         *
         * <p>This is the SINGLE SOURCE OF TRUTH for vendor-neutral SCM isolation.
         * Cross-cutting concerns should be handled via domain events, not direct dependencies.
         *
         * <p>Vendor adapters (scm.github/scm.gitlab) are exempt for the same reason as
         * {@link #scmDoesNotDependOnWorkspace} — but note this is intentionally lax;
         * vendor adapters shouldn't import feature modules either, but Phase 2 of the
         * unified-integration migration carries forward pre-existing coupling.
         *
         * <p>CRITICAL: Keep this list in sync with the actual feature modules.
         */
        @Test
        void scmDoesNotDependOnFeatureModules() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..integration.scm..")
                    .and()
                    .resideOutsideOfPackages("..integration.scm.github..", "..integration.scm.gitlab..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "..activity..", "..mentor..", "..notification..", "..account..", "..contributors..")
                    .because("Vendor-neutral SCM domain must be isolated - use domain events for cross-cutting"
                            + " concerns");
            rule.check(classes);
        }

        /**
         * Provider-specific processors and handlers should be in provider subpackages.
         *
         * <p>To support multiple git providers (GitHub, GitLab), provider-specific
         * logic should be isolated in github/ or gitlab/ subpackages.
         * The sync/ package is allowed to contain orchestrators that dispatch to providers.
         */
        @Test
        void githubSpecificClassesAreInGithubPackages() {
            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameStartingWith("GitHub")
                    .and()
                    .resideInAPackage("..integration.scm..")
                    .and()
                    .resideOutsideOfPackage("..sync..") // Sync orchestrators can dispatch to providers
                    .should()
                    .resideInAPackage("..github..")
                    .because("GitHub-specific logic should be in github/ subpackages for multi-provider support");
            rule.check(classes);
        }
    }

    @Nested
    class DomainEventPatternTests {

        /**
         * Application domain events should be in events packages.
         *
         * <p>Cross-vendor domain events live in {@code integration.events}; activity
         * events use the activity_event ledger. Generated GraphQL model classes
         * ending in 'Event' are excluded.
         */
        @Test
        void domainEventsAreInEventsPackages() {
            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("Event")
                    .and()
                    .resideInAPackage("..integration.scm..")
                    .and()
                    .resideOutsideOfPackage("..graphql..") // Exclude generated GraphQL model
                    .and()
                    .areNotInterfaces()
                    .and()
                    .areNotMemberClasses() // Exclude nested records in SPI interfaces (e.g., callback DTOs)
                    .should()
                    .resideInAPackage("..events..")
                    .because("Domain events should be in events packages");
            rule.check(classes);
        }
    }

    @Nested
    class UtilityClassPatternTests {

        /**
         * Utility classes should be final or have private constructors.
         *
         * <p>Utility classes contain static methods and should not be instantiated
         * or extended.
         */
        @Test
        void utilityClassesAreFinalOrPrivateConstructor() {
            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("Utils")
                    .or()
                    .haveSimpleNameEndingWith("Util")
                    .or()
                    .haveSimpleNameEndingWith("Helper")
                    .should(beFinalOrHaveOnlyPrivateConstructors())
                    .because("Utility classes should not be instantiated or extended");
            rule.check(classes);
        }
    }

    @Nested
    @DisplayName("DTO Patterns")
    class DtoPatternTests {

        /**
         * DTOs should be records or have only final fields.
         *
         * <p>Immutable DTOs are thread-safe and prevent accidental mutation.
         * Java records are preferred for DTOs.
         */
        @Test
        @DisplayName("DTOs are records or have final fields")
        void dtosAreImmutable() {
            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("DTO")
                    .or()
                    .haveSimpleNameEndingWith("Dto")
                    .should(beImmutable())
                    .because("DTOs should be immutable for thread safety");
            rule.check(classes);
        }
    }

    @Nested
    @DisplayName("Repository Patterns")
    class RepositoryPatternTests {

        /**
         * Spring Data repositories should be interfaces.
         *
         * <p>Spring Data automatically implements repository interfaces.
         * Domain entities named "Repository" are excluded from this check.
         */
        @Test
        void springDataRepositoriesAreInterfaces() {
            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("Repository")
                    .and()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .and()
                    .resideOutsideOfPackage("..graphql..")
                    .and()
                    .areAssignableTo(Repository.class)
                    .should()
                    .beInterfaces()
                    .because("Spring Data repositories should be interfaces");
            rule.check(classes);
        }
    }

    @Nested
    @DisplayName("Exception Patterns")
    class ExceptionPatternTests {

        /**
         * Custom exceptions should extend RuntimeException.
         *
         * <p>Using unchecked exceptions simplifies error handling and
         * prevents exception declaration pollution in method signatures.
         */
        @Test
        void customExceptionsExtendRuntimeException() {
            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("Exception")
                    .and()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .should()
                    .beAssignableTo(RuntimeException.class)
                    .because("Custom exceptions should be unchecked for cleaner APIs");
            rule.check(classes);
        }
    }

    @Nested
    class SpiBypassDetectionTests {
        /**
         * Activity reads synced data, never the sync orchestration. Vendor adapters are closed to it by
         * {@link ExternalVendorImportAllowlistTest}.
         */
        @Test
        void activityDoesNotDependOnScmSync() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage(BASE_PACKAGE + ".activity..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..integration.scm.sync..")
                    .because("The activity ledger records through its SPI; it does not drive or read the sync engine");
            rule.check(classes);
        }
    }

    @Nested
    class FeatureModuleBoundaryTests {

        /**
         * Mentor module should only depend on integration.scm and shared modules.
         *
         * <p>Mentor is a standalone feature that consumes pull request data.
         */
        @Test
        void mentorHasLimitedDependencies() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..mentor..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..activity..", "..notification..")
                    .because("Mentor should be isolated from other feature modules");
            rule.check(classes);
        }
    }
}
