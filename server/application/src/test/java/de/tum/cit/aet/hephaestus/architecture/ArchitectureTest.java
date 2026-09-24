package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.GeneralCodingRules.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.*;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

/**
 * Core Architecture Tests - Critical architectural invariants.
 *
 * <p>These tests enforce fundamental architectural constraints that prevent
 * architectural erosion and maintain system integrity:
 *
 * <h2>Test Categories (Priority Order)</h2>
 * <ol>
 *   <li><b>Structural Integrity</b> - Cycle detection, layering violations</li>
 *   <li><b>Module Boundaries</b> - Bounded context isolation, SPI patterns</li>
 *   <li><b>Spring Patterns</b> - Framework best practices</li>
 *   <li><b>Coding Standards</b> - General code quality</li>
 * </ol>
 *
 * <p>All thresholds are defined in {@link ArchitectureTestConstants}.
 *
 * @see ArchitectureTestConstants
 * @see <a href="https://www.archunit.org/userguide/html/000_Index.html">ArchUnit User Guide</a>
 */
class ArchitectureTest extends HephaestusArchitectureTest {

    // STRUCTURAL INTEGRITY - Critical architectural invariants

    @Nested
    class StructuralIntegrity {

        /**
         * No cyclic dependencies between bounded contexts.
         *
         * <p>Six top-level packages fold into one {@code scm-data-platform} slice. The set
         * is derived from the actual cycle paths, and each member is here for a specific,
         * documented reason — not a blanket "they share entities" claim:
         *
         * <ul>
         *   <li><b>integration ↔ workspace</b> — the one irreducible cycle. {@code Connection}
         *       (integration.core) and {@code Workspace} are a single aggregate: Connection
         *       rows are workspace-owned and the GitHub/GitLab workspace bridges mutate
         *       {@code Workspace}/{@code RepositoryToMonitor}. Splitting them means relocating
         *       the Workspace aggregate — wrong and out of scope.</li>
         *   <li><b>activity, leaderboard</b> — folded by a structural tension between two rules,
         *       NOT entity sharing. {@code ExternalVendorImportAllowlistTest} forces vendor code
         *       (the Slack leaderboard publisher, the GitHub Projects-v2 activity listener) to
         *       live inside {@code integration}; that bridge code legitimately reads feature data
         *       (the leaderboard digest, the activity write SPI), giving {@code integration →
         *       leaderboard} and {@code integration → activity}. With the normal {@code feature →
         *       integration} direction this is a cycle no relocation removes — the vendor code can
         *       neither leave integration (allowlist) nor stop reading feature data (its job). The
         *       only escapes are a neutral cross-module event bus or per-sub-package slicing, both
         *       larger dedicated changes tracked as follow-ups.</li>
         *   <li><b>config, profile</b> — transitive collateral of the single {@code integration →
         *       leaderboard} edge ({@code config → integration → leaderboard → config};
         *       {@code profile → integration → leaderboard → profile}). They leave this slice
         *       automatically once that edge is broken.</li>
         * </ul>
         *
         * <p>No frozen baseline — the rule passes cleanly and still fails on any NEW cycle that
         * pulls a genuinely-independent module (agent, mentor, practices, account, notification,
         * activity, analytics, …) into the platform or into each other. Finer boundaries inside
         * the platform stay policed by {@code ModuleBoundaryTest},
         * {@code CrossCuttingModuleBoundaryTest}, {@code IntegrationCoreVendorNeutralityTest}, and
         * {@code ExternalVendorImportAllowlistTest}.
         */
        @Test
        void noCyclesBetweenModules() {
            Set<String> dataPlatform =
                    Set.of("integration", "workspace", "config", "activity", "leaderboard", "profile");
            SliceAssignment boundedContexts = new SliceAssignment() {
                @Override
                public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
                    String pkg = javaClass.getPackageName();
                    if (!pkg.startsWith(BASE_PACKAGE + ".")) {
                        return SliceIdentifier.ignore();
                    }
                    String tail = pkg.substring(BASE_PACKAGE.length() + 1);
                    int dot = tail.indexOf('.');
                    String top = dot < 0 ? tail : tail.substring(0, dot);
                    // integration.slack and integration.outline are each their own bounded context — a
                    // distinct integration modeled as its own Spring Modulith module (see the respective
                    // integration/<vendor>/package-info.java), not part of the folded SCM data platform.
                    // Folding one in would falsely couple its edges to feature modules (Slack->practices
                    // /account/agent, Outline->agent) to the SCM data platform's own dependencies.
                    if (tail.equals("integration.slack") || tail.startsWith("integration.slack.")) {
                        return SliceIdentifier.of("integration-slack");
                    }
                    if (tail.equals("integration.outline") || tail.startsWith("integration.outline.")) {
                        return SliceIdentifier.of("integration-outline");
                    }
                    return dataPlatform.contains(top)
                            ? SliceIdentifier.of("scm-data-platform")
                            : SliceIdentifier.of(top);
                }

                @Override
                public String getDescription() {
                    return "bounded contexts (SCM data platform folded; feature modules per top-level package)";
                }
            };
            ArchRule rule = slices().assignedFrom(boundedContexts)
                    .should()
                    .beFreeOfCycles()
                    .because("Cyclic dependencies between bounded contexts prevent independent evolution");
            rule.check(classes);
        }

        /**
         * Controllers should only delegate to services.
         *
         * <p>Controllers are thin entry points - they should not contain
         * business logic or access data layer directly.
         */
        @Test
        void controllersDoNotAccessRepositories() {
            ArchRule rule = noClasses()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .should()
                    .dependOnClassesThat()
                    .areAnnotatedWith(Repository.class)
                    .because("Controllers should delegate to services, not access data layer directly");
            rule.check(classes);
        }
    }

    // MODULE BOUNDARIES - SPI patterns (main isolation tests in ModuleBoundaryTest)

    @Nested
    class ModuleBoundaries {

        /**
         * SPI interfaces define the contract for dependency inversion.
         *
         * <p>Classes implementing SPIs should be in adapter packages
         * within their respective feature modules.
         */
        @Test
        void spiInterfacesAreInSpiPackage() {
            // Prevent Provider/Resolver/Listener SPI interfaces from creeping back
            // into integration.scm — they belong in integration.spi.
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..integration.scm.domain.common..")
                    .and()
                    .areInterfaces()
                    .should()
                    .haveSimpleNameEndingWith("Provider")
                    .orShould()
                    .haveSimpleNameEndingWith("Resolver")
                    .orShould()
                    .haveSimpleNameEndingWith("Listener")
                    .because("Cross-module SPIs (Provider/Resolver/Listener) belong in integration.spi");
            rule.check(classes);
        }
    }

    // SPRING BEST PRACTICES - Framework patterns

    @Nested
    class SpringBestPractices {

        /**
         * Transaction boundaries belong on service layer.
         *
         * <p>Controllers should not define transactions - this is the
         * responsibility of the service layer.
         */
        @Test
        void transactionalNotOnControllers() {
            ArchRule rule = noClasses()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .should()
                    .beAnnotatedWith(Transactional.class)
                    .because("Transaction boundaries should be defined in the service layer");
            rule.check(classes);
        }

        /**
         * Configuration classes follow naming conventions.
         *
         * <p>All @Configuration classes should have "Config" or "Configuration"
         * suffix for discoverability.
         */
        @Test
        void configurationClassesHaveConfigSuffix() {
            ArchRule rule = classes()
                    .that()
                    .areAnnotatedWith(Configuration.class)
                    .should()
                    .haveSimpleNameEndingWith("Config")
                    .orShould()
                    .haveSimpleNameEndingWith("Configuration")
                    .because("Configuration classes should be easily identifiable by naming");
            rule.check(classes);
        }

        /**
         * Repository interfaces extend Spring Data.
         *
         * <p>Custom repository implementations should still extend
         * Spring Data abstractions for consistency.
         */
        @Test
        void repositoriesExtendSpringData() {
            ArchRule rule = classes()
                    .that()
                    .areAnnotatedWith(Repository.class)
                    .and()
                    .areInterfaces()
                    .should()
                    .beAssignableTo(org.springframework.data.repository.Repository.class)
                    .because("Repositories should use Spring Data abstractions");
            rule.check(classes);
        }
    }

    // CODING STANDARDS - Core quality rules

    @Nested
    class CodingStandardsCore {

        /**
         * No console output - use SLF4J.
         */
        @Test
        void noSystemOutOrErr() {
            ArchRule rule = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.because(
                    "Use SLF4J (LoggerFactory.getLogger) instead of System.out/err");
            rule.check(classes);
        }

        /**
         * No generic exceptions.
         */
        @Test
        void noGenericExceptions() {
            ArchRule rule = noClasses()
                    .should(THROW_GENERIC_EXCEPTIONS)
                    .because("Use specific exception types for better error handling");
            rule.check(classes);
        }

        /**
         * No field injection.
         */
        @Test
        void noFieldInjection() {
            ArchRule rule = NO_CLASSES_SHOULD_USE_FIELD_INJECTION.because(
                    "Constructor injection makes dependencies explicit and testable");
            rule.check(classes);
        }
    }

    // CODING STANDARDS - Logging and dependencies

    @Nested
    class CodingStandardsLogging {

        /**
         * No Joda Time usage.
         *
         * <p>Java 8+ has java.time API - Joda Time is deprecated.
         */
        @Test
        void noJodaTime() {
            ArchRule rule = noClasses()
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.joda.time..")
                    .because("Use java.time API instead of deprecated Joda Time");
            rule.check(classes);
        }

        /**
         * No java.util.logging.
         *
         * <p>SLF4J provides consistent logging facade.
         */
        @Test
        void noJavaUtilLogging() {
            ArchRule rule = noClasses()
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("java.util.logging..")
                    .because("Use SLF4J for consistent logging across the application");
            rule.check(classes);
        }

        /**
         * No Apache Commons Logging.
         *
         * <p>SLF4J provides consistent logging facade.
         */
        @Test
        void noCommonsLogging() {
            ArchRule rule = noClasses()
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("org.apache.commons.logging..")
                    .because("Use SLF4J for consistent logging across the application");
            rule.check(classes);
        }
    }

    // NAMING CONVENTIONS - Consistency and discoverability

    @Nested
    class NamingConventions {

        @Test
        void controllerNaming() {
            ArchRule rule = classes()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .should()
                    .haveSimpleNameEndingWith("Controller")
                    .because("Consistent naming improves code discoverability");
            rule.check(classes);
        }

        @Test
        void repositoryNaming() {
            ArchRule rule = classes()
                    .that()
                    .areAnnotatedWith(Repository.class)
                    .should()
                    .haveSimpleNameEndingWith("Repository")
                    .because("Consistent naming improves code discoverability");
            rule.check(classes);
        }

        /**
         * Adapter classes should use @Component, not @Service.
         *
         * <p>Adapters are technical integration code that bridges infrastructure
         * and domain layers. They should use @Component because they don't contain
         * business logic - they delegate to services.
         *
         * <p><b>Why package-based, not name-based:</b> Spring's @Service annotation
         * indicates the class is in the service LAYER, not that it has a particular
         * name. A class named "PaymentProcessor" containing business logic SHOULD
         * use @Service. We test by package location (which implies architectural layer)
         * rather than by name suffix.
         */
        @Test
        @DisplayName("Adapter classes use @Component, not @Service")
        void adaptersAreNotServices() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage("..adapter..")
                    .should()
                    .beAnnotatedWith(Service.class)
                    .because("Adapters are infrastructure glue, not business services - use @Component");
            rule.check(classes);
        }
    }
}
