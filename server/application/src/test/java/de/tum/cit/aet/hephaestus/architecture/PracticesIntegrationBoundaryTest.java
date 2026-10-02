package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The practices module may know the integration <em>contract</em> and nothing about who implements it. Once
 * the only permitted imports are the vendor-neutral ports, a new domain becomes bindable by shipping a
 * descriptor and a manifest, with no edit here.
 *
 * <p>The boundary is not yet clean, so the rule freezes what exists rather than failing the build.
 * {@link #FROZEN_VIOLATIONS} may only shrink: {@link #frozenViolationsHaveNotGrown()} fails on a new
 * entry, and {@link #frozenViolationsAreStillReal()} fails on a stale one, so the list cannot quietly
 * become a permanent exemption list. Each entry names what removes it.
 */
@DisplayName("practices depends on the integration contract, not on integrations")
class PracticesIntegrationBoundaryTest extends HephaestusArchitectureTest {

    private static final String PRACTICES = "..practices..";
    private static final String PRACTICES_PACKAGE = "de.tum.cit.aet.hephaestus.practices";
    private static final String INTEGRATION_PACKAGE = "de.tum.cit.aet.hephaestus.integration";

    /**
     * Packages under {@code integration} that {@code practices} may depend on.
     *
     * <p>{@code core.spi} is the contract. {@code core.signal} is the vocabulary that contract is written
     * in — a practice binds to a {@code SignalName}, so the type has to be reachable from here; it is
     * vendor-neutral by construction and carries no behaviour beyond its own grammar.
     */
    private static final Set<String> ALLOWED_PACKAGES = Set.of(
            "de.tum.cit.aet.hephaestus.integration.core.spi", "de.tum.cit.aet.hephaestus.integration.core.signal");

    /**
     * Every dependency from {@code practices} into an integration package outside {@link #ALLOWED_PACKAGES},
     * as {@code <practices class> -> <integration class>}. What removes each: the {@code User}/
     * {@code UserRepository} entries need a {@code practices.spi} port for "who is the current developer"
     * (the same shape as {@code UserRoleChecker}); the detection-gate entries need a gate that takes a
     * recorded signal and a workspace rather than an entity.
     *
     * <p>ArchUnit reads bytecode, and javac inlines {@code String} constants, so a practices-side use of a
     * constant such as {@code ScmDomainEvent.TriggerEventNames} leaves no dependency for this rule to see —
     * do not read its silence as proof that none exists.
     */
    private static final Set<String> FROZEN_VIOLATIONS = Set.of(
            "de.tum.cit.aet.hephaestus.practices.observation.ObservationService -> de.tum.cit.aet.hephaestus.integration.scm.domain.user.User",
            "de.tum.cit.aet.hephaestus.practices.observation.ObservationService -> de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository",
            "de.tum.cit.aet.hephaestus.practices.feedback.inapp.InAppFeedbackService -> de.tum.cit.aet.hephaestus.integration.scm.domain.user.User",
            "de.tum.cit.aet.hephaestus.practices.feedback.inapp.InAppFeedbackService -> de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository",
            "de.tum.cit.aet.hephaestus.practices.review.ReviewGate -> de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue",
            "de.tum.cit.aet.hephaestus.practices.review.ReviewGate -> de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest",
            "de.tum.cit.aet.hephaestus.practices.review.ReviewGate -> de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository",
            "de.tum.cit.aet.hephaestus.practices.reviewoutput.ReviewSubjectResolver -> de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository",
            "de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewSubjectDTO -> de.tum.cit.aet.hephaestus.integration.scm.domain.user.User");

    @Test
    void practicesNeverNamesAVendor() {
        // No freeze here: a vendor package is the hard line, unlike the shared SCM domain FROZEN_VIOLATIONS covers.
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(PRACTICES)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..integration.scm.github..",
                        "..integration.scm.gitlab..",
                        "..integration.slack..",
                        "..integration.outline..")
                .because("A practice binds to a vendor-neutral signal so it works on every provider. Naming one "
                        + "vendor here is what makes adding another an edit to the practices module.");
        rule.check(classes);
    }

    @Test
    void frozenViolationsHaveNotGrown() {
        Set<String> current = currentViolations(classes);
        Set<String> added = new TreeSet<>(current);
        added.removeAll(FROZEN_VIOLATIONS);

        Assertions.assertThat(added)
                .as(
                        "New dependency from practices into integration internals. The contract is reachable "
                                + "through %s — take a port, do not take an entity.",
                        ALLOWED_PACKAGES)
                .isEmpty();
    }

    @Test
    void frozenViolationsAreStillReal() {
        Set<String> current = currentViolations(classes);
        Set<String> stale = new TreeSet<>(FROZEN_VIOLATIONS);
        stale.removeAll(current);

        Assertions.assertThat(stale)
                .as("These frozen violations are gone — delete them so the list can only ever shrink")
                .isEmpty();
    }

    /**
     * Reported as {@code <source> -> <target>} on the outermost enclosing classes, so a lambda or an
     * anonymous class does not produce an entry whose name changes when the file is reformatted.
     */
    private static Set<String> currentViolations(JavaClasses classes) {
        Set<String> violations = new LinkedHashSet<>();
        for (JavaClass source : classes) {
            if (!source.getPackageName().startsWith(PRACTICES_PACKAGE)) {
                continue;
            }
            for (var dependency : source.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass().getBaseComponentType();
                if (!target.getPackageName().startsWith(INTEGRATION_PACKAGE) || isAllowed(target)) {
                    continue;
                }
                violations.add(source.getName() + " -> " + target.getName());
            }
        }
        return new TreeSet<>(violations);
    }

    private static boolean isAllowed(JavaClass target) {
        return ALLOWED_PACKAGES.stream()
                .anyMatch(allowed -> target.getPackageName().startsWith(allowed));
    }
}
