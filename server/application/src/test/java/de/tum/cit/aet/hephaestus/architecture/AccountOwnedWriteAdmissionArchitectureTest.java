package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExport;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportRepository;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportWriter;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned;
import jakarta.persistence.Table;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

/**
 * The HTTP command and the durable worker can add export data only through the writer.
 * AccountExportDurabilityIntegrationTest covers the lock order and transaction rollback (#5088).
 */
class AccountOwnedWriteAdmissionArchitectureTest extends HephaestusArchitectureTest {
    private static final Set<JavaCodeUnit> EXPORT_ENTITY_MUTATORS =
            classes.get(AccountExport.class).getFieldAccessesFromSelf().stream()
                    .filter(access -> access.getAccessType() == JavaFieldAccess.AccessType.SET)
                    .filter(access -> access.getTargetOwner().isEquivalentTo(AccountExport.class))
                    .map(JavaFieldAccess::getOrigin)
                    .collect(Collectors.toUnmodifiableSet());
    private static final String EXPORT_TABLE = "account_export";
    private static final Set<String> NON_WRITING_REPOSITORY_METHODS =
            Set.of("findByIdAndAccountId", "findStatus", "tryLockGeneration", "existsByAccountIdAndStatusIn");

    private static final Pattern EXPORT_DML = Pattern.compile(
            "\\b(?:insert into|update|merge into|delete from|truncate(?: table)?) (?:only )?"
                    + "(?:\"?public\"?\\s*\\.\\s*)?\"?(?:account_export|AccountExport)\"?(?![\\w$])",
            Pattern.CASE_INSENSITIVE);
    private static final Path PRODUCTION_SOURCES = Path.of("src/main/java");
    private static final Path ERASURE_REPOSITORY =
            PRODUCTION_SOURCES.resolve("de/tum/cit/aet/hephaestus/core/auth/AccountErasureRepository.java");

    @Test
    void shouldEnforceAdmissionForEveryEnrolledAccountOwnedStore() {
        var tables = classes.stream()
                .filter(type -> type.isAnnotatedWith(PersonOwned.class))
                .map(type -> type.getAnnotationOfType(Table.class).name())
                .toList();
        assertThat(tables)
                .as("Enroll each @PersonOwned store in this rule when it enters the person data inventory")
                .containsExactly(EXPORT_TABLE);
        assertThat(classes.stream()
                        .filter(type -> type.isAnnotatedWith(PersonDataStores.class))
                        .flatMap(type -> Arrays.stream(
                                type.getAnnotationOfType(PersonDataStores.class).value())))
                .contains(EXPORT_TABLE);
    }

    @Test
    void shouldKeepExportWritesBehindLifecycleAdmission() {
        assertThat(classes.stream()
                        .filter(type -> type.isAssignableTo(Repository.class))
                        .filter(type -> type.getDirectDependenciesFromSelf().stream()
                                .anyMatch(dependency ->
                                        dependency.getTargetClass().isEquivalentTo(AccountExport.class))))
                .as("Use the guarded export writer instead of adding another export repository")
                .containsExactly(classes.get(AccountExportRepository.class));

        assertThat(classes.stream()
                        .flatMap(type -> type.getCodeUnitAccessesFromSelf().stream())
                        .filter(access -> !allowedExportAccess(access))
                        .map(JavaCodeUnitAccess::getDescription))
                .as("Use AccountExportWriter for persistent export writes")
                .isEmpty();

        JavaClass writer = classes.get(AccountExportWriter.class);
        assertThat(writer.getMethod("createPending", long.class).getMethodCallsFromSelf())
                .anyMatch(call ->
                        call.getTargetOwner().equals(writer) && call.getName().equals("admit"));
        assertThat(writer.getMethod("finish", Long.class, Long.class, byte[].class, String.class)
                        .getMethodCallsFromSelf())
                .anyMatch(call ->
                        call.getTargetOwner().equals(writer) && call.getName().equals("admit"));
        assertThat(writer.getMethod("admit", long.class).getMethodCallsFromSelf())
                .anyMatch(call -> call.getTargetOwner().isEquivalentTo(AccountRepository.class)
                        && call.getName().equals("lockStatusForShare"));
    }

    private static boolean allowedExportAccess(JavaCodeUnitAccess<?> access) {
        boolean repository = access.getTargetOwner().isAssignableTo(AccountExportRepository.class);
        boolean entity = access.getTargetOwner().isAssignableTo(AccountExport.class);
        if (!repository && !entity) {
            return true;
        }
        var writer = classes.get(AccountExportWriter.class);
        var origin = access.getOrigin();
        boolean create = origin.equals(writer.getMethod("createPending", long.class));
        boolean finish = origin.equals(writer.getMethod("finish", Long.class, Long.class, byte[].class, String.class));
        boolean fail = origin.equals(writer.getMethod("fail", AccountExport.class, String.class));
        String operation = access.getName();

        if (repository) {
            return switch (operation) {
                case "saveAndFlush" -> create;
                case "lockByIdAndAccountId" -> finish;
                case "save" -> finish || fail;
                // Retention only expires existing rows and clears their payloads.
                case "expireReadyBefore" -> true;
                default -> NON_WRITING_REPOSITORY_METHODS.contains(operation);
            };
        }
        return switch (operation) {
            case "<init>" -> create;
            case "markFailed" -> fail; // Clears data from the locked row.
            // JPA can flush a changed managed entity, even without a repository save.
            default ->
                finish || access.getTarget().resolveMember().stream().noneMatch(EXPORT_ENTITY_MUTATORS::contains);
        };
    }

    @Test
    void shouldReserveDirectExportDmlForErasure() throws IOException {
        try (var files = Files.walk(PRODUCTION_SOURCES)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var writes = SourceSql.statements(Files.readString(file)).stream()
                        .filter(sql -> EXPORT_DML.matcher(sql).find())
                        .toList();
                if (file.equals(ERASURE_REPOSITORY)) {
                    assertThat(writes)
                            .as("Account erasure may delete exports after locking their account")
                            .containsExactly("DELETE FROM account_export WHERE account_id = :accountId");
                } else {
                    assertThat(writes).as("Direct export DML in %s", file).isEmpty();
                }
            }
        }
    }
}
