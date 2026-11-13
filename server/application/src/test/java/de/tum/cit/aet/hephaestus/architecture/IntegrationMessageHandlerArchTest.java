package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.IntegrationMessageHandler;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Invariant: every production message handler routes through the unified
 * {@code IntegrationMessageHandlerRegistry}, never a per-kind registry.
 *
 * <ol>
 *   <li>No production class may name the deleted legacy registries — re-introducing one
 *       splits the routing surface into a parallel resolution path.</li>
 *   <li>Every concrete {@code *MessageHandler} under {@code integration/<kind>/} must extend
 *       {@link AbstractIntegrationMessageHandler} or implement {@link IntegrationMessageHandler}
 *       directly; anything else cannot be picked up by the unified registry.</li>
 *   <li>The registry must be non-empty per kind — an empty registry silently routes 100% of
 *       traffic to ACK-as-no-op.</li>
 * </ol>
 */
class IntegrationMessageHandlerArchTest extends HephaestusArchitectureTest {

    /**
     * Names that, if found in production code, indicate the deleted legacy registries are
     * being re-introduced. Matched against both fully-qualified class names and references
     * (extends / implements / type usage).
     */
    private static final List<String> DELETED_LEGACY_NAMES = List.of(
            "de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubMessageHandler",
            "de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubMessageHandlerRegistry",
            "de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabMessageHandler",
            "de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabMessageHandlerRegistry");

    @Test
    void deletedLegacyClassesDoNotReappear() {
        List<String> reintroduced = classes.stream()
                .map(JavaClass::getFullName)
                .filter(DELETED_LEGACY_NAMES::contains)
                .collect(Collectors.toList());

        assertThat(reintroduced)
                .as("Legacy per-kind GitHub/GitLab message-handler registries are deleted; "
                        + "re-introducing them under any of these FQNs splits the routing surface.")
                .isEmpty();
    }

    @Test
    void everyMessageHandlerExtendsUnifiedBaseOrImplementsSpi() {
        // Whitelist: classes that look like handlers by name but aren't inbox event
        // handlers (e.g. ABSTRACT bases, support classes). Add here ONLY if intentional.
        List<String> whitelist = List.of(AbstractIntegrationMessageHandler.class.getName());

        List<String> violations = classes.stream()
                .filter(c -> c.getPackageName().startsWith("de.tum.cit.aet.hephaestus.integration.scm.github."))
                .filter(c -> c.getSimpleName().endsWith("MessageHandler"))
                .filter(c -> !c.getModifiers().contains(JavaModifier.ABSTRACT))
                .filter(c -> !whitelist.contains(c.getFullName()))
                .filter(c -> !bindsToUnifiedSpi(c))
                .map(JavaClass::getFullName)
                .collect(Collectors.toList());

        // Same scan for GitLab.
        violations.addAll(classes.stream()
                .filter(c -> c.getPackageName().startsWith("de.tum.cit.aet.hephaestus.integration.scm.gitlab."))
                .filter(c -> c.getSimpleName().endsWith("MessageHandler"))
                .filter(c -> !c.getModifiers().contains(JavaModifier.ABSTRACT))
                .filter(c -> !whitelist.contains(c.getFullName()))
                .filter(c -> !bindsToUnifiedSpi(c))
                .map(JavaClass::getFullName)
                .toList());

        assertThat(violations)
                .as("Every concrete *MessageHandler class under integration/<kind>/ must extend "
                        + "AbstractIntegrationMessageHandler or implement IntegrationMessageHandler "
                        + "directly. Anything else cannot be picked up by the unified registry and "
                        + "would silently NOT receive messages.")
                .isEmpty();
    }

    @Test
    void everyKindWithMessageHandlersBindsAtLeastOneToTheUnifiedRegistry() {
        long githubBound = countBoundHandlersUnder("de.tum.cit.aet.hephaestus.integration.scm.github.");
        long gitlabBound = countBoundHandlersUnder("de.tum.cit.aet.hephaestus.integration.scm.gitlab.");

        assertThat(githubBound)
                .as("GitHub handlers must bind to the unified registry; zero means the SPI wiring regressed.")
                .isPositive();
        assertThat(gitlabBound)
                .as("GitLab handlers must bind to the unified registry; zero means the SPI wiring regressed.")
                .isPositive();
    }

    private long countBoundHandlersUnder(String packagePrefix) {
        return classes.stream()
                .filter(c -> c.getPackageName().startsWith(packagePrefix))
                .filter(c -> c.getSimpleName().endsWith("MessageHandler"))
                .filter(c -> !c.getModifiers().contains(JavaModifier.ABSTRACT))
                .filter(IntegrationMessageHandlerArchTest::bindsToUnifiedSpi)
                .count();
    }

    private static boolean bindsToUnifiedSpi(JavaClass c) {
        // Direct interface implementation.
        if (c.getAllRawInterfaces().stream()
                .anyMatch(i -> i.getFullName().equals(IntegrationMessageHandler.class.getName()))) {
            return true;
        }
        // Inheritance chain (covers the AbstractIntegrationMessageHandler<T> case, which
        // itself implements IntegrationMessageHandler).
        JavaClass parent = c.getRawSuperclass().orElse(null);
        while (parent != null && !parent.getFullName().equals("java.lang.Object")) {
            if (parent.getFullName().equals(AbstractIntegrationMessageHandler.class.getName())) {
                return true;
            }
            parent = parent.getRawSuperclass().orElse(null);
        }
        return false;
    }
}
