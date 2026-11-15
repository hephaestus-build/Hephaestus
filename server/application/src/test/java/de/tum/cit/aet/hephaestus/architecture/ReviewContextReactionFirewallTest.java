package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

// Review capture must exclude developer reactions, which could bias results toward accepted feedback.
class ReviewContextReactionFirewallTest extends HephaestusArchitectureTest {

    private static final String CONTEXT_PROVIDERS = "..agent.context.providers..";
    private static final String CONVERSATION_PROVIDERS = "..agent.context.providers.conversation..";
    private static final String REACTION_PACKAGE = "..practices.result.reaction..";

    @Test
    void shouldKeepReactionsOutOfReviewContextProviders() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(CONTEXT_PROVIDERS)
                .and()
                .resideOutsideOfPackage(CONVERSATION_PROVIDERS)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(REACTION_PACKAGE)
                .because("a review reads the work blind to how developers answered earlier results "
                        + "(ADR 0021 F-9); reaction-aware behaviour belongs in the delivery layer");
        rule.check(classes);
    }
}
