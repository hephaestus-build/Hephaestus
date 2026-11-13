package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.workspace.authorization.RequireAtLeastWorkspaceAdmin;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fixtures for {@link AuditByDefaultArchTest}'s endpoint detection, which can fail silently — an
 * endpoint the rule cannot see looks exactly like a compliant one.
 */
@Tag("architecture")
class AuditByDefaultGateDetectionTest {

    @RestController
    @RequireAtLeastWorkspaceAdmin
    static class ClassGatedController {

        @PostMapping
        public void create() {}
    }

    @RestController
    static class MethodGatedController {

        @RequireAtLeastWorkspaceAdmin
        @PatchMapping
        public void update() {}
    }

    @RestController
    @PreAuthorize("hasAuthority('app_admin')")
    static class InstanceAdminGatedController {

        @PostMapping
        public void create() {}
    }

    @RestController
    static class UngatedController {

        @PostMapping
        public void create() {}
    }

    @RestController
    @RequireAtLeastWorkspaceAdmin
    static class ReadOnlyController {

        public void list() {}
    }

    interface MutationPort {
        void update(ConfigAuditEntry entry);

        void update();
    }

    static class AuditedMutation implements MutationPort {
        private final ConfigAuditPort audit;

        AuditedMutation(ConfigAuditPort audit) {
            this.audit = audit;
        }

        @Override
        public void update(ConfigAuditEntry entry) {
            audit.record(entry);
        }

        @Override
        public void update() {}
    }

    interface MissingPort {
        void update(ConfigAuditEntry entry);
    }

    static class PortCaller {
        private final MutationPort mutations;
        private final MissingPort missing;

        PortCaller(MutationPort mutations, MissingPort missing) {
            this.mutations = mutations;
            this.missing = missing;
        }

        public void audited(ConfigAuditEntry entry) {
            mutations.update(entry);
        }

        public void unrecorded() {
            mutations.update();
        }

        public void missing(ConfigAuditEntry entry) {
            missing.update(entry);
        }
    }

    private static final JavaClasses FIXTURES = new ClassFileImporter()
            .importClasses(
                    ClassGatedController.class,
                    MethodGatedController.class,
                    InstanceAdminGatedController.class,
                    UngatedController.class,
                    ReadOnlyController.class,
                    MutationPort.class,
                    AuditedMutation.class,
                    MissingPort.class,
                    PortCaller.class);

    static Stream<Arguments> endpoints() {
        return Stream.of(
                Arguments.of(
                        ClassGatedController.class, "create", true, "gate declared on the controller, not the method"),
                Arguments.of(MethodGatedController.class, "update", true, "gate declared on the method"),
                Arguments.of(InstanceAdminGatedController.class, "create", true, "instance-admin authority gate"),
                Arguments.of(
                        UngatedController.class,
                        "create",
                        false,
                        "a public endpoint is not an admin action and must not be forced to declare one"),
                Arguments.of(
                        ReadOnlyController.class,
                        "list",
                        false,
                        "reading changes nothing, so there is nothing to record"));
    }

    @ParameterizedTest(name = "{3}")
    @MethodSource("endpoints")
    void detectsAdminMutations(Class<?> controller, String method, boolean expected, String why) {
        assertThat(isAdminMutation(controller, method)).as(why).isEqualTo(expected);
    }

    @Test
    void followsOnlyTheMatchingSpiImplementationToItsAuditRecorder() {
        var caller = FIXTURES.get(PortCaller.class);
        assertThat(AuditByDefaultArchTest.reachesRecorder(caller.getMethod("audited", ConfigAuditEntry.class)))
                .isTrue();
        assertThat(AuditByDefaultArchTest.reachesRecorder(caller.getMethod("unrecorded")))
                .isFalse();
        assertThat(AuditByDefaultArchTest.reachesRecorder(caller.getMethod("missing", ConfigAuditEntry.class)))
                .isFalse();
    }

    private static boolean isAdminMutation(Class<?> controller, String method) {
        var javaClass = FIXTURES.get(controller);
        if (!AuditByDefaultArchTest.isController(javaClass)) {
            return false;
        }
        var javaMethod = javaClass.getMethod(method);
        return AuditByDefaultArchTest.isMutation(javaMethod) && AuditByDefaultArchTest.isAdminGated(javaMethod);
    }
}
