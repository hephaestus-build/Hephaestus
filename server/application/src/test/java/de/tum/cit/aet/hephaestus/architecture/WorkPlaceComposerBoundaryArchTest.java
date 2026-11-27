package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The work-place composer writes where a team reads, so it reads only scoped work and public observations (ADR 0121
 * decisions 4, 5 and 12). Its side, the handoff it reads, the composer and the on-work turn, never reaches a private
 * source or a write path, not even through another class. Only the scope builds a handoff, so the scope is the one
 * place that decides what the public writer may read.
 */
class WorkPlaceComposerBoundaryArchTest extends HephaestusArchitectureTest {
    private static final String COMPOSER = BASE_PACKAGE + ".agent.composer.";

    private static final String HANDLER = BASE_PACKAGE + ".agent.handler.";

    /** The classes the work-place composer runs on, with their nested types: the scope reads what the seal hands it. */
    private static final Set<String> COMPOSER_SIDE =
            Set.of("WorkPlaceComposer", "WorkPlaceHandoff", "WorkPlaceScope", "OnWorkTurn");

    private static final DescribedPredicate<JavaClass> WORK_PLACE_COMPOSER = DescribedPredicate.describe(
            "the work-place composer, its scope, its handoff and the on-work turn",
            type -> named(type, COMPOSER, COMPOSER_SIDE));

    /** What the composer may use of the review handler: the text policy and the package records, which read nothing. */
    private static final Set<String> HANDLER_DATA =
            Set.of("FeedbackTextPolicy", "composition.ComposedFeedback", "composition.FeedbackPackage");

    /** The legacy composition's private half and its custody: the admission, the private read, the basis. */
    private static final Set<String> LEGACY_COMPOSITION = Set.of(
            "PrivateFeedback",
            "Admission",
            "ComposerTurns",
            "CompositionSessions",
            "CompositionPlans",
            "CompositionClosing",
            "ComposerRequests",
            "ComposerStreamAnswer",
            "ComposerTranscript",
            "ComposerTranscriptPart",
            "ComposerTranscriptPartRepository");

    private static final DescribedPredicate<JavaClass> PRIVATE_OR_WRITING = resideInAnyPackage(
                    BASE_PACKAGE + ".conversation..",
                    BASE_PACKAGE + ".agent.conversation..",
                    BASE_PACKAGE + ".account..",
                    BASE_PACKAGE + ".notification..",
                    BASE_PACKAGE + ".relationship..",
                    BASE_PACKAGE + ".practices.feedback..",
                    BASE_PACKAGE + ".practices.profile..",
                    BASE_PACKAGE + ".practices.weeklydigest..",
                    BASE_PACKAGE + ".agent.job..",
                    BASE_PACKAGE + ".core.action..",
                    BASE_PACKAGE + ".integration..")
            .or(DescribedPredicate.describe(
                    "the review handler, except its text policy and package records",
                    type -> type.getPackageName().startsWith(HANDLER.substring(0, HANDLER.length() - 1))
                            && !named(type, HANDLER, HANDLER_DATA)))
            .or(DescribedPredicate.describe(
                    "the legacy composition's private half", type -> named(type, COMPOSER, LEGACY_COMPOSITION)));

    private static boolean named(JavaClass type, String prefix, Set<String> names) {
        return names.stream()
                .anyMatch(name ->
                        type.getName().equals(prefix + name) || type.getName().startsWith(prefix + name + "$"));
    }

    @Test
    void shouldKeepTheWorkPlaceComposerAwayFromPrivateSourcesAndWritePaths() {
        noClasses()
                .that(WORK_PLACE_COMPOSER)
                .should()
                .transitivelyDependOnClassesThat(PRIVATE_OR_WRITING)
                .because("a teammate never reads a person's private data, and a shadow composer never writes")
                .check(classes);
    }

    @Test
    void shouldBuildAWorkPlaceHandoffOnlyInItsScope() {
        noClasses()
                .that(DescribedPredicate.describe(
                        "are not the work-place scope", type -> !type.getName().equals(COMPOSER + "WorkPlaceScope")))
                .should()
                .callConstructorWhere(DescribedPredicate.describe(
                        "constructs a work-place handoff",
                        (JavaConstructorCall call) ->
                                call.getTargetOwner().getName().equals(COMPOSER + "WorkPlaceHandoff")))
                .because("the scope is the one place that decides what the public writer reads")
                .check(classes);
    }
}
