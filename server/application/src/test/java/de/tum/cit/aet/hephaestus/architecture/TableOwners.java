package de.tum.cit.aet.hephaestus.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinTable;
import jakarta.persistence.Table;
import java.lang.annotation.Annotation;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import org.springframework.modulith.core.ApplicationModules;

/** Supplies the table ownership rules with their shared map. */
final class TableOwners {

    private TableOwners() {}

    /** The owner module of each table that no entity or association maps. */
    static final Map<String, String> NATIVE_TABLES = Map.of(
            "event_publication", "core",
            "shedlock", "core",
            "auth_rate_limit_bucket", "core",
            "account_public_activity_hidden_by_default", "core",
            "workspace_agent_binding", "agent");

    /** Each mapped table and its owner module. */
    static Map<String, String> byTable(ApplicationModules modules) {
        Map<String, String> owners = new TreeMap<>(NATIVE_TABLES);
        for (var module : modules) {
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
}
