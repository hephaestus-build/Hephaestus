package de.tum.cit.aet.hephaestus.agent.runtime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import de.tum.cit.aet.hephaestus.architecture.HephaestusArchitectureTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Architecture boundaries for the unified Pi-runtime kernel.
 *
 * <ul>
 *   <li>{@code agent.runtime} must not depend on agent-domain packages (practice / mentor) —
 *       the kernel stays role-agnostic.</li>
 * </ul>
 */
class AgentRuntimeBoundaryTest extends HephaestusArchitectureTest {

    private static final String RUNTIME = "..agent.runtime..";
    private static final String CONTEXT = "..agent.context..";
    private static final String CONTEXT_PROVIDERS = "..agent.context.providers..";
    private static final String PRACTICE = "..agent.practice..";
    private static final String MENTOR = "..agent.mentor..";
    private static final String TASK = "..agent.task..";

    @Nested
    class RuntimeBoundary {

        @Test
        @DisplayName("agent.runtime must not depend on agent.practice or agent.mentor")
        void runtimeIndependentOfDomains() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage(RUNTIME)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(PRACTICE, MENTOR)
                    .because("agent.runtime is the shared Pi kernel reused by both domains — it must stay"
                            + " role-agnostic");
            rule.check(classes);
        }
    }

    @Nested
    class TaskBoundary {

        @Test
        @DisplayName("agent.task must not depend on agent.practice or agent.mentor")
        void taskIndependentOfDomains() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage(TASK)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(PRACTICE, MENTOR)
                    .because("Task envelope types are wire-format primitives; they must not pull in domain handlers");
            rule.check(classes);
        }
    }

    @Nested
    class PiRunnerProfilePlacement {

        @Test
        @DisplayName("PiRunnerProfile implementations reside in agent.practice or agent.mentor")
        void profilesInDomainPackages() {
            ArchRule rule = classes()
                    .that()
                    .implement(PiRunnerProfile.class)
                    .should()
                    .resideInAnyPackage(PRACTICE, MENTOR)
                    .because("Each runner kind owns its profile next to its adapter; the kernel sees only the"
                            + " interface");
            rule.check(classes);
        }
    }

    @Nested
    class ContextBoundary {

        @Test
        @DisplayName("agent.context (excluding providers) must not depend on agent.practice or agent.mentor")
        void contextCoreIndependentOfDomains() {
            ArchRule rule = noClasses()
                    .that()
                    .resideInAPackage(CONTEXT)
                    .and()
                    .resideOutsideOfPackage(CONTEXT_PROVIDERS)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(PRACTICE, MENTOR)
                    .because("Context orchestration is shared across job types; only the providers under "
                            + "agent.context.providers may take domain-specific dependencies.");
            rule.check(classes);
        }
    }
}
