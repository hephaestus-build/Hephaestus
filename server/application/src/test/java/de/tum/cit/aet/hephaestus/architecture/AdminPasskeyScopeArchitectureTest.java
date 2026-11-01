package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import de.tum.cit.aet.hephaestus.core.AdminAccess;
import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireWorkspaceOwner;
import java.lang.reflect.AnnotatedElement;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;

class AdminPasskeyScopeArchitectureTest extends HephaestusArchitectureTest {

    // Administrative access is declared only through the composed annotations, which carry their
    // assurance scope. A direct declaration must be one of these exact non-administrative
    // expressions, so the rule never interprets an expression.
    private static final Set<String> NON_ADMINISTRATIVE_EXPRESSIONS = Set.of(
            "permitAll()",
            "isAuthenticated()",
            "@workspaceSecure.isMember()",
            "@workspaceSecure.isMemberWithoutElevation()",
            "isAuthenticated() && @workspaceSecure.isMember()");

    @Test
    void shouldCarryAssuranceScopeWhenAnAnnotationComposesAdministrativeAccess() {
        assertThat(scope(RequireInstanceAdmin.class)).isEqualTo(AdminAccess.Scope.INSTANCE);
        assertThat(scope(RequireAtLeastWorkspaceAdmin.class)).isEqualTo(AdminAccess.Scope.WORKSPACE);
        assertThat(scope(RequireWorkspaceOwner.class)).isEqualTo(AdminAccess.Scope.WORKSPACE);
    }

    @Test
    void shouldUseScopedAnnotationsWhenAHandlerDeclaresAuthorizationDirectly() {
        Map<String, String> unscoped = new TreeMap<>();
        classes.stream()
                .map(JavaClass::reflect)
                .filter(type -> !type.isAnnotation())
                .flatMap(type ->
                        Stream.concat(Stream.<AnnotatedElement>of(type), Arrays.stream(type.getDeclaredMethods())))
                .filter(element -> element.isAnnotationPresent(PreAuthorize.class))
                .filter(element -> AnnotatedElementUtils.findMergedAnnotation(element, AdminAccess.class) == null)
                .forEach(element -> unscoped.put(
                        element.toString(),
                        Objects.requireNonNull(element.getAnnotation(PreAuthorize.class))
                                .value()));

        assertThat(unscoped).isNotEmpty();
        assertThat(unscoped)
                .as("Declare administrative access with @RequireInstanceAdmin, @RequireAtLeastWorkspaceAdmin"
                        + " or @RequireWorkspaceOwner")
                .allSatisfy((element, expression) ->
                        assertThat(NON_ADMINISTRATIVE_EXPRESSIONS).contains(expression));
    }

    private static AdminAccess.Scope scope(Class<?> annotation) {
        AdminAccess access = AnnotatedElementUtils.findMergedAnnotation(annotation, AdminAccess.class);
        assertThat(access).as("%s must declare its assurance scope", annotation).isNotNull();
        return Objects.requireNonNull(access).value();
    }
}
