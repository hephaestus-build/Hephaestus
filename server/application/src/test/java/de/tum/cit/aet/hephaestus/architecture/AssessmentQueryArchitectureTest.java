package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import de.tum.cit.aet.hephaestus.conversation.AssessmentSqlFixture;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.practices.spi.RawObservationAccess;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.StringEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AssessmentQueryArchitectureTest extends HephaestusArchitectureTest {
    static final DescribedPredicate<JavaClass> CONSUMER = resideInAnyPackage(MentoringModules.PACKAGES)
            .or(resideInAnyPackage("..conversation.."))
            .or(assignableTo(ContentSource.class));

    private static final Pattern READ = Pattern.compile("\\b(?:from|join|table|update)\\b", Pattern.CASE_INSENSITIVE);
    private static final String SQL_NAME = "\"?[a-z_][a-z0-9_]*\"?";
    private static final String QUALIFIED_NAME = "(?:" + SQL_NAME + "\\s*\\.\\s*)*" + SQL_NAME;
    private static final Pattern RELATION = Pattern.compile(
            "\\s*(?:\\(\\s*)*+(?!(?:select|with|values|lateral)\\b)"
                    + "(?:only\\s*\\(\\s*(?<wrapped>" + QUALIFIED_NAME + ")\\s*\\)"
                    + "|(?:(?:only|fetch)\\s+)?(?<table>" + QUALIFIED_NAME + "))"
                    + "(?:\\s+(?:as\\s+)?(?!(?:on|where|join|left|right|full|inner|cross|natural|outer"
                    + "|group|order|union|having|limit|offset|fetch|for|using|set|returning|intersect|except)\\b)"
                    + "(?<alias>" + SQL_NAME + "))?\\s*(?<comma>,)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_START = Pattern.compile(
            "\\A[\\s(]*(?:select|with|from|table|insert|update|delete|merge)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*", Pattern.CASE_INSENSITIVE);
    private static final String QUOTED_IDENTIFIER = "\"[^\"]*+(?:\"\"[^\"]*+)*+\"";
    private static final Pattern SQL_TOKEN =
            Pattern.compile(QUOTED_IDENTIFIER + "|" + IDENTIFIER.pattern() + "|\\S", Pattern.CASE_INSENSITIVE);
    private static final Pattern AS_DECLARATION = Pattern.compile(
            "'(?:[^']|'')*'|" + QUOTED_IDENTIFIER + "|\\b(as\\s+(?:" + QUOTED_IDENTIFIER + "|" + IDENTIFIER.pattern()
                    + "))",
            Pattern.CASE_INSENSITIVE);
    private static final Map<JavaClass, List<String>> LITERAL_QUERIES = new ConcurrentHashMap<>();

    private static final Map<String, ObservationColumns> OBSERVATION_RELATIONS = classes.stream()
            .filter(type -> type.isAnnotatedWith(RawObservationAccess.class) && type.isAnnotatedWith(Entity.class))
            .flatMap(type -> relationNames(type).map(name -> Map.entry(name, observationColumns(type))))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue, ObservationColumns::merge));

    private record ObservationColumns(Set<String> fields, Set<String> metadata) {
        private static final ObservationColumns NONE = new ObservationColumns(Set.of(), Set.of());

        ObservationColumns merge(ObservationColumns other) {
            var combined =
                    Stream.concat(fields.stream(), other.fields.stream()).collect(Collectors.toUnmodifiableSet());
            var shared = metadata.stream().filter(other.metadata()::contains).collect(Collectors.toUnmodifiableSet());
            return new ObservationColumns(combined, shared);
        }

        boolean isRestricted(String name) {
            return fields.contains(name) && !metadata.contains(name);
        }
    }

    private record QueryBlock(
            int projection, int depth, @Nullable QueryBlock parent, Map<String, ObservationColumns> bindings) {
        @Nullable
        ObservationColumns columnsOf(String alias) {
            var own = bindings.get(alias);
            return own != null || parent == null ? own : parent.columnsOf(alias);
        }

        boolean hasColumn(String name) {
            return bindings.values().stream().anyMatch(columns -> columns.isRestricted(name))
                    || (parent != null && parent.hasColumn(name));
        }
    }

    private static Stream<String> relationNames(JavaClass type) {
        return Stream.of(
                        type.getSimpleName(),
                        type.getAnnotationOfType(Entity.class).name(),
                        type.isAnnotatedWith(Table.class)
                                ? type.getAnnotationOfType(Table.class).name()
                                : "")
                .filter(name -> !name.isBlank())
                .map(name -> name.replace("\"", "").toLowerCase(Locale.ROOT));
    }

    private static ObservationColumns observationColumns(JavaClass type) {
        var fields = type.getFields().stream()
                .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
                .filter(field -> !field.isAnnotatedWith(Transient.class))
                .toList();
        return new ObservationColumns(
                fields.stream()
                        .flatMap(AssessmentQueryArchitectureTest::columnNames)
                        .collect(Collectors.toUnmodifiableSet()),
                fields.stream()
                        .filter(field -> field.isAnnotatedWith(RawObservationAccess.Metadata.class))
                        .flatMap(AssessmentQueryArchitectureTest::columnNames)
                        .collect(Collectors.toUnmodifiableSet()));
    }

    private static Stream<String> columnNames(JavaField field) {
        return Stream.of(
                        field.getName(),
                        field.isAnnotatedWith(Column.class)
                                ? field.getAnnotationOfType(Column.class).name()
                                : "")
                .filter(name -> !name.isBlank())
                .map(name -> name.replace("\"", "").toLowerCase(Locale.ROOT));
    }

    @Test
    void shouldReadNoObservationTableFromAMentoringQuery() {
        assertThat(queriesFrom(classes.stream())).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "review_job,capture",
        "problem,workspace_id",
        "result,severity",
        "result,explanation",
        "result,evidence",
        "result,example",
        "result,coverage",
        "person_evidence_copy,payload",
        "review_attempt_input,content",
        "review_job_script_run,stop_reason",
        "review_job_script_run,calls",
        "review_job_script_run,error",
        "review,report",
        "review,inventory_receipt",
        "review_practice,reason",
        "review_practice,capture_decision",
        "review_item,result_id",
        "review_item,reason",
        "review_item,detail"
    })
    void shouldRejectRawFieldsAndRelations(String table, String field) {
        assertThat(readsObservations("SELECT t." + field + " FROM " + table + " t"))
                .isTrue();
    }

    @Test
    void shouldRejectAConversationQueryThatReadsObservationTextDirectly() {
        var fixture = new ClassFileImporter()
                .importClasses(
                        AssessmentSqlFixture.class,
                        AssessmentSqlFixture.HistoryRow.class,
                        AssessmentSqlFixture.NestedQueries.class);
        assertThat(queriesFrom(fixture.stream()))
                .hasSize(40)
                .anySatisfy(query -> assertThat(query).contains("summaries("))
                .anySatisfy(query -> assertThat(query).contains("summariesByEntity("))
                .anySatisfy(query -> assertThat(query).contains("outputByEntity("))
                .anySatisfy(query -> assertThat(query).contains("aliasedJob("))
                .anySatisfy(query -> assertThat(query).contains("implicitlyAliasedResult("))
                .anySatisfy(query -> assertThat(query).contains("castJob("))
                .anySatisfy(query -> assertThat(query).contains("hasNegativeOutcome("))
                .anySatisfy(query -> assertThat(query).contains("jsonOutput("))
                .anySatisfy(query -> assertThat(query).contains("wholeJob("))
                .anySatisfy(query -> assertThat(query).contains("wholeJobAfterAll("))
                .anySatisfy(query -> assertThat(query).contains("wholeJobInCommaJoin("))
                .anySatisfy(query -> assertThat(query).contains("outputAfterQuotedComment("))
                .anySatisfy(query -> assertThat(query).contains("outputFromOnlyRelation("))
                .anySatisfy(query -> assertThat(query).contains("outputFromDerivedRelation("))
                .anySatisfy(query -> assertThat(query).contains("jobTable("))
                .anySatisfy(query -> assertThat(query).contains("groupedJoinOutput("))
                .anySatisfy(query -> assertThat(query).contains("distinctWholeJob("))
                .anySatisfy(query -> assertThat(query).contains("mixedWholeJob("))
                .anySatisfy(query -> assertThat(query).contains("countObservations("))
                .anySatisfy(query -> assertThat(query).contains("countNestedObservations("))
                .anySatisfy(query -> assertThat(query).contains("observationExists("))
                .anySatisfy(query -> assertThat(query).contains("observationPractice("))
                .anySatisfy(query -> assertThat(query).contains("jobExitCode("))
                .anySatisfy(query -> assertThat(query).contains("aggregatedJob("))
                .anySatisfy(query -> assertThat(query).contains("captureFailureText("))
                .anySatisfy(query -> assertThat(query).contains("endReasons("))
                .anySatisfy(query -> assertThat(query).contains("sourceKinds("))
                .anySatisfy(query -> assertThat(query).contains("recordedStopReason("))
                .anySatisfy(query -> assertThat(query).contains("arrayOfJobs("))
                .anySatisfy(query -> assertThat(query).contains("conditionalJob("))
                .anySatisfy(query -> assertThat(query).contains("stagedHistory("))
                .anySatisfy(query -> assertThat(query).contains("schemaQualifiedOutput("))
                .anySatisfy(query -> assertThat(query).contains("outputProperty("))
                .anySatisfy(query -> assertThat(query).contains("correlatedOutput("))
                .anySatisfy(query -> assertThat(query).contains("resultIds("))
                .anySatisfy(query -> assertThat(query).contains("markReasons("))
                .anySatisfy(query -> assertThat(query).contains("INSERT INTO", "j.output"))
                .anySatisfy(query -> assertThat(query).contains("HistoryRow.summary"))
                .anySatisfy(query -> assertThat(query).contains("NestedQueries"))
                .anySatisfy(query -> assertThat(query).contains("j.output AS stored_output", "review_job"))
                .noneSatisfy(query -> assertThat(query).contains("workspaceAliasedAsOutput("))
                .noneSatisfy(query -> assertThat(query).contains("workspaceAliasedAsQualifier("))
                .noneSatisfy(query -> assertThat(query).contains("workspaceWithLiteralLabel("))
                .noneSatisfy(query -> assertThat(query).contains("workspaceInCommaJoin("))
                .noneSatisfy(query -> assertThat(query).contains("workspaceFromOnlyRelation("))
                .noneSatisfy(query -> assertThat(query).contains("workspaceFromDerivedRelation("))
                .noneSatisfy(query -> assertThat(query).contains("workspaceWithUnrelatedWildcard("))
                .noneSatisfy(query -> assertThat(query).contains("workspaceWithUnrelatedOutput("))
                .noneSatisfy(query -> assertThat(query).contains("workspaceWithShadowedAlias("))
                .noneSatisfy(query -> assertThat(query).contains("workspaceWithOutputParameter("))
                .noneSatisfy(query -> assertThat(query).contains("countQueuedJobs("))
                .noneSatisfy(query -> assertThat(query).contains("SELECT j.workspace_id FROM review_job"));
    }

    private static List<String> queriesFrom(Stream<JavaClass> scope) {
        return scope.filter(CONSUMER)
                .flatMap(type -> Stream.concat(
                        annotatedQueriesFrom(type),
                        literals(type).stream().map(query -> type.getName() + ": " + query)))
                .toList();
    }

    private static Stream<String> annotatedQueriesFrom(JavaClass type) {
        var types = Stream.of(type)
                .filter(AssessmentQueryArchitectureTest::readsObservationQuery)
                .map(JavaClass::getName);
        var members = type.getMembers().stream()
                .filter(AssessmentQueryArchitectureTest::readsObservationQuery)
                .map(JavaMember::getFullName);
        return Stream.concat(types, members);
    }

    static boolean readsObservationQuery(HasAnnotations<?> declaration) {
        return declaration.getAnnotations().stream().anyMatch(AssessmentQueryArchitectureTest::includesObservationSql);
    }

    private static boolean includesObservationSql(Object value) {
        return switch (value) {
            case String sql -> readsObservations(sql);
            case JavaAnnotation<?> nested ->
                nested.getProperties().values().stream()
                        .anyMatch(AssessmentQueryArchitectureTest::includesObservationSql);
            case Object[] values ->
                Arrays.stream(values).anyMatch(AssessmentQueryArchitectureTest::includesObservationSql);
            default -> false;
        };
    }

    static boolean containsObservationSql(JavaClass type) {
        return type.getPackageName().startsWith(BASE_PACKAGE + ".")
                && (!literals(type).isEmpty()
                        || readsObservationQuery(type)
                        || type.getFields().stream().anyMatch(AssessmentQueryArchitectureTest::readsObservationQuery));
    }

    private static List<String> literals(JavaClass type) {
        return type.getSource().isEmpty()
                ? List.of()
                : LITERAL_QUERIES.computeIfAbsent(type, AssessmentQueryArchitectureTest::literalQueriesFrom);
    }

    private static List<String> literalQueriesFrom(JavaClass type) {
        var source = type.getSource().orElseThrow().getUri();
        List<String> literals = new ArrayList<>();
        try (var bytes = source.toURL().openStream()) {
            for (var entry : ClassFile.of().parse(bytes.readAllBytes()).constantPool()) {
                if (entry instanceof StringEntry literal) literals.add(literal.stringValue());
            }
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return literals.stream()
                .map(SourceSql::normalized)
                .filter(value -> QUERY_START.matcher(value).find())
                .filter(AssessmentQueryArchitectureTest::readsObservations)
                .toList();
    }

    private static boolean readsObservations(String query) {
        query = SourceSql.normalized(query);
        var matches = SQL_TOKEN.matcher(query).results().toList();
        var tokens = matches.stream()
                .map(token -> token.group().toLowerCase(Locale.ROOT))
                .toList();
        boolean implicitSelect = tokens.stream()
                .filter(token -> !token.equals("("))
                .findFirst()
                .filter("from"::equals)
                .isPresent();
        // Parentheses inherit their scope. A SELECT creates a query block with its own relations.
        var blocks = new ArrayDeque<QueryBlock>();
        blocks.push(new QueryBlock(-1, 1, null, new HashMap<>()));
        var blockAt = new HashMap<Integer, QueryBlock>();
        for (int index = 0; index < tokens.size(); index++) {
            switch (tokens.get(index)) {
                case "(" -> blocks.push(blocks.element());
                case ")" -> {
                    if (blocks.size() > 1) blocks.pop();
                }
                case "select" -> {
                    int depth = blocks.size();
                    var inherited = blocks.pop();
                    blocks.push(new QueryBlock(
                            index,
                            depth,
                            inherited.depth() == depth ? inherited.parent() : inherited,
                            new HashMap<>()));
                }
                default -> {}
            }
            blockAt.put(matches.get(index).start(), blocks.element());
        }
        // Erase declared labels and relation names. A remaining bare alias denotes the whole row in an expression.
        var expressions = AS_DECLARATION
                .matcher(query)
                .replaceAll(match -> match.group(1) == null
                        ? Matcher.quoteReplacement(match.group())
                        : " ".repeat(match.group().length()))
                .toCharArray();
        var reads = READ.matcher(query);
        var relation = RELATION.matcher(query);
        boolean protectedRelation = false;
        while (reads.find()) {
            var block = blockAt.get(reads.start());
            if (block == null) continue;
            int start = reads.end();
            while (relation.region(start, query.length()).lookingAt()) {
                String qualified = relation.group("wrapped");
                if (qualified == null) qualified = relation.group("table");
                String name = sqlName(qualified.substring(qualified.lastIndexOf('.') + 1));
                String alias = relation.group("alias");
                var columns = OBSERVATION_RELATIONS.get(name);
                if (columns != null) {
                    protectedRelation = true;
                    int select = block.projection();
                    if (reads.group().equalsIgnoreCase("table")
                            || columns.metadata().isEmpty()
                            || (select < 0 && implicitSelect)
                            || (select >= 0 && hasWildcardProjection(tokens, select + 1))) return true;
                }
                block.bindings()
                        .put(
                                alias == null ? name : sqlName(alias),
                                columns == null ? ObservationColumns.NONE : columns);
                int end = relation.group("comma") == null ? relation.end() : relation.start("comma");
                Arrays.fill(expressions, start, end, ' ');
                if (relation.group("comma") == null) break;
                start = relation.end();
            }
        }
        return protectedRelation && readsBoundFields(new String(expressions), blockAt);
    }

    private static boolean readsBoundFields(String expressions, Map<Integer, QueryBlock> blockAt) {
        var matches = SQL_TOKEN.matcher(expressions).results().toList();
        var tokens = matches.stream().map(token -> sqlName(token.group())).toList();
        for (int index = 0; index < tokens.size(); index++) {
            String token = tokens.get(index);
            var block = blockAt.get(matches.get(index).start());
            if (block == null) continue;
            var columns = block.columnsOf(token);
            boolean protectedAlias = columns != null && !columns.metadata().isEmpty();
            if (follows(tokens, index, ".")) {
                if (columns != null
                        && protectedAlias
                        && index + 2 < tokens.size()
                        && !columns.metadata().contains(tokens.get(index + 2))) {
                    return true;
                }
            } else if ((index == 0
                            || (!tokens.get(index - 1).equals(".")
                                    && !tokens.get(index - 1).equals(":")))
                    && (protectedAlias || block.hasColumn(token))) {
                return true;
            }
        }
        return false;
    }

    private static String sqlName(String spelling) {
        return spelling.strip().replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private static boolean follows(List<String> tokens, int index, String next) {
        return index + 1 < tokens.size() && tokens.get(index + 1).equals(next);
    }

    private static boolean hasWildcardProjection(List<String> tokens, int start) {
        if (tokens.get(start).equals("distinct")) {
            start++;
            if (start + 1 < tokens.size()
                    && tokens.get(start).equals("on")
                    && tokens.get(start + 1).equals("(")) {
                start = afterGroup(tokens, start + 1);
            }
        } else if (tokens.get(start).equals("all")) {
            start++;
        }
        for (int index = start; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.equals("from") || token.equals(")")) return false;
            if (token.equals("*") && (index == start || tokens.get(index - 1).equals(","))) return true;
            if (token.equals("(")) index = afterGroup(tokens, index) - 1;
        }
        return false;
    }

    private static int afterGroup(List<String> tokens, int open) {
        int depth = 1;
        for (int index = open + 1; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (token.equals("(")) depth++;
            else if (token.equals(")") && --depth == 0) return index + 1;
        }
        return tokens.size();
    }
}
