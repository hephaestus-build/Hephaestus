package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinTable;
import jakarta.persistence.Table;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * A module reads only its own tables, and another module's through that module's interface (ADR 0138). The test reads
 * every SQL string in the production source, whichever API runs it: a query annotation, its count query, a
 * {@code JdbcTemplate} call or an entity manager. The tables of a module are the tables of its entities, its join
 * tables, and the tables without an entity that {@link #NATIVE_TABLES} assigns. An edge that touches a mentoring
 * module has no exception. Main's earlier reads are listed one by one in {@code legacy-native-table-reads.txt}.
 */
class TableOwnershipArchTest extends HephaestusArchitectureTest {
    private static final Path SOURCES = Path.of("src/main/java");

    /** The owner module of each table that no entity or association maps. */
    static final Map<String, String> NATIVE_TABLES = Map.of(
            "event_publication", "core",
            "shedlock", "core",
            "auth_rate_limit_bucket", "core",
            "account_public_activity_hidden_by_default", "core",
            "workspace_agent_binding", "agent");

    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("\"((?:\\\\.|[^\"\\\\\\n])*)\"");
    private static final Pattern KEYWORD =
            Pattern.compile("\\b(?:from|join|into|update|using|table)\\s+", Pattern.CASE_INSENSITIVE);
    private static final String NAME = "(?:\"?[a-z_][a-z0-9_]*\"?\\.)?\"?([a-z_][a-z0-9_]*)\"?";
    /** One table of a list, schema-qualified and quoted or not, with its alias and the comma before the next. */
    private static final Pattern LISTED_TABLE = Pattern.compile(
            "\\s*(?:only\\s+)?" + NAME
                    + "(?:\\s+(?:as\\s+)?(?!on\\b|where\\b|set\\b|using\\b)[a-z_][a-z0-9_]*)?(\\s*,)?",
            Pattern.CASE_INSENSITIVE);

    @Test
    void shouldReadNoTableOfAnotherModuleInSql() throws IOException {
        var modules = ModulithVerificationTest.applicationModules();
        var owners = tableOwners();
        var violations = new ArrayList<String>();
        try (var paths = Files.walk(SOURCES)) {
            for (Path source :
                    paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String type =
                        SOURCES.relativize(source).toString().replace('/', '.').replace(".java", "");
                var module = modules.getModuleByType(type);
                if (module.isEmpty()) continue;
                violations.addAll(
                        reads(module.get().getIdentifier().toString(), type, Files.readString(source), owners));
            }
        }
        assertThat(violations.stream()
                        .filter(read -> read.contains(" !mentoring"))
                        .toList())
                .as("Read a mentoring module's table only through its interface, and none of another from one")
                .isEmpty();
        LegacyBoundaryBaseline.assertMatches("legacy-native-table-reads", violations);
    }

    @Test
    void shouldFindAReadOfAnotherModulesTableInEveryFormOfSql() {
        var owners =
                Map.of("relationship", "relationship", "conversation_entry", "conversation", "result", "practices");
        String source = """
                class Fixture {
                    @Query(value = "SELECT * FROM result r", countQuery = "SELECT count(*) FROM \\"public\\".\\"relationship\\" x",
                           nativeQuery = true)
                    List<Long> page();
                    void jdbc() { jdbc.update("DELETE FROM conversation_entry WHERE id = ?", 1); }
                    String block = \"""
                            SELECT 1 FROM result r, relationship x JOIN conversation_entry e ON true
                            \""";
                    // Prose that names a table from relationship is no SQL.
                }
                """;
        assertThat(reads("practices", "Fixture", source, owners))
                .containsExactlyInAnyOrder(
                        "practices Fixture reads relationship of relationship !mentoring",
                        "practices Fixture reads conversation_entry of conversation",
                        "practices Fixture reads relationship of relationship !mentoring",
                        "practices Fixture reads conversation_entry of conversation");
    }

    /** Each mapped table and its owner module. */
    static Map<String, String> tableOwners() {
        Map<String, String> owners = new TreeMap<>(NATIVE_TABLES);
        for (var module : ModulithVerificationTest.applicationModules()) {
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

    /** The reads of other modules' tables in one source file, from its string literals and text blocks. */
    private static List<String> reads(String module, String type, String source, Map<String, String> owners) {
        var literals = new ArrayList<String>();
        Matcher blocks = TEXT_BLOCK.matcher(source);
        while (blocks.find()) literals.add(blocks.group(1));
        Matcher strings = STRING.matcher(TEXT_BLOCK.matcher(source).replaceAll(""));
        while (strings.find()) literals.add(strings.group(1).replace("\\\"", "\""));
        var violations = new ArrayList<String>();
        for (String sql : literals) {
            Matcher keywords = KEYWORD.matcher(sql);
            Matcher listed = LISTED_TABLE.matcher(sql);
            while (keywords.find()) {
                int from = keywords.end();
                boolean more = true;
                while (more && listed.region(from, sql.length()).lookingAt()) {
                    String table = listed.group(1).toLowerCase(Locale.ROOT);
                    String owner = owners.get(table);
                    if (owner != null && !owner.equals(module))
                        violations.add(module + " " + type + " reads " + table + " of " + owner
                                + (MentoringModules.touches(module, owner) ? " !mentoring" : ""));
                    more = listed.group(2) != null;
                    from = listed.end();
                }
            }
        }
        return violations;
    }
}
