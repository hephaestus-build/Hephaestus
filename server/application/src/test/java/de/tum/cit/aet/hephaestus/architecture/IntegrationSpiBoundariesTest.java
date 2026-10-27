package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Architectural fitness functions for the unified integration framework boundary.
 *
 * <p>Each rule encodes an invariant whose violation has bitten this repo before — or
 * would silently re-introduce the per-vendor coupling the unification fixed:
 * <ul>
 *   <li>{@link #spiHasNoVendorSdkDependencies} — {@code integration/core/spi} stays vendor-
 *       agnostic. Today {@code RateLimitTracker} imports
 *       {@code de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GHRateLimit}; that
 *       drift is acceptable while {@code integration.scm/} is still load-bearing, but new
 *       SPI surfaces must NOT add vendor-SDK imports.
 *   <li>{@link #kindModulesDoNotImportEachOther} — {@code integration/scm/github} cannot
 *       import {@code integration/scm/gitlab}, etc. Cross-kind coupling defeats the point
 *       of the SPI.
 *   <li>{@link #agentDoesNotReadProviderEnumConstants} — agent/** must dispatch via the SPI
 *       registry (a {@code Map<IntegrationKind, …Channel>} keyed by {@code channel.kind()}),
 *       never by naming a concrete enum constant ({@code IntegrationKind.GITHUB} /
 *       {@code GITLAB} / {@code SLACK}), and must never reach for the legacy
 *       {@code IdentityProviderType} enum at all. The delivery classes
 *       {@code DiffNotePoster}, {@code PullRequestCommentPoster}, {@code FeedbackDeliveryService}
 *       must not branch on the provider enum.
 * </ul>
 */
class IntegrationSpiBoundariesTest extends HephaestusArchitectureTest {

    private static final String INTEGRATION_KIND_FQN = "de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind";
    private static final String GIT_PROVIDER_TYPE_FQN =
            "de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType";

    @Test
    void spiHasNoVendorSdkDependencies() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("..integration.core.spi..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.kohsuke..", "com.slack..", "org.gitlab4j..", "com.linecorp.bot..")
                .because("The unified SPI must remain vendor-neutral. Vendor SDK types belong in "
                        + "integration/<kind>/internal/, never on a cross-vendor port.");
        rule.check(classes);
    }

    @Test
    void kindModulesDoNotImportEachOther() {
        List<String> adapters = List.of(
                "..integration.scm.github..",
                "..integration.scm.gitlab..",
                "..integration.slack..",
                "..integration.outline..");
        for (String adapter : adapters) {
            check(noClasses()
                    .that()
                    .resideInAPackage(adapter)
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(adapters.stream()
                            .filter(other -> !other.equals(adapter))
                            .toArray(String[]::new))
                    .because("Cross-kind coupling defeats the SPI. Use the shared integration/spi surface."));
        }
    }

    /**
     * The load-bearing invariant.
     *
     * <p>Agent-side code must dispatch on the provider through the SPI <em>registry</em>
     * ({@code Map<IntegrationKind, …Channel>} populated from Spring beans and looked up by
     * {@code channel.kind()}), never by naming a concrete enum constant. Reading
     * {@code IntegrationKind.GITHUB} / {@code GITLAB} / {@code SLACK} <em>is</em> the
     * "branch on provider" smell: it bakes one vendor's behaviour into vendor-neutral
     * orchestration. Using {@code IntegrationKind} purely as a {@code Map} key <em>type</em>
     * or a {@code Class} literal ({@code new EnumMap<>(IntegrationKind.class)}) is fine — those
     * are not constant reads, so this condition leaves them alone.
     *
     * <p>The check is structural, not name-based: it inspects every field access from each
     * {@code ..agent..} class and fails on any read of an {@code IntegrationKind} enum constant.
     * There are no exemptions: every agent/** class must resolve its kind from context (the
     * event it consumes, or {@code ConnectionService.findActiveProviderKind}) rather than
     * naming a constant.
     */
    @Test
    void agentDoesNotReadProviderEnumConstants() {
        ArchRule rule = classes()
                .that()
                .resideInAPackage("..agent..")
                // Bites for both enums: no constant reads of IntegrationKind, and no dependency at all
                // on the legacy IdentityProviderType (whose only legitimate agent-side use would itself be a
                // branch-on-provider). Reading a IdentityProviderType constant is caught by the same
                // constant-read condition; depending on the type in any other way is caught explicitly.
                .should(notReadEnumConstantsOf(INTEGRATION_KIND_FQN)
                        .and(notReadEnumConstantsOf(GIT_PROVIDER_TYPE_FQN))
                        .and(notDependOnClass(GIT_PROVIDER_TYPE_FQN)))
                .because("agent/** dispatches on the provider via the SPI registry "
                        + "(Map<IntegrationKind, …Channel> keyed by channel.kind()). Reading a concrete "
                        + "IntegrationKind constant (GITHUB/GITLAB/SLACK) — or touching the legacy "
                        + "IdentityProviderType enum at all — is the branch-on-provider smell; push the "
                        + "behaviour into the per-kind SPI adapter instead.");
        rule.check(classes);
    }

    /**
     * Fails a class that reads any enum constant of the given enum. An enum constant access is a
     * field access whose target is owned by the enum and is a static field of the enum's own type
     * — which excludes {@code .class} literals and use of the enum merely as a generic type
     * parameter.
     */
    private static ArchCondition<JavaClass> notReadEnumConstantsOf(String enumFqn) {
        return new ArchCondition<>("not read an " + enumFqn + " enum constant") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaFieldAccess access : javaClass.getFieldAccessesFromSelf()) {
                    var target = access.getTarget();
                    boolean ownedByEnum = target.getOwner().getFullName().equals(enumFqn);
                    boolean isEnumConstant = target.resolveMember()
                            .map(field -> field.getModifiers().contains(JavaModifier.STATIC)
                                    && field.getRawType().getFullName().equals(enumFqn))
                            // Unresolved member but owned by the enum and same-typed: treat as a constant.
                            .orElse(ownedByEnum
                                    && target.getRawType().getFullName().equals(enumFqn));
                    if (ownedByEnum && isEnumConstant) {
                        events.add(SimpleConditionEvent.violated(
                                javaClass,
                                String.format(
                                        "%s reads enum constant %s.%s at %s — branch-on-provider smell; "
                                                + "dispatch via the SPI registry (Map keyed by channel.kind()) instead",
                                        javaClass.getSimpleName(),
                                        target.getOwner().getSimpleName(),
                                        target.getName(),
                                        access.getSourceCodeLocation())));
                    }
                }
            }
        };
    }

    /**
     * Fails a class that has any direct dependency on the named class — extends/implements, field,
     * parameter, return type, local variable, annotation, or call target. Used to forbid the legacy
     * {@code IdentityProviderType} from agent/** entirely, not just its constant reads.
     */
    private static ArchCondition<JavaClass> notDependOnClass(String fqn) {
        return new ArchCondition<>("not depend on " + fqn) {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    if (dependency.getTargetClass().getFullName().equals(fqn)) {
                        events.add(SimpleConditionEvent.violated(
                                javaClass,
                                String.format(
                                        "%s depends on %s at %s — agent/** must dispatch by IntegrationKind "
                                                + "via the SPI registry, never the legacy IdentityProviderType",
                                        javaClass.getSimpleName(), fqn, dependency.getSourceCodeLocation())));
                    }
                }
            }
        };
    }

    private static void check(ArchRule rule) {
        rule.check(classes);
    }
}
