package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaMethodCall;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackOutcomes;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Feedback changes its delivery state only through the writer that counts each settled state and delivery. */
class FeedbackOutcomesArchTest extends HephaestusArchitectureTest {

    private static final Set<String> DELIVERY_WRITES =
            Set.of("updateDelivery", "recordFirstRead", "expireConversation");

    @Test
    void shouldChangeFeedbackDeliveryStateOnlyThroughItsCountingWriter() {
        noClasses()
                .that()
                .doNotBelongToAnyOf(FeedbackOutcomes.class)
                .should()
                .callMethodWhere(describe(
                        "a write of a feedback's delivery state",
                        (JavaMethodCall call) -> call.getTargetOwner().isEquivalentTo(FeedbackRepository.class)
                                && DELIVERY_WRITES.contains(call.getName())))
                .because("a state written past FeedbackOutcomes is missing from the delivery metrics")
                .check(classes);
    }
}
