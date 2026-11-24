package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import de.tum.cit.aet.hephaestus.Application;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinTable;
import jakarta.persistence.Table;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

@Tag("architecture")
class ModulithVerificationTest {

    // Vendor DTO architecture tests guard generated transport models separately from application modules. They stay
    // out of the import itself: Modulith ignores classes through a view of its import, which would keep every
    // generated class in the one architecture JVM.
    private static final List<String> VENDOR_MODELS = List.of(
            "/integration/scm/github/graphql/model/",
            "/integration/scm/gitlab/graphql/model/",
            "/integration/outline/client/model/");

    private static final ApplicationModules MODULES = ApplicationModules.of(
            Application.class,
            location -> ImportOption.Predefined.DO_NOT_INCLUDE_TESTS.includes(location)
                    && VENDOR_MODELS.stream().noneMatch(location::contains));

    /** The owner module of each table that no entity or association maps. */
    static final Map<String, String> NATIVE_TABLES = Map.of(
            "event_publication", "core",
            "shedlock", "core",
            "auth_rate_limit_bucket", "core",
            "account_public_activity_hidden_by_default", "core",
            "workspace_agent_binding", "agent");

    @Test
    void modulesAreCycleFreeAndRespectNamedInterfaces() {
        MODULES.verify();
    }

    /**
     * Verification refuses a code dependency that a module does not declare. This rule refuses the declaration itself
     * when it points up the layers of the mentoring modules.
     */
    @Test
    void shouldDeclareNoDependencyUpTheMentoringLayers() {
        assertThat(layerViolations(declaredDependencies())).isEmpty();
    }

    @Test
    void shouldRefuseADependencyOfPracticesOnMentoring() {
        assertThat(layerViolations(Map.of("practices", List.of("core", "mentoring::spi"))))
                .containsExactly("practices -> mentoring::spi");
        assertThat(layerViolations(Map.of("mentoring", List.of("relationship", "practices"))))
                .isEmpty();
    }

    @Test
    void shouldRefuseADependencyOfAMentoringModuleOnItsRuntime() {
        assertThat(layerViolations(
                        Map.of("relationship", List.of("core", "agent::spi"), "agent", List.of("relationship"))))
                .containsExactly("relationship -> agent::spi");
    }

    static ApplicationModules applicationModules() {
        return MODULES;
    }

    /** Each mapped table and its owner module. The database tier only needs the Modulith import. */
    static Map<String, String> tableOwners() {
        Map<String, String> owners = new TreeMap<>(NATIVE_TABLES);
        for (var module : MODULES) {
            String name = module.getIdentifier().toString();
            for (JavaClass type : module.getBasePackage().stream().toList()) {
                if (type.isAnnotatedWith(Entity.class)) owners.put(table(type), name);
                for (JavaField field : type.getFields())
                    associationTable(field, JoinTable.class, JoinTable::name)
                            .ifPresent(table -> owners.put(table, name));
                for (JavaField field : type.getFields())
                    associationTable(field, CollectionTable.class, CollectionTable::name)
                            .ifPresent(table -> owners.put(table, name));
                for (JavaMethod method : type.getMethods()) {
                    if (method.isAnnotatedWith(JoinTable.class))
                        owners.put(
                                unquoted(method.getAnnotationOfType(JoinTable.class)
                                        .name()),
                                name);
                }
            }
        }
        owners.remove("");
        return owners;
    }

    private static <A extends Annotation> Optional<String> associationTable(
            JavaField field, Class<A> annotation, Function<A, String> name) {
        return field.isAnnotatedWith(annotation)
                ? Optional.of(unquoted(name.apply(field.getAnnotationOfType(annotation))))
                : Optional.empty();
    }

    private static String table(JavaClass entity) {
        String table = entity.isAnnotatedWith(Table.class)
                ? entity.getAnnotationOfType(Table.class).name()
                : "";
        // A reserved word such as "user" is quoted in the mapping, and the database names it without quotes.
        if (!table.isBlank()) return unquoted(table);
        return entity.getSimpleName().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static String unquoted(String name) {
        return name.replace("\"", "").toLowerCase(Locale.ROOT);
    }

    /** Each module's declared dependencies, as {@code module} or {@code module::interface}. */
    static Map<String, List<String>> declaredDependencies() {
        Map<String, List<String>> declared = new TreeMap<>();
        for (var module : MODULES) {
            declared.put(
                    module.getIdentifier().toString(),
                    module.getBasePackage()
                            .getAnnotation(ApplicationModule.class)
                            .map(declaration -> List.of(declaration.allowedDependencies()))
                            .orElse(List.of()));
        }
        return declared;
    }

    /** The module that a declared dependency names: {@code mentoring::spi} names {@code mentoring}. */
    static String moduleOf(String dependency) {
        int named = dependency.indexOf("::");
        return named < 0 ? dependency : dependency.substring(0, named);
    }

    private static List<String> layerViolations(Map<String, List<String>> declared) {
        var violations = new ArrayList<String>();
        declared.forEach((module, dependencies) -> dependencies.stream()
                .filter(dependency -> !MentoringModules.mayDependOn(module, moduleOf(dependency)))
                .forEach(dependency -> violations.add(module + " -> " + dependency)));
        return violations;
    }
}
