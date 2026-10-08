package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.AdminAccess;
import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminPasskeyScopeArchitectureTest extends HephaestusArchitectureTest {
    @Test
    void shouldRequireAssuranceScopeForAdministrativeRoleDeclarations() {
        var declarations = classes.stream()
                .map(c -> c.reflect())
                .flatMap(c -> Stream.concat(Stream.of(c), Arrays.stream(c.getDeclaredMethods())))
                .filter(element -> element.isAnnotationPresent(PreAuthorize.class))
                .filter(AdminPasskeyScopeArchitectureTest::administrative)
                .toList();
        assertThat(declarations).isNotEmpty();
        assertThat(declarations).allSatisfy(element -> {
            String expression = Objects.requireNonNull(element.getAnnotation(PreAuthorize.class))
                    .value();
            AdminAccess.Scope expected =
                    expression.contains("app_admin") ? AdminAccess.Scope.INSTANCE : AdminAccess.Scope.WORKSPACE;
            AdminAccess scope = AnnotatedElementUtils.findMergedAnnotation(element, AdminAccess.class);
            assertThat(scope)
                    .as("Administrative authorization must identify its assurance scope: %s", element)
                    .isNotNull();
            assertThat(Objects.requireNonNull(scope).value()).isEqualTo(expected);
        });
    }

    private static boolean administrative(AnnotatedElement element) {
        String expression = Objects.requireNonNull(element.getAnnotation(PreAuthorize.class))
                .value();
        return expression.contains("app_admin")
                || expression.contains("workspaceSecure.isAdmin(")
                || expression.contains("workspaceSecure.isOwner(")
                || expression.contains("workspaceSecure.canManageRole(");
    }
}
