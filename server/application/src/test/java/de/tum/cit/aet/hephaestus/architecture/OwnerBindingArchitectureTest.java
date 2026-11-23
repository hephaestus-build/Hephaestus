package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import java.util.List;
import java.util.function.LongConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

class OwnerBindingArchitectureTest extends HephaestusArchitectureTest {
    @Test
    void shouldReserveJobBindingForAdmittedOperations() {
        assertThat(unauthorizedBindings(classes)).isEmpty();
    }

    @Test
    void shouldDetectCallsAndMethodReferencesFromAnUnmarkedCaller() {
        var fixture = new ClassFileImporter().importClasses(UnmarkedCaller.class, ControllerCaller.class);
        assertThat(unauthorizedBindings(fixture)).hasSize(3);
    }

    private static List<String> unauthorizedBindings(JavaClasses inspected) {
        return inspected.stream()
                .filter(type -> !type.isAnnotatedWith(OwnerScope.StoredOwnerOperation.class)
                        || type.isMetaAnnotatedWith(Controller.class))
                .flatMap(type -> type.getCodeUnitAccessesFromSelf().stream())
                .filter(access -> access.getTargetOwner().isAssignableTo(OwnerScope.class)
                        && access.getName().equals("ofJob"))
                .map(access -> access.getDescription())
                .toList();
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class UnmarkedCaller {
        void changeOwner(OwnerScope owners, long id) {
            owners.ofJob(id);
        }

        LongConsumer changeOwnerLater(OwnerScope owners) {
            return owners::ofJob;
        }
    }

    @TestComponent
    @RestController
    @OwnerScope.StoredOwnerOperation
    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class ControllerCaller {
        void changeOwner(OwnerScope owners, long id) {
            owners.ofJob(id);
        }
    }
}
