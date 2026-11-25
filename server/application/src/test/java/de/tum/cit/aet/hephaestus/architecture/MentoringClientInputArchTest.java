package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import de.tum.cit.aet.hephaestus.core.web.ClientInputs;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

class MentoringClientInputArchTest extends HephaestusArchitectureTest {

    @Test
    void shouldApplyEveryConversationCreateThroughTheKeyStore() {
        assertThat(unrecordedCreates(classes)).isEmpty();
    }

    @Test
    void shouldFindAHeaderThatDoesNotGuardTheOperation() {
        var fixture = new ClassFileImporter().importClasses(BookmarkController.class);
        assertThat(unrecordedCreates(fixture)).singleElement().asString().contains("bookmark");
    }

    private static List<String> unrecordedCreates(JavaClasses types) {
        return types.stream()
                .filter(type -> type.isAnnotatedWith(RequestMapping.class))
                .filter(type -> {
                    var route = type.getAnnotationOfType(RequestMapping.class);
                    return Stream.concat(Arrays.stream(route.value()), Arrays.stream(route.path()))
                            .anyMatch(path -> {
                                String absolute = path.startsWith("/") ? path : "/" + path;
                                return absolute.equals("/conversation") || absolute.startsWith("/conversation/");
                            });
                })
                .flatMap(type -> type.getMethods().stream())
                .filter(method -> method.isAnnotatedWith(PostMapping.class)
                        || method.tryGetAnnotationOfType(RequestMapping.class).stream()
                                .anyMatch(mapping ->
                                        Arrays.asList(mapping.method()).contains(RequestMethod.POST)))
                .filter(method -> method.getMethodCallsFromSelf().stream()
                        .noneMatch(call -> call.getTargetOwner().isEquivalentTo(ClientInputs.class)
                                && call.getName().equals("create")))
                .map(method -> method.getFullName())
                .toList();
    }

    @RequestMapping("conversation")
    static class BookmarkController {
        @RequestMapping(path = "/bookmarks", method = RequestMethod.POST)
        public UUID bookmark(@RequestHeader(ClientInputs.HEADER) UUID key) {
            return key;
        }
    }
}
