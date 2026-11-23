package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
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
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.simple.JdbcClient;

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
        var inSource = new ArrayList<String>();
        try (var paths = Files.walk(SOURCES)) {
            for (Path source :
                    paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String type =
                        SOURCES.relativize(source).toString().replace('/', '.').replace(".java", "");
                var module = modules.getModuleByType(type);
                if (module.isEmpty()) continue;
                inSource.addAll(reads(
                        module.get().getIdentifier().toString(),
                        type,
                        SourceSql.statements(Files.readString(source)),
                        owners));
            }
        }
        // A query annotation built from constants is whole only in the compiled class.
        var compiled = new ArrayList<String>();
        for (var module : modules) {
            for (JavaClass type : module.getBasePackage().stream().toList()) {
                String outer = type.getName().replaceAll("\\$.*", "");
                compiled.addAll(reads(module.getIdentifier().toString(), outer, annotationSql(type), owners));
            }
        }
        var reads = counted(inSource, compiled);
        assertThat(reads.stream().filter(read -> read.contains(" !mentoring")).toList())
                .as("Read a mentoring module's table only through its interface, and none of another from one")
                .isEmpty();
        LegacyBoundaryBaseline.assertMatches("legacy-native-table-reads", reads);
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
                    @Query(value = "SELECT * FROM " + "conversation_entry", nativeQuery = true)
                    List<Long> joined();
                    void jdbc() { jdbc.update("DELETE FROM /* rows of a person */ conversation_entry WHERE id = ?", 1); }
                    String block = \"""
                            SELECT 1 FROM result r, relationship x -- a comment
                            JOIN conversation_entry e ON true
                            \""";
                    // Prose that names a table from relationship is no SQL.
                }
                """;
        assertThat(counted(reads("practices", "Fixture", SourceSql.statements(source), owners), List.of()))
                .containsExactly(
                        "practices Fixture reads conversation_entry of conversation x3",
                        "practices Fixture reads relationship of relationship !mentoring x2");
    }

    @Test
    void shouldCountEachUseSoThatASecondUseOrARemovedUseChangesTheEntry() {
        assertThat(counted(List.of("a reads t of m", "a reads t of m", "b reads u of n"), List.of("b reads u of n")))
                .containsExactly("a reads t of m x2", "b reads u of n x1");
        assertThat(counted(List.of(), List.of("c reads v of o"))).containsExactly("c reads v of o x1");
    }

    @Test
    void shouldLetAMentoringModuleRunSqlOnlyThroughAQueryAnnotation() {
        var fixtures = DescribedPredicate.<JavaClass>describe(
                "SQL entry fixtures", type -> type.getName().startsWith(TableOwnershipArchTest.class.getName() + "$"));
        assertThat(checkedSqlOnly(fixtures)
                        .evaluate(classesWithTests)
                        .getFailureReport()
                        .getDetails())
                .anySatisfy(detail -> assertThat(detail).contains("RunsJdbcSql"))
                .noneSatisfy(detail -> assertThat(detail).contains("QueriesThroughARepository"));
        checkedSqlOnly(DescribedPredicate.describe(
                        "mentoring classes", type -> MentoringModules.holds(type.getPackageName())))
                .check(classes);
    }

    /**
     * A mentoring module runs SQL only in query annotations, whose values this test reads whole. SQL that a
     * {@code JdbcTemplate} or an entity manager runs could be assembled at run time.
     */
    private static ArchRule checkedSqlOnly(DescribedPredicate<JavaClass> subjects) {
        return noClasses()
                .that(subjects)
                .should()
                .dependOnClassesThat()
                .areAssignableTo(JdbcOperations.class)
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(NamedParameterJdbcOperations.class)
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(JdbcClient.class)
                .orShould()
                .callMethod(EntityManager.class, "createNativeQuery", String.class)
                .because("a query annotation is SQL that the table ownership test can read whole")
                .allowEmptyShould(true);
    }

    /** Each read and how often it occurs: in the source or in the compiled annotations, whichever sees more. */
    private static List<String> counted(List<String> inSource, List<String> compiled) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String read : inSource) counts.merge(read, 1, Integer::sum);
        Map<String, Integer> compiledCounts = new TreeMap<>();
        for (String read : compiled) compiledCounts.merge(read, 1, Integer::sum);
        compiledCounts.forEach((read, count) -> counts.merge(read, count, Math::max));
        return counts.entrySet().stream()
                .map(entry -> entry.getKey() + " x" + entry.getValue())
                .toList();
    }

    private static List<String> annotationSql(JavaClass type) {
        var sql = new ArrayList<String>();
        for (JavaMethod method : type.getMethods()) {
            if (method.isAnnotatedWith(NativeQuery.class)) {
                NativeQuery query = method.getAnnotationOfType(NativeQuery.class);
                sql.add(query.value());
                sql.add(query.countQuery());
            }
            if (method.isAnnotatedWith(Query.class)
                    && method.getAnnotationOfType(Query.class).nativeQuery()) {
                Query query = method.getAnnotationOfType(Query.class);
                sql.add(query.value());
                sql.add(query.countQuery());
            }
        }
        return sql;
    }

    @SuppressWarnings({"unused", "UnusedNestedClass", "UnusedMethod"}) // Read by ArchUnit only.
    private interface QueriesThroughARepository extends Repository<Object, Long> {
        @Query(value = "SELECT 1", nativeQuery = true)
        int one();
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class RunsJdbcSql {
        int one(JdbcTemplate jdbc) {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return one == null ? 0 : one;
        }
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
    private static List<String> reads(String module, String type, List<String> statements, Map<String, String> owners) {
        var violations = new ArrayList<String>();
        for (String sql : statements) {
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
