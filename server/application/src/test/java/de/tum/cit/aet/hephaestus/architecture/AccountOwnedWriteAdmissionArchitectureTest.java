package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExport;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportRepository;
import de.tum.cit.aet.hephaestus.core.auth.export.AccountExportWriter;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonAct;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataWriteFence;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned;
import jakarta.persistence.Table;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.CriteriaUpdate;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
    private static final String PERSONAL_GOAL_TABLE = "personal_goal";
    private static final Set<String> NON_WRITING_REPOSITORY_METHODS = Set.of(
            "findByIdAndAccountId", "findStatus", "tryLockGeneration", "existsByAccountIdAndStatusIn", "lockSources");

    private static final Pattern EXPORT_DML = Pattern.compile(
            "\\b(?:insert into|update|merge into|delete from|truncate(?: table)?) (?:only )?"
                    + "(?:\"?public\"?\\s*\\.\\s*)?\"?(?:account_export|AccountExport)\"?(?![\\w$])",
            Pattern.CASE_INSENSITIVE);
    private static final Path PRODUCTION_SOURCES = Path.of("src/main/java");
    private static final String ERASURE_REPOSITORY = "de.tum.cit.aet.hephaestus.core.auth.AccountErasureRepository";

    @Test
    void shouldEnforceAdmissionForEveryEnrolledAccountOwnedStore() {
        var tables = classes.stream()
                .filter(type -> type.isAnnotatedWith(PersonOwned.class))
                .map(type -> type.getAnnotationOfType(Table.class).name())
                .toList();
        assertThat(tables)
                .as("Enroll each @PersonOwned store in this rule when it enters the person data inventory")
                .containsExactlyInAnyOrder(EXPORT_TABLE, PERSONAL_GOAL_TABLE);
        assertThat(classes.stream()
                        .filter(type -> type.isAnnotatedWith(PersonDataStores.class))
                        .flatMap(type -> Arrays.stream(
                                type.getAnnotationOfType(PersonDataStores.class).value())))
                .contains(EXPORT_TABLE)
                .doesNotContain(PERSONAL_GOAL_TABLE);
    }

    @Test
    void shouldAdmitPersonalGoalWritesUnderTheAccountLifecycleFence() {
        var repository = classes.get("de.tum.cit.aet.hephaestus.account.PersonalGoalRepository");
        assertThat(classes.stream()
                        .filter(type -> type.isAssignableTo(Repository.class))
                        .filter(type -> type.getDirectDependenciesFromSelf().stream()
                                .anyMatch(dependency -> dependency
                                        .getTargetClass()
                                        .getName()
                                        .equals("de.tum.cit.aet.hephaestus.account.PersonalGoal"))))
                .containsExactly(repository);

        var goals = classes.get("de.tum.cit.aet.hephaestus.account.PersonalGoals");
        var admitWrite = goals.getMethod("admitWrite", PersonAct.class);
        assertThat(admitWrite.getMethodCallsFromSelf())
                .anyMatch(call -> call.getTargetOwner().isEquivalentTo(OwnerScope.class)
                        && call.getName().equals("fromPrincipal"))
                .anyMatch(call -> call.getTargetOwner().isEquivalentTo(PersonDataWriteFence.class)
                        && call.getName().equals("holdForAccountRecordWrite"));
        assertThat(goals.getMethod("save", PersonAct.class, long.class, String.class).getMethodCallsFromSelf())
                .anyMatch(call -> call.getName().equals("admitWrite"));
        assertThat(goals.getMethod("forget", PersonAct.class, long.class).getMethodCallsFromSelf())
                .anyMatch(call -> call.getName().equals("admitWrite"));
    }

    @Test
    void shouldKeepExportWritesBehindLifecycleAdmission() {
        assertThat(classes.stream()
                        .filter(type -> type.isAssignableTo(Repository.class))
                        .filter(AccountOwnedWriteAdmissionArchitectureTest::referencesExport))
                .as("Use the guarded export writer instead of adding another export repository")
                .containsExactly(classes.get(AccountExportRepository.class));

        assertThat(unadmittedExportAccesses(classes.stream()))
                .as("Use AccountExportWriter for persistent export writes")
                .isEmpty();

        JavaClass writer = classes.get(AccountExportWriter.class);
        assertThat(writer.getMethod("createPending", long.class).getMethodCallsFromSelf())
                .anyMatch(call ->
                        call.getTargetOwner().equals(writer) && call.getName().equals("admit"));
        assertThat(writer.getMethod("finish", Long.class, Long.class, Supplier.class, String.class)
                        .getMethodCallsFromSelf())
                .anyMatch(call ->
                        call.getTargetOwner().equals(writer) && call.getName().equals("admit"));
        assertThat(writer.getMethod("admit", long.class).getMethodCallsFromSelf())
                .anyMatch(call -> call.getTargetOwner().isEquivalentTo(AccountRepository.class)
                        && call.getName().equals("lockStatusForShare"));
    }

    private static boolean referencesExport(JavaClass type) {
        return type.getDirectDependenciesFromSelf().stream()
                .anyMatch(dependency -> dependency.getTargetClass().isEquivalentTo(AccountExport.class));
    }

    private static Stream<String> unadmittedExportAccesses(Stream<JavaClass> types) {
        return types.flatMap(type -> type.getCodeUnitAccessesFromSelf().stream())
                .filter(access -> !allowedExportAccess(access))
                .map(JavaCodeUnitAccess::getDescription);
    }

    private static boolean allowedExportAccess(JavaCodeUnitAccess<?> access) {
        boolean repository = access.getTargetOwner().isAssignableTo(AccountExportRepository.class);
        boolean entity = access.getTargetOwner().isAssignableTo(AccountExport.class);
        var target = access.getTarget();
        // A class that names the export entity may build or execute a bulk mutation only at admission.
        boolean criteria = (isBulkCriteria(access.getTargetOwner())
                        || isBulkCriteria(target.getRawReturnType())
                        || target.getRawParameterTypes().stream()
                                .anyMatch(AccountOwnedWriteAdmissionArchitectureTest::isBulkCriteria))
                && referencesExport(access.getOriginOwner());
        if (!repository && !entity && !criteria) {
            return true;
        }
        var writer = classes.get(AccountExportWriter.class);
        var origin = access.getOrigin();
        boolean create = origin.equals(writer.getMethod("createPending", long.class));
        boolean finish =
                origin.equals(writer.getMethod("finish", Long.class, Long.class, Supplier.class, String.class));
        boolean fail = origin.equals(writer.getMethod("fail", AccountExport.class, String.class));
        String operation = access.getName();

        if (criteria) {
            return create || finish;
        }
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

    private static boolean isBulkCriteria(JavaClass type) {
        return type.isAssignableTo(CriteriaUpdate.class) || type.isAssignableTo(CriteriaDelete.class);
    }

    @Test
    void shouldRejectExportCriteriaMutations() {
        var fixture = classesWithTests.get(AccountOwnedWriteAdmissionFixtures.CriteriaWrites.class);
        assertThat(unadmittedExportAccesses(Stream.of(fixture)))
                .anySatisfy(detail -> assertThat(detail).contains("rewritePayload", "createCriteriaUpdate"))
                .anySatisfy(detail -> assertThat(detail).contains("rewritePayload", "createQuery"))
                .anySatisfy(detail -> assertThat(detail).contains("deleteExports", "createCriteriaDelete"))
                .anySatisfy(detail -> assertThat(detail).contains("deleteExports", "createQuery"))
                .noneSatisfy(detail -> assertThat(detail).contains("readExports"));
    }

    @Test
    void shouldReserveDirectExportDmlForErasure() throws IOException {
        var erasure = classes.get(ERASURE_REPOSITORY).getMethod("deleteExports", Long.class);
        assertThat(exportDml(SourceSql.annotationSql(erasure, false)))
                .as("Account erasure may delete exports after locking their account")
                .containsExactly(
                        "WITH locked AS MATERIALIZED ( SELECT id FROM account_export WHERE account_id = :accountId"
                                + " ORDER BY id FOR UPDATE ) DELETE FROM account_export WHERE id IN (SELECT id FROM locked)");
        assertThat(exportQueryViolations(classes.stream()
                        .flatMap(type -> type.getMethods().stream())
                        .filter(method -> !method.equals(erasure))))
                .as("Use AccountExportWriter for modifying queries on exports")
                .isEmpty();

        try (var files = Files.walk(PRODUCTION_SOURCES)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                var inSource = SourceSql.statements(SourceSql.withoutQueryAnnotations(Files.readString(file)));
                assertThat(exportDml(inSource))
                        .as("Direct export DML in %s", file)
                        .isEmpty();
            }
        }
    }

    @Test
    void shouldRejectCompiledExportQueriesAssembledFromConstants() throws IOException {
        var fixture = classesWithTests.get(AccountOwnedWriteAdmissionFixtures.ConstantQueries.class);
        var source = Path.of("src/test/java")
                .resolve(AccountOwnedWriteAdmissionFixtures.class.getName().replace('.', '/') + ".java");
        assertThat(exportDml(SourceSql.statements(Files.readString(source))))
                .as("The old source-only scan misses all three statements")
                .isEmpty();
        String method = fixture.getName() + ".";
        assertThat(exportQueryViolations(fixture.getMethods().stream()))
                .containsExactlyInAnyOrder(
                        method + "nativeUpdate() runs UPDATE account_export SET payload = NULL",
                        method + "nativeDelete() runs DELETE FROM account_export",
                        method + "jpqlUpdate(java.lang.String) runs UPDATE AccountExport e SET e.status = :status");
    }

    private static Stream<String> exportQueryViolations(Stream<JavaMethod> methods) {
        return methods.flatMap(method -> exportDml(SourceSql.annotationSql(method, false)).stream()
                .map(sql -> method.getFullName() + " runs " + sql));
    }

    private static List<String> exportDml(List<String> statements) {
        return statements.stream().filter(sql -> EXPORT_DML.matcher(sql).find()).toList();
    }
}
