package de.tum.cit.aet.hephaestus.architecture;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import de.tum.cit.aet.hephaestus.activity.overview.PublicActivityController;
import de.tum.cit.aet.hephaestus.core.RequiresRecentSignIn;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.webhook.WebhookController;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireWorkspaceOwner;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code MultiTenancyArchitectureTest} lets a controller endpoint go without a {@code WorkspaceContext} parameter only
 * when it or its controller names its workspace scope by exact type, or the rule names the controller by class. A
 * look-alike annotation, parameter type or controller name must scope nothing, so each look-alike fixture below fails.
 */
@Tag("architecture")
class ControllerWorkspaceScopeTest {

    @Retention(RUNTIME)
    @Target(METHOD)
    @interface RequireSomething {}

    @Retention(RUNTIME)
    @Target(TYPE)
    @interface WorkspaceSomething {}

    static final class LookAlikes {
        /** Shares only the simple name of the real {@link WorkspaceContext}. */
        record WorkspaceContext() {}
    }

    @RestController
    static class LookAlikeMethodAnnotation {
        @RequireSomething
        @GetMapping("/fixture")
        public void read() {}
    }

    @RestController
    @WorkspaceSomething
    static class LookAlikeControllerAnnotation {
        @GetMapping("/fixture")
        public void read() {}
    }

    @RestController
    static class OpenApiAnnotationOnly {
        @SecurityRequirements
        @GetMapping("/fixture")
        public void read() {}
    }

    @RestController
    static class RecentSignInOnly {
        @RequiresRecentSignIn
        @GetMapping("/fixture")
        public void read() {}
    }

    @RestController
    static class SomethingStatusController {
        @GetMapping("/fixture")
        public void read() {}
    }

    @RestController
    static class AccountLookAlikeController {
        @GetMapping("/fixture")
        public void read() {}
    }

    @RestController
    static class ExtraWebhookController {
        @PostMapping("/fixture")
        public void receive() {}
    }

    @RestController
    static class Unscoped {
        @GetMapping("/fixture")
        public void read() {}
    }

    @RestController
    static class LookAlikeContextParameter {
        @GetMapping("/fixture")
        public void read(LookAlikes.WorkspaceContext workspace) {}
    }

    @RestController
    @WorkspaceScopedController
    static class ScopedController {
        @GetMapping("/fixture")
        public void read() {}
    }

    @RestController
    static class ContextParameter {
        @GetMapping("/fixture")
        public void read(WorkspaceContext workspace) {}
    }

    @RestController
    static class WorkspaceAdminEndpoint {
        @RequireAtLeastWorkspaceAdmin
        @GetMapping("/fixture")
        public void read() {}
    }

    @RestController
    @RequireWorkspaceOwner
    static class WorkspaceOwnerController {
        @GetMapping("/fixture")
        public void read() {}
    }

    @RestController
    @WorkspaceAgnostic("Fixture for the architecture rule")
    static class AgnosticController {
        @GetMapping("/fixture")
        public void read() {}
    }

    static Stream<Arguments> controllers() {
        return Stream.of(
                Arguments.of(LookAlikeMethodAnnotation.class, false, "a look-alike Require annotation scopes nothing"),
                Arguments.of(LookAlikeControllerAnnotation.class, false, "a look-alike Workspace annotation too"),
                Arguments.of(OpenApiAnnotationOnly.class, false, "an OpenAPI security note scopes nothing"),
                Arguments.of(RecentSignInOnly.class, false, "a recent sign-in names no workspace"),
                Arguments.of(
                        LookAlikeContextParameter.class,
                        false,
                        "a parameter that only shares the name WorkspaceContext"),
                Arguments.of(SomethingStatusController.class, false, "a controller name that holds Status"),
                Arguments.of(AccountLookAlikeController.class, false, "a controller name that holds Account"),
                Arguments.of(ExtraWebhookController.class, false, "a controller name that ends in WebhookController"),
                Arguments.of(Unscoped.class, false, "no scope at all"),
                Arguments.of(ScopedController.class, true, "a workspace-scoped controller"),
                Arguments.of(ContextParameter.class, true, "a WorkspaceContext parameter"),
                Arguments.of(WorkspaceAdminEndpoint.class, true, "a workspace admin gate on the endpoint"),
                Arguments.of(WorkspaceOwnerController.class, true, "a workspace owner gate on the controller"),
                Arguments.of(AgnosticController.class, true, "an explicit, reasoned workspace-agnostic controller"),
                Arguments.of(WebhookController.class, true, "a provider delivery, named by class"),
                Arguments.of(PublicActivityController.class, true, "the anonymous public pages, named by class"));
    }

    @ParameterizedTest(name = "{2}")
    @MethodSource("controllers")
    void acceptsOnlyAnExactWorkspaceScope(Class<?> controller, boolean accepted, String why) {
        var result =
                MultiTenancyArchitectureTest.ControllerWorkspaceContextTests.dataEndpointsReceiveWorkspaceContextRule()
                        .evaluate(new ClassFileImporter().importClasses(controller));

        assertThat(result.hasViolation()).as(why).isEqualTo(!accepted);
    }
}
