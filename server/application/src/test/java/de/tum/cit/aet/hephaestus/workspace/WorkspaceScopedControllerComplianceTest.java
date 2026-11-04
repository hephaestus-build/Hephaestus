package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.Application;
import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelAdminController;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Every handler of a {@link WorkspaceScopedController} takes the {@link WorkspaceContext} it serves. The rule reads
 * only the compiled classes, so it runs with the architecture tests, in pull request CI and before a push. It checks
 * every such controller of the application, also one whose bean a runtime role or a condition leaves out.
 */
@Tag("architecture")
class WorkspaceScopedControllerComplianceTest {

    private static final URL MAIN_CLASSES =
            Application.class.getProtectionDomain().getCodeSource().getLocation();

    /**
     * Handlers whose answer does not depend on which workspace asked, so the rule has nothing to bind to.
     * They still sit under the workspace path and still authorize through it: WorkspaceContextFilter matches
     * the URL and @RequireAtLeastWorkspaceAdmin reads the resolved context from the holder, neither of which
     * involves the handler's signature. Named one at a time so an exception is a reviewable diff — a
     * controller that instead dropped @WorkspaceScopedController would take this rule's protection off
     * itself silently, and off every route that copied the trick.
     */
    private static final Set<String> WORKSPACE_INDEPENDENT_ANSWERS = Set.of(
            // The instance-wide "may a workspace connect its own AI provider" switch. There is no per-workspace
            // form of it to consult: WorkspaceLlmConnectionService gates on the instance value alone.
            "de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmSettingsController#get");

    @Test
    void workspaceScopedHandlersDeclareWorkspaceContextParameter() {
        List<String> violations = new ArrayList<>();
        Set<String> unusedExemptions = new LinkedHashSet<>(WORKSPACE_INDEPENDENT_ANSWERS);

        controllers().forEach(type -> inspectController(type, violations, unusedExemptions));

        assertThat(violations)
                .describedAs("Workspace scoped handler methods must include a WorkspaceContext parameter")
                .isEmpty();
        assertThat(unusedExemptions)
                .describedAs("Stale exemption: the handler now takes a WorkspaceContext, or no longer exists")
                .isEmpty();
    }

    /** The application's own classes that carry {@link WorkspaceScopedController}, directly or as a meta-annotation. */
    private static List<Class<?>> controllers() {
        var annotated = new AnnotationTypeFilter(WorkspaceScopedController.class);
        // Overridden rather than filtered: the default check also drops classes whose conditions this bare
        // environment turns off, such as the Slack and Outline controllers.
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
                return annotated.match(reader, getMetadataReaderFactory());
            }
        };
        List<Class<?>> types = scanner.findCandidateComponents(Application.class.getPackageName()).stream()
                .<Class<?>>map(candidate ->
                        ClassUtils.resolveClassName(Objects.requireNonNull(candidate.getBeanClassName()), null))
                .filter(type -> MAIN_CLASSES.equals(
                        type.getProtectionDomain().getCodeSource().getLocation()))
                .toList();
        assertThat(types)
                .as("workspace scoped controllers found on the classpath, also one that a property turns off")
                .contains(SlackChannelAdminController.class);
        return types;
    }

    private void inspectController(Class<?> type, List<String> violations, Set<String> unusedExemptions) {
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(type)) {
            if (!isRequestMappingMethod(method) || hasWorkspaceContextParameter(method)) {
                continue;
            }
            String handler = type.getName() + "#" + method.getName();
            if (!unusedExemptions.remove(handler)) {
                violations.add(handler);
            }
        }
    }

    private boolean hasWorkspaceContextParameter(Method method) {
        return Arrays.stream(method.getParameterTypes()).anyMatch(WorkspaceContext.class::isAssignableFrom);
    }

    private boolean isRequestMappingMethod(Method method) {
        return AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class);
    }
}
