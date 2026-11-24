package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * A module reads only its own tables, and another module's through that module's interface (ADR 0138). The test reads
 * every SQL string in the production source, whichever API runs it, and the compiled value of each query
 * annotation, its count query included. The tables of a module are the tables of its entities, its join
 * tables, and the tables without an entity that {@link ModulithVerificationTest#NATIVE_TABLES} assigns. An edge that touches a mentoring
 * module has no exception. Main's earlier reads are listed one by one in {@code legacy-native-table-reads.txt}.
 */
class TableOwnershipArchTest extends HephaestusArchitectureTest {
    private static final Path SOURCES = Path.of("src/main/java");

    private static final Pattern KEYWORD =
            Pattern.compile("\\b(?:from|join|into|update|using|table)\\s+", Pattern.CASE_INSENSITIVE);
    private static final String NAME = "(?:\"?[a-z_][a-z0-9_]*\"?\\s*\\.\\s*)?\"?([a-z_][a-z0-9_]*)\"?";
    /** One table of a list, schema-qualified and quoted or not, with its alias and the comma before the next. */
    private static final Pattern LISTED_TABLE = Pattern.compile(
            "\\s*(?:only\\s+)?" + NAME
                    + "(?:\\s+(?:as\\s+)?(?!on\\b|where\\b|set\\b|using\\b)[a-z_][a-z0-9_]*)?(\\s*,)?",
            Pattern.CASE_INSENSITIVE);

    @Test
    void shouldReadNoTableOfAnotherModuleInSql() throws IOException {
        var modules = ModulithVerificationTest.applicationModules();
        var owners = ModulithVerificationTest.tableOwners();
        // A query annotation built from constants is whole only in the compiled class.
        Map<String, List<String>> compiled = new TreeMap<>();
        for (var module : modules) {
            for (JavaClass type : module.getBasePackage().stream().toList())
                compiled.computeIfAbsent(type.getName().replaceAll("\\$.*", ""), outer -> new ArrayList<>())
                        .addAll(annotationSql(type));
        }
        var reads = new ArrayList<String>();
        try (var paths = Files.walk(SOURCES)) {
            for (Path source :
                    paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String type =
                        SOURCES.relativize(source).toString().replace('/', '.').replace(".java", "");
                var module = modules.getModuleByType(type);
                if (module.isEmpty()) continue;
                var inSource = SourceSql.statements(SourceSql.withoutQueryAnnotations(Files.readString(source)));
                reads.addAll(reads(
                        module.get().getIdentifier().toString(),
                        type,
                        sites(inSource, compiled.getOrDefault(type, List.of())),
                        owners));
            }
        }
        var counted = counted(reads);
        assertThat(counted.stream().filter(read -> read.contains(" !mentoring")).toList())
                .as("Read a mentoring module's table only through its interface, and none of another from one")
                .isEmpty();
        LegacyBoundaryBaseline.assertMatches("legacy-native-table-reads", counted);
    }

    @Test
    void shouldFindAReadOfAnotherModulesTableInEveryFormOfSql() {
        var owners =
                Map.of("relationship", "relationship", "conversation_entry", "conversation", "result", "practices");
        String source = """
                class Fixture {
                    @Query(value = "SELECT * FROM relationship WHERE id IN (SELECT 1)", // an open ( in a comment
                           nativeQuery = true)
                    List<Long> annotated();
                    // jdbc.update("DELETE FROM conversation_entry"): a comment runs no SQL.
                    String escaped = \"""
                            ends only here: \\\""" Use @Query(
                            \""";
                    String hint = "Use @Query(";
                    void later() { jdbc.query("SELECT '--' AS separator, x.* FROM relationship x"); }
                    void jdbc() { jdbc.update("DELETE FROM /* rows of a person */ " + "conversation_entry WHERE id = ?", 1); }
                    String block = \"""
                            SELECT 1 FROM result r, relationship x -- a comment
                            JOIN conversation_entry e ON true
                            \""";
                    // Prose that names a table from relationship is no SQL.
                }
                """;
        assertThat(counted(reads(
                        "practices",
                        "Fixture",
                        SourceSql.statements(SourceSql.withoutQueryAnnotations(source)),
                        owners)))
                .as("the annotation is read from the compiled class, not from the source")
                .containsExactly(
                        "practices Fixture reads conversation_entry of conversation x2",
                        "practices Fixture reads relationship of relationship !mentoring x2");
        var compiled = annotationSql(classesWithTests.get(ConstantQueries.class));
        assertThat(counted(reads("practices", "ConstantQueries", compiled, owners)))
                .as("constant-assembled SQL with a comment, a quoted count query, and two annotations with one text")
                .containsExactly(
                        "practices ConstantQueries reads conversation_entry of conversation x2",
                        "practices ConstantQueries reads relationship of relationship !mentoring x5");
    }

    @Test
    void shouldCountEachCompiledAnnotationAndEachOtherSiteOnItsOwn() {
        String sql = "SELECT * FROM conversation_entry WHERE id = 1";
        var owners = Map.of("conversation_entry", "conversation");
        assertThat(counted(reads("practices", "T", sites(List.of(sql), List.of(sql, sql)), owners)))
                .as("a JDBC literal and two annotations with the same text are three sites")
                .containsExactly("practices T reads conversation_entry of conversation x3");
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
                .anySatisfy(detail -> assertThat(detail).contains("RunsNamedSql"))
                .anySatisfy(detail -> assertThat(detail).contains("RunsClientSql"))
                .anySatisfy(detail -> assertThat(detail).contains("RunsTypedNativeQuery"))
                .anySatisfy(detail -> assertThat(detail).contains("RefersToNativeQuery"))
                .anySatisfy(detail -> assertThat(detail).contains("RunsPlainJdbc"))
                .noneSatisfy(detail -> assertThat(detail).contains("QueriesThroughARepository"))
                .noneSatisfy(detail -> assertThat(detail).contains("ConstantQueries"));
        checkedSqlOnly(DescribedPredicate.describe(
                        "mentoring classes", type -> MentoringModules.holds(type.getPackageName())))
                .check(classes);
    }

    /** A call of, or a method reference to, an entity manager's native query, whichever overload. */
    private static final ArchCondition<JavaClass> CREATE_NATIVE_QUERY =
            new ArchCondition<>("create a native query of an entity manager") {
                @Override
                public void check(JavaClass type, ConditionEvents events) {
                    for (JavaCodeUnitAccess<?> access : type.getCodeUnitAccessesFromSelf()) {
                        if (access.getTargetOwner().isAssignableTo(EntityManager.class)
                                && access.getName().equals("createNativeQuery"))
                            // noClasses() inverts the condition: each access that meets it is a violation.
                            events.add(SimpleConditionEvent.satisfied(type, access.getDescription()));
                    }
                }
            };

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
                .dependOnClassesThat()
                .areAssignableTo(Connection.class)
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(Statement.class)
                .orShould()
                .dependOnClassesThat()
                .areAssignableTo(DataSource.class)
                .orShould(CREATE_NATIVE_QUERY)
                .because("a query annotation is SQL that the table ownership test can read whole")
                .allowEmptyShould(true);
    }

    /** The SQL sites of one class: the source statements outside query annotations, and each compiled annotation. */
    private static List<String> sites(List<String> inSource, List<String> compiled) {
        var sites = new ArrayList<>(inSource);
        compiled.stream().map(SourceSql::normalized).forEach(sites::add);
        return sites;
    }

    /** Each read and how often it occurs. */
    private static List<String> counted(List<String> reads) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String read : reads) counts.merge(read, 1, Integer::sum);
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
                if (!query.countQuery().isEmpty()) sql.add(query.countQuery());
            }
            if (method.isAnnotatedWith(Query.class)
                    && method.getAnnotationOfType(Query.class).nativeQuery()) {
                Query query = method.getAnnotationOfType(Query.class);
                sql.add(query.value());
                if (!query.countQuery().isEmpty()) sql.add(query.countQuery());
            }
        }
        return sql.stream().map(SourceSql::normalized).toList();
    }

    @SuppressWarnings({"unused", "UnusedNestedClass", "UnusedMethod"}) // Read by ArchUnit only.
    private interface QueriesThroughARepository extends Repository<Object, Long> {
        @Query(value = "SELECT 1", nativeQuery = true)
        int one();
    }

    private static final String TABLE = "relationship";

    @SuppressWarnings({"unused", "UnusedNestedClass", "UnusedMethod"}) // Read by ArchUnit only.
    private interface ConstantQueries extends Repository<Object, Long> {
        @Query(value = "SELECT * FROM /* rows */ " + TABLE, nativeQuery = true)
        List<Long> rows();

        @Query(
                value = "SELECT * FROM result r",
                countQuery = "SELECT count(*) FROM \"public\".\"" + TABLE + "\" x",
                nativeQuery = true)
        List<Long> page();

        @Query(value = "SELECT * FROM conversation_entry", nativeQuery = true)
        List<Long> entries();

        @Query(value = "SELECT * FROM conversation_entry", nativeQuery = true)
        List<Long> sameEntries();

        @Query(
                value = "SELECT '--' AS separator, r.* FROM relationship r WHERE r.note <> 'FROM conversation_entry'",
                nativeQuery = true)
        List<Long> separated();

        @Query(value = "SELECT $$--$$ AS separator, r.* FROM public . relationship r", nativeQuery = true)
        List<Long> dollarQuoted();

        @Query(value = "SELECT 1 AS \"revision$tag$\" FROM relationship", nativeQuery = true)
        List<Long> dollarInAnIdentifier();
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class RunsNamedSql {
        int one(NamedParameterJdbcTemplate jdbc) {
            Integer one = jdbc.queryForObject("SELECT 1", Map.of(), Integer.class);
            return one == null ? 0 : one;
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class RunsClientSql {
        Object one(JdbcClient jdbc) {
            return jdbc.sql("SELECT 1").query().singleValue();
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class RunsTypedNativeQuery {
        Object one(EntityManager entities) {
            return entities.createNativeQuery("SELECT 1", Long.class).getSingleResult();
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class RefersToNativeQuery {
        Function<String, jakarta.persistence.Query> query(EntityManager entities) {
            return entities::createNativeQuery;
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class RunsPlainJdbc {
        boolean run(Connection connection, String table) throws SQLException {
            try (var statement = connection.createStatement()) {
                return statement.execute("SELECT * FROM " + table);
            }
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class RunsJdbcSql {
        int one(JdbcTemplate jdbc) {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return one == null ? 0 : one;
        }
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
