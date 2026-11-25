package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.integration.core.framework.MonitoredRepositoryScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.repository.Repository;

class NativeIdProviderBindingArchTest extends HephaestusArchitectureTest {

    private static final Path JAVA = Path.of("src/main/java");
    private static final Path CHANGELOG = Path.of("src/main/resources/db/changelog");
    private static final Path BASELINE = Path.of("src/test/resources/native-id-provider-binding-baseline.txt");
    private static final String ID = "(?:[a-z0-9_]*(?:native_id|nativeid)|provider_user_key|provideruserkey|subject)";
    private static final Pattern NATIVE_ID = Pattern.compile(
            "native_id|nativeid|provider_user_key|provideruserkey|\\bsubject\\b", Pattern.CASE_INSENSITIVE);
    private static final String NAME = "[a-z_][a-z0-9_]*";
    private static final String PATH = "(?:" + NAME + "\\.)*";
    private static final String PROVIDER =
            "(?:provider_id|providerid|provider\\.id|(?:source_)?connection_id|connectionid|connection\\.id)";
    private static final String EQUALITY = "\\s*(?:=(?!=)|\\bin\\b)\\s*";
    private static final Pattern SQL_BLOCK = Pattern.compile("<sql(?:\\s[^>]*?)?>(.*?)</sql>", Pattern.DOTALL);
    private static final Pattern FUNCTION_BODY =
            Pattern.compile("(?i)\\bas\\s+(\\$(?:[a-z_]\\w*)?\\$)(.*?)\\1", Pattern.DOTALL);
    private static final Pattern TOKEN = Pattern.compile(
            "'(?:[^']|'')*'|[();]|\\b(?:select|where|on|having|and|or|not|union|intersect|except|returning|set|"
                    + "order|group|values)\\b|(?<![:.$\\w])(?:(?<native>" + PATH + ID + ")"
                    + "(?<operation>" + EQUALITY + ")(?<other>(?:cast\\(\\s*)?" + PATH + ID + "\\b)?"
                    + "|(?<provider>" + PATH + PROVIDER + ")" + EQUALITY
                    + "(?<value>[:?$a-z_0-9][a-z0-9_.$]*|''))");
    private static final Pattern DERIVED =
            Pattern.compile("(?:find|read|get|query|search|stream|count|exists|delete|remove)\\w*?By(.+)");
    private static final Pattern DERIVED_ID =
            Pattern.compile("(?:^|[._A-Z])[A-Za-z_]*(?:NativeId|ProviderUserKey)(?:In|Is|Equals)?$");
    private static final Pattern DERIVED_PROVIDER =
            Pattern.compile("(?:Provider_?Id|(?:Source)?Connection_?Id)(?:In|Is|Equals)?");
    private static final Pattern OR = Pattern.compile("Or(?=[A-Z])");
    private static final Pattern AND = Pattern.compile("And(?=[A-Z])");
    private static final Set<String> QUERY_ANNOTATIONS = Set.of("Query", "NativeQuery");

    // This predicate binds the repository provider to the monitor workspace's source connection.
    // Pin its shape so weakening the shared predicate cannot weaken this test's check.
    private static final String MONITOR_SCOPE = normalized(MonitoredRepositoryScope.NATIVE_RTM_R);
    private static final boolean REVIEWED_MONITOR_SCOPE =
            fingerprint(MONITOR_SCOPE).equals("8c2d126e277c8fdb932ac020c6f75c72e8ea33bb94bdd1bbea99088ca4bfb36c");

    private record Violation(Path file, long line, String predicate, String statement) {

        @Override
        public String toString() {
            return file + ":" + line + " " + predicate;
        }

        String key() {
            return file + " | " + predicate + " | " + fingerprint(statement);
        }
    }

    @Test
    void shouldBindProviderNativeIdsInDeclaredQueries() throws IOException {
        List<Violation> violations = new ArrayList<>();
        scanQueryMethods(violations);
        scanJava(violations);
        try (var files = Files.walk(CHANGELOG)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                if (file.toString().endsWith(".xml")) {
                    String source = Files.readString(file);
                    var blocks = SQL_BLOCK.matcher(source);
                    while (blocks.find()) {
                        String sql = blocks.group(1).replace("<![CDATA[", "").replace("]]>", "");
                        scanMigration(
                                file,
                                source.substring(0, blocks.start(1)).lines().count(),
                                sql,
                                violations);
                    }
                } else if (file.toString().endsWith(".sql")) {
                    scanMigration(file, 1, Files.readString(file), violations);
                }
            }
        }
        assertThat(violations.stream().map(Violation::key))
                .as("Bind the provider or connection in the same predicate. Call sites: %s", violations)
                .containsExactlyInAnyOrderElementsOf(Files.readAllLines(BASELINE).stream()
                        .filter(line -> !line.isBlank() && !line.startsWith("#"))
                        .toList());
    }

    @ParameterizedTest
    @CsvSource(
            value = {
                "SELECT r FROM Repo r WHERE r.nativeId = :id AND r.provider.id = :p | 0",
                "SELECT r FROM Repo r WHERE r.nativeId IN :ids AND r.provider.id = :p | 0",
                "SELECT r FROM Repo r WHERE r.nativeId = :id AND s.provider.id = :p | 1",
                "SELECT r FROM Repo r WHERE r.nativeId = :id OR r.provider.id = :p | 1",
                "SELECT r FROM Repo r WHERE r.nativeId = :id AND NOT r.provider.id = :p | 1",
                "SELECT r FROM Repo r WHERE r.nativeId = :id AND r.provider.id = r.provider.id | 1",
                "SELECT r FROM Repo r WHERE r.nativeId = :id AND EXISTS (SELECT 1 FROM Repo r WHERE r.provider.id = :p) | 1",
                "SELECT r FROM Repo r WHERE r.provider.id = :p AND (r.nativeId = :first OR r.nativeId = :second) | 0"
            },
            delimiter = '|')
    void shouldRequireProviderBindingForTheMatchedRelation(String query, int expected) {
        List<Violation> violations = new ArrayList<>();
        scan(Path.of("fixture"), 1, query, violations);
        assertThat(violations).hasSize(expected);
    }

    @Test
    void shouldRecognizeTheSharedMonitorScopeOnlyWithItsProviderBinding() {
        List<Violation> violations = new ArrayList<>();
        String query = "SELECT r.id FROM repository r JOIN repository_to_monitor rtm ON "
                + MonitoredRepositoryScope.NATIVE_RTM_R;
        scan(Path.of("fixture"), 1, query, violations);
        assertThat(violations).isEmpty();

        scan(Path.of("fixture"), 1, query.replace("monitor_provider.id = r.provider_id", "TRUE"), violations);
        assertThat(violations).hasSize(1);
    }

    private static String normalized(String sql) {
        return SourceSql.normalized(sql).toLowerCase(Locale.ROOT).replace("\"", "");
    }

    private static String fingerprint(String text) {
        return ProvenanceDigest.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void scanQueryMethods(List<Violation> violations) throws IOException {
        for (JavaClass type : classes) {
            var methods = type.getMethods();
            for (var method : methods) {
                var queries = NativeQueryCommentArchTest.queryStrings(method);
                var derived = DERIVED.matcher(method.getName());
                boolean derivedLookup = queries.isEmpty()
                        && derived.matches()
                        && NATIVE_ID.matcher(derived.group(1)).find()
                        && type.isAssignableTo(Repository.class)
                        && method.getModifiers().contains(JavaModifier.ABSTRACT);
                if (!derivedLookup
                        && queries.values().stream()
                                .noneMatch(query -> NATIVE_ID.matcher(query).find())) continue;
                Path file = JAVA.resolve(type.getName().split("\\$", 2)[0].replace('.', '/') + ".java");
                long line = lineOfMethod(file, method.getName());
                for (String query : queries.values()) scan(file, line, query, violations);
                if (!derivedLookup) continue;
                for (String branch : OR.split(derived.group(1), -1)) {
                    var parts = List.of(AND.split(branch, -1));
                    if (parts.stream().anyMatch(part -> DERIVED_ID.matcher(part).matches())
                            && parts.stream()
                                    .noneMatch(part ->
                                            DERIVED_PROVIDER.matcher(part).matches())) {
                        violations.add(new Violation(file, line, branch, method.getName()));
                    }
                }
            }
        }
    }

    private static long lineOfMethod(Path file, String method) throws IOException {
        var declaration = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(");
        var lines = Files.readAllLines(file);
        for (int index = 0; index < lines.size(); index++) {
            if (declaration.matcher(lines.get(index)).find()) return index + 1L;
        }
        return 1;
    }

    private static void scanJava(List<Violation> violations) throws IOException {
        List<Path> candidates = new ArrayList<>();
        try (var files = Files.walk(JAVA)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList()) {
                if (NATIVE_ID.matcher(Files.readString(file)).find()) candidates.add(file);
            }
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var task = (JavacTask) compiler.getTask(
                    null, manager, null, List.of("-proc:none"), null, manager.getJavaFileObjectsFromPaths(candidates));
            var positions = Trees.instance(task).getSourcePositions();
            for (var unit : task.parse()) {
                Path file = Path.of(unit.getSourceFile().getName());
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitAnnotation(AnnotationTree tree, Void unused) {
                        String name = tree.getAnnotationType().toString();
                        return QUERY_ANNOTATIONS.contains(name.substring(name.lastIndexOf('.') + 1))
                                ? null
                                : super.visitAnnotation(tree, null);
                    }

                    @Override
                    public Void visitLiteral(LiteralTree tree, Void unused) {
                        if (tree.getValue() instanceof String sql
                                && NATIVE_ID.matcher(sql).find()) {
                            long line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, tree));
                            NativeIdProviderBindingArchTest.scan(file, line, sql, violations);
                        }
                        return super.visitLiteral(tree, null);
                    }
                }.scan(unit, null);
            }
        }
    }

    private static void scanMigration(Path file, long line, String sql, List<Violation> violations) {
        // A migration's dollar-quoted function body contains statements, not a value in a comparison.
        var bodies = FUNCTION_BODY.matcher(sql);
        while (bodies.find()) {
            scan(file, line + sql.substring(0, bodies.start(2)).lines().count() - 1, bodies.group(2), violations);
        }
        scan(file, line, sql, violations);
    }

    private static final class Scope {
        private boolean predicate;
        private int branch;
        private final boolean negated;

        Scope(@Nullable Scope parent, boolean negated) {
            this.predicate = parent != null && parent.predicate;
            this.negated = negated || (parent != null && parent.negated);
        }
    }

    private record Position(
            Scope scope, int branch, @Nullable Position parent) {

        boolean providesFor(Position at) {
            return scope == at.scope ? branch == at.branch : at.parent != null && providesFor(at.parent);
        }
    }

    private record Binding(String qualifier, String value, Position at) {
        boolean covers(String name) {
            return qualifier.equals(name);
        }
    }

    private record Match(
            String predicate, String left, @Nullable String right, Position at) {}

    private static void scan(Path file, long line, String raw, List<Violation> violations) {
        if (!NATIVE_ID.matcher(raw).find()) return;
        String sql = normalized(raw);
        String predicates = REVIEWED_MONITOR_SCOPE ? sql.replace(MONITOR_SCOPE, "(true)") : sql;
        var scopes = new ArrayDeque<Position>();
        scopes.push(new Position(new Scope(null, false), 0, null));
        var matches = new ArrayList<Match>();
        var bindings = new ArrayList<Binding>();
        var tokens = TOKEN.matcher(predicates);
        boolean not = false;
        while (tokens.find()) {
            Position at = scopes.element();
            Scope scope = at.scope();
            switch (tokens.group()) {
                case "(" -> scopes.push(new Position(new Scope(scope, not), 0, at));
                case ")" -> {
                    if (scopes.size() > 1) scopes.pop();
                }
                case "select", "union", "intersect", "except", ";" -> {
                    scopes.pop();
                    scopes.push(new Position(new Scope(null, false), 0, null));
                }
                case "where", "on", "having", "and" -> scope.predicate = true;
                case "or" -> {
                    scope.predicate = true;
                    scopes.pop();
                    scopes.push(new Position(scope, ++scope.branch, at.parent()));
                }
                case "returning", "set", "order", "group", "values" -> scope.predicate = false;
                default -> {
                    if (!scope.predicate) continue;
                    String nativeId = tokens.group("native");
                    String provider = tokens.group("provider");
                    if (nativeId != null) {
                        String other = tokens.group("other");
                        matches.add(new Match(
                                tokens.group().strip(),
                                qualifier(nativeId),
                                other == null ? null : qualifier(other.replaceFirst("^cast\\(\\s*", "")),
                                at));
                    } else if (provider != null && !scope.negated && !not) {
                        String stem = provider.replaceFirst(PROVIDER + "$", "");
                        String value = Objects.requireNonNull(tokens.group("value"));
                        if (!value.equals(provider)) bindings.add(new Binding(stem, value, at));
                    }
                }
            }
            not = tokens.group().equals("not");
        }
        for (Match match : matches) {
            if (!isBound(match, bindings)) violations.add(new Violation(file, line, match.predicate(), sql));
        }
    }

    private static boolean isBound(Match match, List<Binding> bindings) {
        return bindings.stream()
                .filter(binding -> binding.at().providesFor(match.at()))
                .anyMatch(binding -> {
                    String other = match.right();
                    if (other == null) return binding.covers(match.left());
                    return (binding.covers(match.left()) && sameProvider(binding.value(), other))
                            || (binding.covers(other) && sameProvider(binding.value(), match.left()));
                });
    }

    private static boolean sameProvider(String value, String qualifier) {
        return !qualifier.isEmpty() && value.matches(Pattern.quote(qualifier) + PROVIDER);
    }

    private static String qualifier(String column) {
        return column.substring(0, column.lastIndexOf('.') + 1);
    }
}
