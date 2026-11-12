package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaMethodCall;
import java.nio.file.Files;
import java.nio.file.Path;
import liquibase.Liquibase;
import org.junit.jupiter.api.Test;

/** Master-based rollback tests must not apply the whole future migration tail before their local rollback. */
class MigrationRollbackConventionTest extends HephaestusArchitectureTest {
    @Test
    void masterRollbackTestsMustUseBoundedMigrationUpdates() throws Exception {
        Path sources = Path.of("src/test/java");
        if (!Files.isDirectory(sources)) sources = Path.of("server/application/src/test/java");
        assertThat(sources).isDirectory();
        for (var type : classesWithTests) {
            if (!type.getPackageName().equals("de.tum.cit.aet.hephaestus.core.database")
                    || !type.getSimpleName().endsWith("Test")) continue;
            var calls = type.getMethodCallsFromSelf();
            boolean rollsBack = calls.stream()
                    .anyMatch(call -> (call.getTargetOwner().isEquivalentTo(Liquibase.class)
                                    && call.getTarget().getName().startsWith("rollback"))
                            || call.getTarget().getName().equals("rollbackOnly"));
            if (!rollsBack
                    || !Files.readString(sources.resolve(type.getName().replace('.', '/') + ".java"))
                            .contains("\"db/master.xml\"")) continue;
            assertThat(calls.stream()
                            .filter(MigrationRollbackConventionTest::updatesEntireMaster)
                            .toList())
                    .as(
                            "%s must use PracticeMigrationFixture.upgradeOnly/rollbackOnly for its own changelog",
                            type.getName())
                    .isEmpty();
        }
    }

    private static boolean updatesEntireMaster(JavaMethodCall call) {
        if (!call.getTargetOwner().isEquivalentTo(Liquibase.class)
                || !call.getTarget().getName().equals("update")) return false;
        var parameters = call.getTarget().getRawParameterTypes();
        if (parameters.isEmpty()) return true;
        return !parameters.getFirst().isEquivalentTo(int.class)
                && !(parameters.size() >= 3 && parameters.getFirst().isEquivalentTo(String.class));
    }
}
