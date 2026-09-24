package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.*;
import static de.tum.cit.aet.hephaestus.architecture.conditions.HephaestusConditions.*;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.*;

/**
 * Spring & DDD Architecture Tests.
 *
 * <p>These tests enforce Spring best practices and DDD patterns:
 * <ul>
 *   <li>Layered architecture enforcement</li>
 *   <li>DTO boundary protection</li>
 *   <li>Security annotation coverage</li>
 *   <li>Service layer isolation</li>
 *   <li>DDD aggregate patterns</li>
 *   <li>Package structure conventions</li>
 * </ul>
 *
 * <p>All thresholds are defined in {@link ArchitectureTestConstants}.
 *
 * @see ArchitectureTestConstants
 * @see ArchitectureTest for core architecture tests
 */
@DisplayName("Spring & DDD Architecture")
class AdvancedArchitectureTest extends HephaestusArchitectureTest {

    // LAYERED ARCHITECTURE - Strict dependency direction

    @Nested
    class LayeredArchitectureTests {

        @Test
        void servicesDoNotDependOnControllers() {
            ArchRule rule = noClasses()
                    .that()
                    .haveSimpleNameEndingWith("Service")
                    .should()
                    .dependOnClassesThat()
                    .haveSimpleNameEndingWith("Controller")
                    .because("Services should not know about the presentation layer");
            rule.check(classes);
        }

        @Test
        void repositoriesDoNotDependOnServices() {
            ArchRule rule = noClasses()
                    .that()
                    .haveSimpleNameEndingWith("Repository")
                    .should()
                    .dependOnClassesThat()
                    .haveSimpleNameEndingWith("Service")
                    .because("Repositories should not call business logic");
            rule.check(classes);
        }
    }

    // DTO BOUNDARIES - Protect domain from data transfer objects

    @Nested
    @DisplayName("DTO Boundaries")
    class DtoBoundaryTests {

        @Test
        void entitiesDoNotDependOnDtos() {
            ArchRule rule = noClasses()
                    .that()
                    .areAnnotatedWith(jakarta.persistence.Entity.class)
                    .should()
                    .dependOnClassesThat()
                    .haveSimpleNameEndingWith("DTO")
                    .because("Domain entities should not know about DTOs");
            rule.check(classes);
        }

        @Test
        void dtosAreImmutableRecords() {
            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("DTO")
                    .should()
                    .beRecords()
                    .orShould()
                    .haveOnlyFinalFields()
                    .because("DTOs should be immutable for thread safety and clarity");
            rule.check(classes);
        }

        /** This codebase uses "Package by Feature" - DTOs may live colocated with their domain. */
        @Test
        void dtosInDtoPackages() {
            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("DTO")
                    .should()
                    .resideInAPackage("..dto..")
                    .orShould()
                    .beRecords() // Records are inherently DTOs - colocated is fine
                    .orShould()
                    .haveSimpleNameEndingWith("InfoDTO") // Info DTOs can be colocated
                    .orShould()
                    .beNestedClasses() // Inner class DTOs are scoped to outer class
                    .because("DTOs should be in dto packages or colocated with their domain (Package by Feature)");
            rule.check(classes);
        }
    }

    // SECURITY - Ensure all endpoints are protected

    @Nested
    class SecurityTests {

        @Test
        void controllerMethodsHaveSecurityAnnotations() {
            ArchRule rule = methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .areAnnotatedWith(RestController.class)
                    .and()
                    .arePublic()
                    .should(haveSecurityAnnotationIfEndpoint())
                    .because("All endpoints must have explicit security");

            rule.check(classes);
        }
    }

    // DOMAIN-DRIVEN DESIGN - Aggregate and event patterns

    @Nested
    @DisplayName("DDD Patterns")
    class DddPatternTests {

        @Test
        void domainEventsAreRecords() {
            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("Event")
                    .and()
                    .resideInAPackage("..event..")
                    .should()
                    .beRecords()
                    .orShould()
                    .beInterfaces()
                    .because("Domain events should be immutable records");
            rule.check(classes);
        }

        /** This codebase uses "Package by Feature" - listeners are colocated with their domain. */
        @Test
        void eventListenersInApplicationPackages() {
            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("Listener")
                    .or()
                    .haveSimpleNameEndingWith("EventHandler")
                    .and()
                    .resideOutsideOfPackage("..spi..")
                    .and()
                    .areNotMemberClasses()
                    .should()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .because("Event listeners should be within the application package structure");
            rule.check(classes);
        }

        @Test
        void spiImplementationsInAdapterPackages() {
            ArchCondition<JavaClass> implementSpiInterfaces = new ArchCondition<>("implement SPI interfaces properly") {
                @Override
                public void check(JavaClass javaClass, ConditionEvents events) {
                    boolean implementsSpi = javaClass.getAllRawInterfaces().stream()
                            .anyMatch(i -> i.getPackageName().startsWith(BASE_PACKAGE)
                                    && i.getPackageName().contains(".spi"));

                    if (!implementsSpi) {
                        return; // Not an SPI implementation (or implements a third-party SPI)
                    }

                    // Sealed-record permitted variants live in the same package as the SPI; that's
                    // not the adapter pattern, it's a value type closed over the seal.
                    if (javaClass.isRecord()) {
                        return;
                    }

                    // Sub-SPI interfaces (e.g. family-typed extensions like ScmFeedbackChannel,
                    // sealed permits like FeedbackAnchor.DocumentAnchor) are contracts, not adapters.
                    if (javaClass.isInterface()) {
                        return;
                    }

                    // Any class under integration.<kind>.* is a per-vendor adapter by
                    // definition (webhook/credentials/connect/lifecycle/sync/…).
                    boolean inIntegrationVendorPackage = javaClass
                            .getPackageName()
                            .matches("^de\\.tum\\.cit\\.aet\\.hephaestus\\.integration\\.[a-z]+\\..*");

                    // Same-module SPI impl: the class lives in the same top-level module as
                    // the SPI interface it implements (e.g. activity/ActivityEventService implements
                    // activity/spi/ActivityRecorder). The module exposes its own contract; this is
                    // not an adapter pattern, it's a "this module IS the implementation" pattern.
                    boolean implementsSpiFromSameModule = javaClass.getRawInterfaces().stream()
                            .filter(i -> i.getPackageName().startsWith(BASE_PACKAGE)
                                    && i.getPackageName().contains(".spi"))
                            .anyMatch(spiInterface -> {
                                String classModule = topLevelModule(javaClass.getPackageName());
                                String spiModule = topLevelModule(spiInterface.getPackageName());
                                return classModule != null && classModule.equals(spiModule);
                            });

                    boolean inAdapterPackage = inIntegrationVendorPackage
                            || implementsSpiFromSameModule
                            || javaClass.getPackageName().contains(".adapter")
                            || javaClass.getPackageName().contains(".impl")
                            || javaClass.getPackageName().contains(".handler")
                            || // Job type handlers implement handler SPI
                            javaClass.getPackageName().contains(".context.providers")
                            || // Content sources declare ReviewContextBuilder
                            javaClass.getPackageName().contains(".manifest")
                            || // IntegrationManifest impls + bootstrap utilities
                            javaClass.getPackageName().contains(".registry")
                            || // ConnectionPurgeContributor lives with the entity
                            // A domain descriptor belongs beside the artifact it describes, not in a vendor package.
                            javaClass.getSimpleName().endsWith("ArtifactDescriptor")
                            || javaClass.getSimpleName().endsWith("Adapter")
                            || javaClass.getSimpleName().endsWith("Provider")
                            || javaClass.getSimpleName().endsWith("Tracker")
                            || // Rate limit trackers implement RateLimitTracker SPI
                            javaClass.getSimpleName().endsWith("Manifest")
                            || // Per-kind IntegrationManifest impls
                            javaClass
                                    .getSimpleName()
                                    .endsWith("Contributor"); // SPI suffix used by WorkspacePurgeContributor + similar

                    if (!inAdapterPackage) {
                        events.add(SimpleConditionEvent.violated(
                                javaClass,
                                String.format(
                                        "%s implements SPI but is not in adapter package", javaClass.getSimpleName())));
                    }
                }
            };

            ArchRule rule = classes().should(implementSpiInterfaces).because("SPI implementations are adapters");
            rule.check(classes);
        }
    }

    // CONTROLLER PATTERNS - Thin controllers

    @Nested
    @DisplayName("Controller Patterns")
    class ControllerPatternTests {

        @Test
        void controllersDoNotAccessJpaDirectly() {
            ArchRule rule = noClasses()
                    .that()
                    .areAnnotatedWith(RestController.class)
                    .should()
                    .dependOnClassesThat()
                    .areAssignableTo(jakarta.persistence.EntityManager.class)
                    .because("Controllers should not access JPA directly - use services");
            rule.check(classes);
        }

        @Test
        void controllersDoNotReturnEntities() {
            ArchCondition<JavaMethod> notReturnEntity = new ArchCondition<>("not return JPA entity") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                    boolean hasMapping = method.isAnnotatedWith(GetMapping.class)
                            || method.isAnnotatedWith(PostMapping.class)
                            || method.isAnnotatedWith(PutMapping.class)
                            || method.isAnnotatedWith(DeleteMapping.class)
                            || method.isAnnotatedWith(PatchMapping.class);

                    if (!hasMapping) {
                        return;
                    }

                    String returnType = method.getRawReturnType().getName();
                    if (returnType.contains(".integration.scm.")
                            && !returnType.endsWith("DTO")
                            && !returnType.contains("ResponseEntity")
                            && !returnType.contains("Void")
                            && !returnType.equals("void")) {
                        events.add(SimpleConditionEvent.violated(
                                method,
                                String.format(
                                        "Method %s.%s returns entity type %s - use DTO",
                                        method.getOwner().getSimpleName(), method.getName(), returnType)));
                    }
                }
            };

            ArchRule rule = methods()
                    .that()
                    .areDeclaredInClassesThat()
                    .areAnnotatedWith(RestController.class)
                    .should(notReturnEntity)
                    .because("Controllers should return DTOs, not entities");

            rule.check(classes);
        }
    }

    // PACKAGE STRUCTURE - Consistent organization

    @Nested
    class PackageStructureTests {

        @Test
        void noCyclesWithinFeatureModules() {
            ArchRule rule = slices().matching(BASE_PACKAGE + ".workspace.(*)..")
                    .should()
                    .beFreeOfCycles()
                    .because("Feature module subpackages should not have cycles");
            rule.check(classes);
        }

        @Test
        void utilityClassesInUtilPackages() {
            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("Util")
                    .or()
                    .haveSimpleNameEndingWith("Utils")
                    .or()
                    .haveSimpleNameEndingWith("Helper")
                    .or()
                    .haveSimpleNameEndingWith("Helpers")
                    .should()
                    .resideInAPackage("..util..")
                    .orShould()
                    .resideInAPackage("..common..")
                    .orShould()
                    .resideInAPackage("..core..") // Core infrastructure utilities
                    .orShould()
                    .resideInAPackage(BASE_PACKAGE) // Root-level cross-cutting utils (e.g., SecurityUtils)
                    .because("Utility classes should be in util, common, core, or root packages");
            rule.check(classes);
        }

        /**
         * This codebase uses "Package by Feature" - exceptions are colocated with their domain. The
         * only restriction: exceptions must be RuntimeException subclasses (enforced in ModuleBoundaryTest).
         */
        @Test
        void exceptionsInApplicationPackage() {
            ArchRule rule = classes()
                    .that()
                    .areAssignableTo(Exception.class)
                    .and()
                    .doNotHaveSimpleName("Exception")
                    .and()
                    .areNotMemberClasses()
                    .should()
                    .resideInAPackage(BASE_PACKAGE + "..")
                    .because("Custom exceptions should be within the application package structure");
            rule.check(classes);
        }
    }

    // TEST ARCHITECTURE - Tests follow patterns

    @Nested
    class TestArchitectureTests {

        /**
         * Tests that drive Liquibase themselves and therefore must NOT have a Spring context: the context
         * would build the schema before the test could seed the pre-migration rows it exists to migrate.
         */
        private static final Set<String> SCHEMA_OWNING_TESTS = Set.of(
                "LegacyAgentConfigMigrationIntegrationTest", "PracticeCatalogInstallationMigrationIntegrationTest");

        @Test
        void integrationTestsExtendBaseClasses() {
            Set<String> baseClassNames = Set.of(
                    "AbstractWorkspaceIntegrationTest",
                    "AbstractGitHubLiveSyncIntegrationTest",
                    "BaseGitHubLiveIntegrationTest",
                    "BaseIntegrationTest",
                    "RealAuthIntegrationTest");

            Set<String> validBaseClasses = Set.of(
                    "de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest",
                    "de.tum.cit.aet.hephaestus.integration.scm.github.AbstractGitHubLiveSyncIntegrationTest",
                    "de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest",
                    "de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest");

            ArchCondition<JavaClass> haveIntegrationHarness =
                    new ArchCondition<>("declare its integration harness explicitly") {
                        @Override
                        public void check(JavaClass javaClass, ConditionEvents events) {
                            if (SCHEMA_OWNING_TESTS.contains(javaClass.getSimpleName())) {
                                return;
                            }
                            boolean hasSpringBootTest = javaClass.isAnnotatedWith(SpringBootTest.class);
                            boolean hasFocusedHarness = javaClass.isAnnotatedWith(DataJpaTest.class)
                                    || (javaClass.isAnnotatedWith(Tag.class)
                                            && Set.of("integration", "database")
                                                    .contains(javaClass
                                                            .getAnnotationOfType(Tag.class)
                                                            .value()));

                            boolean extendsValidBase = javaClass.getAllRawSuperclasses().stream()
                                    .anyMatch(superClass -> validBaseClasses.contains(superClass.getName()));

                            if (!hasSpringBootTest && !hasFocusedHarness && !extendsValidBase) {
                                events.add(SimpleConditionEvent.violated(
                                        javaClass,
                                        String.format(
                                                "%s is an integration test but does not declare a Spring slice, integration tag, or full-context base",
                                                javaClass.getSimpleName())));
                            }
                        }
                    };

            ArchRule rule = classes()
                    .that()
                    .haveSimpleNameEndingWith("IntegrationTest")
                    .and()
                    .haveSimpleNameNotContaining("Abstract")
                    .and()
                    .haveSimpleNameNotStartingWith("Base")
                    .should(haveIntegrationHarness)
                    .because("Integration tests need proper Spring context via base class or annotation");
            rule.check(classesWithTests);
        }

        @Test
        void testClassesEndWithTestSuffix() {
            ArchRule rule = classes()
                    .that()
                    .resideInAPackage("..test..")
                    .and()
                    .areNotInterfaces()
                    .and()
                    .areNotAnonymousClasses()
                    .and()
                    .doNotHaveSimpleName("package-info")
                    .and()
                    .areNotAnnotatedWith(Configuration.class)
                    .and()
                    .haveSimpleNameNotStartingWith("Abstract")
                    .and()
                    .haveSimpleNameNotEndingWith("Base")
                    .and()
                    .haveSimpleNameNotEndingWith("Factory")
                    .and()
                    .haveSimpleNameNotEndingWith("Utils")
                    .and()
                    .haveSimpleNameNotEndingWith("Config")
                    .and()
                    .haveSimpleNameNotEndingWith("Configuration")
                    .should()
                    .haveSimpleNameEndingWith("Test")
                    .orShould()
                    .haveSimpleNameEndingWith("Tests")
                    .orShould()
                    .haveSimpleNameEndingWith("IT")
                    .because("Test classes should be easily identifiable");
            rule.check(classesWithTests);
        }
    }

    /**
     * Returns the top-level module name ({@code activity}, {@code workspace}, …) for a
     * package, or {@code null} if the package is outside {@code BASE_PACKAGE}.
     */
    private static @org.jspecify.annotations.Nullable String topLevelModule(String packageName) {
        if (!packageName.startsWith(BASE_PACKAGE + ".")) {
            return null;
        }
        String tail = packageName.substring(BASE_PACKAGE.length() + 1);
        int dot = tail.indexOf('.');
        return dot < 0 ? tail : tail.substring(0, dot);
    }
}
