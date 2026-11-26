package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Each {@link WorkspaceAgnostic} takes an element out of a tenancy check. On a method it also lets that method's SQL
 * reach every workspace. The exact count below shrinks when a bypass goes. A pull request may raise it only together
 * with the annotations that need it, each with a reason that names the bound that replaces the workspace. Count type
 * and method annotations alike: a type annotation still hides the type from the architecture rules.
 */
class WorkspaceAgnosticRatchetTest extends HephaestusArchitectureTest {

    private static final int BYPASSES = 574;

    @Test
    void shouldKeepTheExactCountOfWorkspaceAgnosticBypasses() {
        List<String> bypasses = bypasses();

        assertThat(bypasses.size())
                .as(
                        "@WorkspaceAgnostic count. Lower BYPASSES when a bypass goes. Raise it only in the commit that"
                                + " adds the bypasses, each with its reason. Current bypasses:%n%s",
                        String.join("\n", bypasses))
                .isEqualTo(BYPASSES);
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
