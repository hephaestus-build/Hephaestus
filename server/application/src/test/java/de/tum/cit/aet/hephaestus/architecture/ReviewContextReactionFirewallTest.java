package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * The review context is reaction-blind (ADR 0021, F-9).
 *
 * <p>A reaction records whether a developer addressed, disputed or set aside an earlier observation. If it
 * reached the sandbox a review reads, the review could learn to agree with whatever the developer accepted,
 * so the {@code ContentSource}s that materialise {@code inputs/} may not depend on the reaction package.
 * Reaction-aware delivery, such as not repeating feedback a developer already resolved, belongs to the
 * delivery layer and the mentor sources and is not constrained here.
 */
class ReviewContextReactionFirewallTest extends HephaestusArchitectureTest {

    private static final String CONTEXT_PROVIDERS = "..agent.context.providers..";
    private static final String MENTOR_PROVIDERS = "..agent.context.providers.mentor..";
    private static final String REACTION_PACKAGE = "..practices.observation.reaction..";

    @Test
    void shouldKeepReactionsOutOfReviewContextProviders() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(CONTEXT_PROVIDERS)
                .and()
                .resideOutsideOfPackage(MENTOR_PROVIDERS)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(REACTION_PACKAGE)
                .because("a review reads the work blind to how developers answered earlier observations "
                        + "(ADR 0021 F-9); reaction-aware behaviour belongs in the delivery layer");
        rule.check(classes);
    }
}
