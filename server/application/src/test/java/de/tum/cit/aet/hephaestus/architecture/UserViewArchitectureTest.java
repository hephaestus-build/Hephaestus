package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import de.tum.cit.aet.hephaestus.core.UserViewRead;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceScopedController;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * A user view discloses one workspace member's private content to an instance administrator. Every
 * controller mounted under {@code /user-view} is therefore workspace-scoped, instance-admin only and
 * read-only, and every handler addressed at a user carries {@link UserViewRead}, whose advisor records
 * the view from the {@code userId} it finds in the path.
 */
class UserViewArchitectureTest extends HephaestusArchitectureTest {

    @Test
    void everyUserViewControllerIsWorkspaceScopedAndInstanceAdminOnly() {
        List<String> violations = userViewControllers()
                .flatMap(controller -> Stream.concat(
                        unless(
                                controller.isAnnotatedWith(WorkspaceScopedController.class),
                                controller.getSimpleName() + " is not @WorkspaceScopedController"),
                        unless(
                                controller
                                        .tryGetAnnotationOfType(PreAuthorize.class)
                                        .map(PreAuthorize::value)
                                        .filter("hasAuthority('app_admin')"::equals)
                                        .isPresent(),
                                controller.getSimpleName() + " is not instance-admin only")))
                .sorted()
                .toList();
        assertThat(violations).isEmpty();
    }

    @Test
    void everyUserViewHandlerIsAGetAndEveryHandlerAddressedAtAUserIsRecorded() {
        List<String> violations = userViewControllers()
                .flatMap(controller -> controller.getAllMethods().stream())
                .filter(UserViewArchitectureTest::isHandler)
                .flatMap(handler -> Stream.concat(
                        unless(handler.isAnnotatedWith(GetMapping.class), name(handler) + " is not a GET"),
                        unless(
                                isAddressedAtAUser(handler) == handler.isAnnotatedWith(UserViewRead.class),
                                name(handler) + " must carry @UserViewRead exactly when its path names {userId}")))
                .sorted()
                .toList();
        assertThat(violations).isEmpty();
    }

    private static Stream<String> unless(boolean holds, String violation) {
        return holds ? Stream.empty() : Stream.of(violation);
    }

    private static Stream<JavaClass> userViewControllers() {
        return classes.stream().filter(c -> paths(c.reflect()).anyMatch(path -> path.startsWith("/user-view")));
    }

    private static boolean isHandler(JavaMethod method) {
        return AnnotatedElementUtils.hasAnnotation(method.reflect(), RequestMapping.class);
    }

    private static boolean isAddressedAtAUser(JavaMethod handler) {
        return Stream.concat(paths(handler.getOwner().reflect()), paths(handler.reflect()))
                .anyMatch(path -> path.contains("{userId}"));
    }

    private static Stream<String> paths(java.lang.reflect.AnnotatedElement element) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(element, RequestMapping.class);
        return mapping == null ? Stream.empty() : Arrays.stream(mapping.path());
    }

    private static String name(JavaMethod method) {
        return method.getOwner().getSimpleName() + "." + method.getName();
    }
}
