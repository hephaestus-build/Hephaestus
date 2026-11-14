package de.tum.cit.aet.hephaestus.workspace.context;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.slack.channel.SlackChannelAdminController;
import de.tum.cit.aet.hephaestus.testconfig.ApplicationClasses;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Each route under {@code /workspaces/} whose first segment is a fixed word is a registry route, and
 * {@link WorkspaceContextFilter#REGISTRY_SEGMENTS} names exactly those words. A new registry route that the set misses
 * would be read as a workspace slug.
 */
@Tag("architecture")
class WorkspaceRegistryRoutesTest {

    @Test
    void shouldNameEveryFixedFirstSegmentUnderWorkspacesAsARegistrySegment() {
        Set<String> fixed = new TreeSet<>();
        for (Class<?> controller : controllers()) {
            for (String path : paths(controller)) {
                String rest = path.startsWith("/workspaces/") ? path.substring("/workspaces/".length()) : null;
                if (rest == null || rest.isEmpty()) continue;
                String segment = rest.split("/", 2)[0];
                if (!segment.startsWith("{")) fixed.add(segment);
            }
        }

        assertThat(fixed).containsExactlyInAnyOrderElementsOf(WorkspaceContextFilter.REGISTRY_SEGMENTS);
    }

    private static List<Class<?>> controllers() {
        List<Class<?>> types = ApplicationClasses.annotatedWith(Controller.class);
        assertThat(types)
                .as("controllers found on the classpath, also one that a property turns off")
                .contains(SlackChannelAdminController.class);
        return types;
    }

    private static Set<String> paths(Class<?> controller) {
        String[] prefixes = mappingPaths(AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class));
        Set<String> paths = new TreeSet<>();
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(controller)) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) continue;
            for (String prefix : prefixes) {
                for (String suffix : mappingPaths(mapping)) {
                    paths.add(join(prefix, suffix));
                }
            }
        }
        return paths;
    }

    private static String join(String prefix, String suffix) {
        String head = prefix.isEmpty() || prefix.startsWith("/") ? prefix : "/" + prefix;
        return suffix.isEmpty() || suffix.startsWith("/") ? head + suffix : head + "/" + suffix;
    }

    private static String[] mappingPaths(@Nullable RequestMapping mapping) {
        if (mapping == null) return new String[] {""};
        String[] paths = Stream.concat(Arrays.stream(mapping.path()), Arrays.stream(mapping.value()))
                .distinct()
                .toArray(String[]::new);
        return paths.length == 0 ? new String[] {""} : paths;
    }
}
