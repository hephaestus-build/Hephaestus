package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.*;
import static de.tum.cit.aet.hephaestus.architecture.conditions.HephaestusConditions.*;

import com.tngtech.archunit.base.DescribedPredicate;
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
 *   <li><b>integration.scm</b> - Shared kernel for git provider data sync (GitHub now, GitLab coming)</li>
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

    // SPI (SERVICE PROVIDER INTERFACE) PATTERN

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

    // GITPROVIDER BOUNDED CONTEXT ISOLATION

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
                    .resideInAPackage("..integration.scm..")
                    .and()
                    .resideOutsideOfPackages("..integration.scm.github..", "..integration.scm.gitlab..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("..workspace..")
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
                    .because(
                            "Vendor-neutral SCM domain must be isolated - use domain events for cross-cutting concerns");
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

    // DOMAIN EVENT PATTERNS

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

    // UTILITY CLASS PATTERNS

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

    // DTO PATTERNS

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

    // REPOSITORY PATTERNS

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

    // EXCEPTION PATTERNS

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

    // SPI BYPASS DETECTION

    @Nested
    class SpiBypassDetectionTests {

        /**
         * Feature modules should only depend on integration.scm's public contracts.
         *
         * <p>Feature modules (activity, practices) must NOT bypass
         * the SPI layer and directly depend on integration.scm internals like:
         * <ul>
         *   <li>integration.scm.sync - internal sync orchestration</li>
         *   <li>integration.scm.github.installation - GitHub installation management</li>
         *   <li>integration.scm.github.app - GitHub-app credential handling</li>
         *   <li>integration.scm.github.lifecycle - GitHub install/uninstall handlers</li>
         *   <li>integration.scm.github.sync - GitHub-specific sync orchestration</li>
         *   <li>integration.scm.github.webhook - GitHub webhook ingest</li>
         *   <li>integration.scm.gitlab.* mirrors of the above</li>
         * </ul>
         *
         * <p>They SHOULD depend on:
         * <ul>
         *   <li>integration.core.spi - Service Provider Interfaces</li>
         *   <li>integration.core.events - Cross-vendor domain events</li>
         *   <li>integration.scm entity packages (for reading data — including
         *       vendor-specific entity sub-packages like scm.github.project)</li>
         * </ul>
         *
         * <p>NOTE: Entity sub-packages under vendor adapters (e.g. scm.github.project) are
         * exempt because they are <i>data</i>, not adapter internals. Phase 3 of the
         * integration-restructure will revisit whether these should move back under
         * scm.{entity}/ for vendor-neutral access.
         */
        @Test
        void featureModulesDoNotBypassScmSpis() {
            // Internal packages that should NOT be accessed directly by feature modules.
            // We list ETL-internal subpackages explicitly instead of blanketing on
            // ..integration.scm.github.. so that entity data packages (e.g. project/)
            // remain readable by feature modules — same as scm.commit/, scm.issue/, etc.
            String[] forbiddenInternalPackages = {
                "..integration.scm.sync..",
                "..integration.scm.github.installation..",
                "..integration.scm.github.app..",
                "..integration.scm.github.lifecycle..",
                "..integration.scm.github.sync..",
                "..integration.scm.github.webhook..",
                "..integration.scm.github.connect..",
                "..integration.scm.github.credentials..",
                "..integration.scm.github.manifest..",
                "..integration.scm.gitlab.lifecycle..",
                "..integration.scm.gitlab.sync..",
                "..integration.scm.gitlab.webhook..",
                "..integration.scm.gitlab.connect..",
                "..integration.scm.gitlab.credentials..",
                "..integration.scm.gitlab.manifest..",
            };

            ArchRule rule = noClasses()
                    .that()
                    .resideInAnyPackage("..activity..", "..practices..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(forbiddenInternalPackages)
                    .because("Feature modules should depend on integration.scm SPIs (..spi..), DTOs (..dto..), "
                            + "or events (..events..) - not internal packages like sync or installation");
            rule.check(classes);
        }

        /**
         * Verifies that feature modules use the SPI pattern correctly.
         *
         * <p>When feature modules need to provide functionality to integration.scm (callbacks,
         * context providers), they should implement SPIs defined in integration.spi,
         * not create ad-hoc coupling.
         */
        @Test
        void featureModulesDoNotDependOnGitHubServiceImplementations() {
            // Entity data sub-packages (e.g. scm.github.project) stay readable; only
            // adapter/service implementations are off-limits. See
            // featureModulesDoNotBypassScmSpis for the broader rationale.
            //
            // Equivalent to: no feature-module class may depend on a class that is
            // in ..integration.scm.github.. AND not in {..integration.scm.github.project..,
            // ..integration.scm.github.events..}.
            //
            // The events subpackage carries GitHub-specific event payloads (Project v2 events
            // among them) that the activity log consumes verbatim — keeping the listener
            // outside activity would create a platform→activity cycle (see ArchitectureTest
            // noCyclesBetweenModules). The pragmatic exemption: events are data-only records,
            // they can't bypass an SPI because they don't have one.
            ArchRule rule = noClasses()
                    .that()
                    .resideInAnyPackage("..activity..", "..practices..")
                    .should()
                    .dependOnClassesThat(DescribedPredicate.describe(
                            "reside in ..integration.scm.github.. but not ..integration.scm.github.project.. "
                                    + "and not ..integration.scm.github.events..",
                            cls -> {
                                String pkg = cls.getPackageName();
                                return (pkg.contains(".integration.scm.github")
                                        && !pkg.contains(".integration.scm.github.project")
                                        && !pkg.contains(".integration.scm.github.events"));
                            }))
                    .because(
                            "Feature modules should use SPIs, not depend on provider-specific service implementations");
            rule.check(classes);
        }
    }

    // FEATURE MODULE BOUNDARIES

    @Nested
    class FeatureModuleBoundaryTests {

        /**
         * Activity should not depend on workspace internal implementation.
         *
         * <p>Feature modules should depend on public APIs, not internal details.
         */
        @Test
        void activityDoesNotDependOnWorkspaceInternals() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..activity..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..workspace..internal..", "..workspace..adapter..")
                    .because("Activity should depend on workspace public API, not internals");
            rule.check(classes);
        }

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

        /**
         * The activity read model should not depend on core sync logic.
         *
         * <p>The Activity pages are a read-only view that should not modify
         * or depend on the sync engine internals.
         */
        @Test
        void activityOverviewDoesNotDependOnSyncInternals() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..activity.overview..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..integration.scm.sync..", "..integration.scm.github..")
                    .because("The activity read model should only read data, not depend on sync internals");
            rule.check(classes);
        }

        /**
         * Notification module should not directly depend on feature module internals.
         *
         * <p>Notification should receive events through the event system,
         * not by depending on feature module implementations.
         */
        @Test
        void notificationUsesEventsNotDirectDependencies() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..notification..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("..activity..service..", "..mentor..service..")
                    .because("Notification should use domain events for cross-module communication");
            rule.check(classes);
        }
    }
}
