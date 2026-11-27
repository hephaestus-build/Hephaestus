package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Each {@link WorkspaceAgnostic} takes an element out of a tenancy check. On a method it also lets that method's SQL
 * reach every workspace. {@code src/test/resources/architecture/workspace-agnostic-bypasses.txt} names every bypass,
 * one per line: a type by its name, a method by its full name. A pull request adds a line only together with the
 * annotation that needs it, with a reason that names the bound that replaces the workspace, and removes the line of a
 * bypass that went. Two pull requests that add different bypasses add different lines, so they merge without a
 * conflict and the list stays exact. Type and method annotations count alike: a type annotation still hides the type
 * from the architecture rules.
 */
class WorkspaceAgnosticRatchetTest extends HephaestusArchitectureTest {

    private static final Path LIST = Path.of("src/test/resources/architecture/workspace-agnostic-bypasses.txt");

    @Test
    void shouldListEveryWorkspaceAgnosticBypass() throws IOException {
        var actual = new TreeSet<>(bypasses());
        var listed = new TreeSet<String>();
        for (String line : Files.readAllLines(LIST)) if (!line.isBlank() && !line.startsWith("#")) listed.add(line.strip());
        var unlisted = new TreeSet<>(actual);
        unlisted.removeAll(listed);
        var stale = new TreeSet<>(listed);
        stale.removeAll(actual);

        assertThat(Map.of("unlisted", unlisted, "stale", stale))
                .as(
                        "@WorkspaceAgnostic bypasses against %s. Add the line of a new bypass in the commit that adds it,"
                                + " with its reason, and remove the line of one that went. Current bypasses:%n%s",
                        LIST.getFileName(),
                        String.join("\n", actual))
                .isEqualTo(Map.of("unlisted", Set.of(), "stale", Set.of()));
    }

    @Test
    void shouldGiveEachBypassAReason() {
        List<String> unexplained = new ArrayList<>();
        for (JavaClass type : classes) {
            type.tryGetAnnotationOfType(WorkspaceAgnostic.class)
                    .filter(annotation -> annotation.value().isBlank())
                    .ifPresent(annotation -> unexplained.add(type.getName()));
            for (JavaMethod method : sourceMethods(type)) {
                method.tryGetAnnotationOfType(WorkspaceAgnostic.class)
                        .filter(annotation -> annotation.value().isBlank())
                        .ifPresent(annotation -> unexplained.add(method.getFullName()));
            }
        }

        assertThat(unexplained).as("@WorkspaceAgnostic without a reason").isEmpty();
    }

    private static List<String> bypasses() {
        List<String> bypasses = new ArrayList<>();
        for (JavaClass type : classes) {
            if (type.isAnnotatedWith(WorkspaceAgnostic.class)) bypasses.add(type.getName());
            for (JavaMethod method : sourceMethods(type)) {
                if (method.isAnnotatedWith(WorkspaceAgnostic.class)) bypasses.add(method.getFullName());
            }
        }
        bypasses.sort(String::compareTo);
        return bypasses;
    }

    /** javac copies a method's annotations onto the bridge methods it generates for it; count the source method once. */
    private static List<JavaMethod> sourceMethods(JavaClass type) {
        return type.getMethods().stream()
                .filter(method -> !method.getModifiers().contains(JavaModifier.BRIDGE))
                .toList();
    }
}
