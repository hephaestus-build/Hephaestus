package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaConstructor;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackCopy;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackLedgerRecorder;
import org.junit.jupiter.api.Test;

class FeedbackLedgerOwnershipTest extends HephaestusArchitectureTest {
    @Test
    void shouldKeepFeedbackRepositoriesInsideTheirOwningModule() {
        noClasses()
                .that()
                .resideOutsideOfPackage("..practices..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..practices.feedback..")
                .because("the runtime prepares and sends feedback through the frozen feedback ports")
                .check(classes);
    }

    @Test
    void shouldLetOnlyTheLedgerRecorderWriteProvenance() {
        noClasses()
                .that()
                .doNotBelongToAnyOf(FeedbackLedgerRecorder.class)
                .should()
                .accessTargetWhere(callsOrReferences(Feedback.class, "setProvenance"))
                .orShould()
                .accessTargetWhere(callsOrReferences(FeedbackCopy.class, "setProvenance"))
                .because("a provenance record is the signature that the ledger recorder makes over the prepared words")
                .check(classesWithTests);
    }

    @Test
    void shouldSetNoProvenanceThroughTheBuilders() {
        noClasses()
                .should()
                .accessTargetWhere(callsOrReferences(Feedback.FeedbackBuilder.class, "provenance"))
                .orShould()
                .accessTargetWhere(callsOrReferences(FeedbackCopy.FeedbackCopyBuilder.class, "provenance"))
                .because("a builder that sets provenance would bypass the signature that the ledger recorder makes")
                .check(classesWithTests);
    }

    @Test
    void shouldOfferNoConstructorThatTakesProvenance() {
        constructors()
                .that()
                .areDeclaredIn(Feedback.class)
                .or()
                .areDeclaredIn(FeedbackCopy.class)
                .and(DescribedPredicate.describe(
                        "take arguments",
                        (JavaConstructor constructor) ->
                                !constructor.getRawParameterTypes().isEmpty()))
                .should()
                .bePrivate()
                .because("a constructor outside the entity would take a provenance past the ledger recorder")
                .check(classes);
    }

    /**
     * A call, a method reference, or a call through a subclass, to {@code method} of {@code owner}. {@code callMethod}
     * sees only a direct call on the exact owner.
     */
    private static DescribedPredicate<JavaAccess<?>> callsOrReferences(Class<?> owner, String method) {
        return DescribedPredicate.describe(
                "call or reference " + owner.getSimpleName() + "." + method,
                access -> access.getTarget() instanceof AccessTarget.CodeUnitAccessTarget target
                        && target.getName().equals(method)
                        && target.getOwner().isAssignableTo(owner));
    }
}
