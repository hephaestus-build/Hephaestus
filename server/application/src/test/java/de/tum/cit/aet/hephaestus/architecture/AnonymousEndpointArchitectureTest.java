package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMethod;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

class AnonymousEndpointArchitectureTest extends HephaestusArchitectureTest {
    @Test
    void shouldRejectAnonymousHandlersOutsideTheExplicitAllowlist() {
        var anonymous = classes.stream()
                .flatMap(type -> type.getMethods().stream())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method.reflect(), RequestMapping.class))
                .filter(AnonymousEndpointArchitectureTest::anonymous)
                .map(method -> method.getOwner().getSimpleName() + "." + method.getName())
                .toList();
        assertThat(anonymous).isNotEmpty().allMatch(AnonymousEndpointAllowlist.HANDLERS::contains);
    }

    private static boolean anonymous(JavaMethod method) {
        var authorization = AnnotatedElementUtils.findMergedAnnotation(method.reflect(), PreAuthorize.class);
        if (authorization == null) {
            authorization =
                    AnnotatedElementUtils.findMergedAnnotation(method.getOwner().reflect(), PreAuthorize.class);
        }
        return (authorization != null && authorization.value().contains("permitAll()"))
                || method.isAnnotatedWith(SecurityRequirements.class);
    }
}
