package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;

/**
 * A module reads only its own tables, and another module's through that module's interface (ADR 0138). The rule
 * covers every native query on an edge that touches a mentoring module. The tables of a module are the tables of its
 * entities. A {@code JdbcTemplate} statement is not a query annotation, so this test does not see it.
 */
@Tag("architecture")
class TableOwnershipArchTest {
    /** The keyword that names the tables a statement reads or writes. */
    private static final Pattern KEYWORD =
            Pattern.compile("\\b(?:from|join|into|update|using)\\b", Pattern.CASE_INSENSITIVE);

    /** One table of a list after the keyword, quoted or not, with its alias and the comma before the next one. */
    private static final Pattern LISTED_TABLE = Pattern.compile(
            "\\s*(?:only\\s+)?(?:public\\.)?\"?([a-z_][a-z0-9_]*)\"?(?:\\s+(?:as\\s+)?(?!on\\b|where\\b|set\\b)[a-z_][a-z0-9_]*)?(\\s*,)?",
            Pattern.CASE_INSENSITIVE);

    @Test
    void shouldReadNoTableOfAnotherModuleInANativeQuery() {
        var modules = ModulithVerificationTest.applicationModules();
        var queries = new ArrayList<NativeStatement>();
        for (var module : modules) {
            for (JavaClass type : module.getBasePackage().stream().toList()) {
                for (JavaMethod method : type.getMethods()) {
                    String sql = nativeSql(method);
                    if (sql != null)
                        queries.add(new NativeStatement(module.getIdentifier().toString(), method.getFullName(), sql));
                }
            }
        }
        assertThat(violations(tableOwners(), queries))
                .as("Read another module's table through its interface, such as a port in its spi package")
                .isEmpty();
    }

    @Test
    void shouldRefuseANativeQueryOfAnotherModulesTable() {
        var owners = Map.of("relationship", "relationship", "conversation_entry", "conversation");
        assertThat(violations(
                        owners,
                        List.of(
                                new NativeStatement("practices", "a", "SELECT * FROM public.relationship r"),
                                new NativeStatement("relationship", "b", "UPDATE relationship SET version = 1"),
                                new NativeStatement(
                                        "relationship", "c", "DELETE FROM conversation_entry WHERE id = 1"))))
                .containsExactly(
                        "a reads relationship of module relationship",
                        "c reads conversation_entry of module conversation");
    }

    /** Each mapped table and the module of its entity. */
    static Map<String, String> tableOwners() {
        Map<String, String> owners = new TreeMap<>();
        for (var module : ModulithVerificationTest.applicationModules()) {
            for (JavaClass type : module.getBasePackage().stream().toList()) {
                if (!type.isAnnotatedWith(Entity.class)) continue;
                String table = type.isAnnotatedWith(Table.class)
                        ? type.getAnnotationOfType(Table.class).name()
                        : "";
                if (table.isBlank())
                    table = type.getSimpleName()
                            .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                            .toLowerCase(Locale.ROOT);
                // A reserved word such as "user" is quoted in the mapping, and the database names it without quotes.
                owners.put(table.replace("\"", ""), module.getIdentifier().toString());
            }
        }
        return owners;
    }

    private static List<String> violations(Map<String, String> owners, List<NativeStatement> statements) {
        var violations = new ArrayList<String>();
        for (var statement : statements) {
            var keywords = KEYWORD.matcher(statement.sql());
            var listed = LISTED_TABLE.matcher(statement.sql());
            while (keywords.find()) {
                int from = keywords.end();
                boolean more = true;
                while (more && listed.region(from, statement.sql().length()).lookingAt()) {
                    String table = listed.group(1).toLowerCase(Locale.ROOT);
                    String owner = owners.get(table);
                    if (owner != null
                            && !owner.equals(statement.module())
                            && MentoringModules.touches(statement.module(), owner))
                        violations.add(statement.method() + " reads " + table + " of module " + owner);
                    more = listed.group(2) != null;
                    from = listed.end();
                }
            }
        }
        return violations;
    }

    private static @Nullable String nativeSql(JavaMethod method) {
        if (method.isAnnotatedWith(NativeQuery.class))
            return method.getAnnotationOfType(NativeQuery.class).value();
        if (method.isAnnotatedWith(Query.class)) {
            Query query = method.getAnnotationOfType(Query.class);
            return query.nativeQuery() ? query.value() : null;
        }
        return null;
    }

    private record NativeStatement(String module, String method, String sql) {}
}
