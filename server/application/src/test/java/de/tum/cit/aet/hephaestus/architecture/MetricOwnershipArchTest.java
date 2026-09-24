package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Checks module ownership in source because javac inlines String constants.
 * Registration names are inspected syntactically; types are not resolved.
 */
@Tag("architecture")
class MetricOwnershipArchTest {

    private static final Path PRODUCTION_SOURCES = Path.of("src/main/java/de/tum/cit/aet/hephaestus");
    private static final Set<String> FIXTURE_CATALOGS = Set.of("de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics");
    private static final Set<String> BUILDERS = Set.of(
            "Counter",
            "Timer",
            "Gauge",
            "DistributionSummary",
            "LongTaskTimer",
            "FunctionCounter",
            "FunctionTimer",
            "TimeGauge",
            "MultiGauge",
            "Meter");
    private static final Set<String> REGISTRATIONS = Set.of(
            "counter",
            "timer",
            "summary",
            "gauge",
            "gaugeCollectionSize",
            "gaugeMapSize",
            "longTaskTimer",
            "timeGauge");

    @Test
    void shouldRejectInlineNamesAndForeignCatalogReferences() throws IOException {
        assertThat(PRODUCTION_SOURCES)
                .as("production sources, relative to the server/application working directory Gradle sets")
                .isDirectory();
        var modules = ModulithVerificationTest.applicationModules();
        var compiler = ToolProvider.getSystemJavaCompiler();
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(PRODUCTION_SOURCES);
                var files = compiler.getStandardFileManager(null, null, null)) {
            var sources = paths.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
            var units = parse(files.getJavaFileObjectsFromPaths(sources));
            Set<String> catalogs = new HashSet<>();
            Map<String, String> nameOwners = new HashMap<>();
            for (var unit : units) {
                String packageName = Objects.requireNonNull(
                                unit.getPackageName(), "Production sources must declare a package")
                        .toString();
                String filename =
                        Path.of(unit.getSourceFile().toUri()).getFileName().toString();
                // Matched against every module base package rather than through getModuleForPackage,
                // which answers with the outermost of a nest and so cannot see a nested module's catalog.
                if (filename.endsWith("Metrics.java")
                        && modules.stream()
                                .anyMatch(module -> packageName.equals(
                                        module.getBasePackage().getName() + ".metrics"))) {
                    String catalog = packageName + "." + filename.replace(".java", "");
                    catalogs.add(catalog);
                    recordCatalogNames(unit, catalog, nameOwners, violations);
                }
            }
            assertThat(catalogs).as("module metric catalogs").isNotEmpty();
            var forwarders = nameForwardingMethods(units);
            for (var unit : units) {
                violations.addAll(literalViolations(unit, catalogs, forwarders));
                violations.addAll(ownershipViolations(unit, catalogs));
            }
        }
        assertThat(violations).as("meter name ownership violations").isEmpty();
    }

    private static void recordCatalogNames(
            CompilationUnitTree unit, String catalog, Map<String, String> owners, List<String> violations) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                if (node.getInitializer() instanceof LiteralTree literal && literal.getValue() instanceof String name) {
                    String previous = owners.putIfAbsent(name, catalog + "." + node.getName());
                    if (previous != null) {
                        violations.add("Duplicate meter name " + name + " in " + previous + " and " + catalog);
                    }
                }
                return super.visitVariable(node, unused);
            }
        }.scan(unit, null);
    }

    @Test
    void shouldRejectDuplicateNamesWithinAndAcrossCatalogs() throws IOException {
        var unit = parse("""
                class Metrics {
                    static final String FIRST = "same.name";
                    static final String SECOND = "same.name";
                }
                """);
        Map<String, String> owners = new HashMap<>();
        List<String> violations = new ArrayList<>();
        recordCatalogNames(unit, "first.metrics.FirstMetrics", owners, violations);
        assertThat(violations).hasSize(1);
        recordCatalogNames(unit, "second.metrics.SecondMetrics", owners, violations);
        assertThat(violations)
                .hasSize(3)
                .allSatisfy(violation -> assertThat(violation).contains("same.name"));
    }

    private static List<String> ownershipViolations(CompilationUnitTree unit, Set<String> catalogs) {
        var modules = ModulithVerificationTest.applicationModules();
        String sourcePackage = Objects.requireNonNull(
                        unit.getPackageName(), "Production sources must declare a package")
                .toString();
        List<String> violations = new ArrayList<>();
        for (String catalog : catalogReferences(unit, catalogs)) {
            var sourceModule = modules.getModuleForPackage(sourcePackage);
            var catalogModule = modules.getModuleForPackage(catalog.substring(0, catalog.lastIndexOf('.')));
            if (sourceModule.isEmpty() || catalogModule.isEmpty() || !sourceModule.equals(catalogModule)) {
                violations.add(unit.getSourceFile().getName()
                        + " references meter names from another application module: " + catalog);
            }
        }
        return violations;
    }

    @Test
    void shouldRejectForeignCatalogsEvenWhenTheModuleIsOpen() throws IOException {
        String catalog = "de.tum.cit.aet.hephaestus.integration.core.metrics.IntegrationCoreMetrics";
        for (String reference : List.of(
                "import " + catalog + ";",
                "import static " + catalog + ".COUNT;",
                "import static " + catalog + ".*;",
                "import de.tum.cit.aet.hephaestus.integration.core.metrics.*;",
                "class Fixture { String name = " + catalog + ".COUNT; }")) {
            assertThat(ownershipViolations(
                            parse("package de.tum.cit.aet.hephaestus.agent; " + reference), Set.of(catalog)))
                    .hasSize(1);
            assertThat(ownershipViolations(
                            parse("package de.tum.cit.aet.hephaestus.integration.core.signal; " + reference),
                            Set.of(catalog)))
                    .isEmpty();
        }
    }

    private static List<CompilationUnitTree> parse(Iterable<? extends JavaFileObject> sources) throws IOException {
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        List<CompilationUnitTree> units = new ArrayList<>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, sources);
            task.parse().forEach(units::add);
        }
        assertThat(diagnostics.getDiagnostics().stream()
                        .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                        .toList())
                .as("Java source parsing errors")
                .isEmpty();
        return units;
    }

    private static CompilationUnitTree parse(String source) throws IOException {
        var file = new SimpleJavaFileObject(URI.create("string:///Fixture.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        return parse(List.of(file)).getFirst();
    }

    private static Set<String> catalogReferences(CompilationUnitTree unit, Set<String> catalogs) {
        Set<String> references = new HashSet<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMemberSelect(MemberSelectTree node, Void unused) {
                String reference = node.toString();
                for (String catalog : catalogs) {
                    String packageName = catalog.substring(0, catalog.lastIndexOf('.'));
                    if (reference.equals(catalog)
                            || reference.startsWith(catalog + ".")
                            || reference.equals(packageName + ".*")) {
                        references.add(catalog);
                    }
                }
                return super.visitMemberSelect(node, unused);
            }
        }.scan(unit, null);
        return references;
    }

    private static List<String> literalViolations(
            CompilationUnitTree unit, Set<String> catalogs, Set<String> forwarders) {
        List<String> violations = new ArrayList<>();
        var resolver = new NameResolver(unit, catalogs);
        boolean importedMeterId = unit.getImports().stream()
                .anyMatch(importTree -> Set.of(
                                "io.micrometer.core.instrument.Meter.Id", "io.micrometer.core.instrument.Meter.*")
                        .contains(importTree.getQualifiedIdentifier().toString()));
        boolean staticBuilder = staticBuilderImported(unit);
        Set<String> declaredTypes = declaredTypes(unit);
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                nameArguments(node, staticBuilder, forwarders, declaredTypes)
                        .forEach(argument ->
                                checkName(argument, node.getMethodSelect().toString()));
                return super.visitMethodInvocation(node, unused);
            }

            @Override
            public Void visitNewClass(NewClassTree node, Void unused) {
                String type = node.getIdentifier().toString();
                if (((importedMeterId && type.equals("Id"))
                                || type.equals("Meter.Id")
                                || type.equals("io.micrometer.core.instrument.Meter.Id"))
                        && !node.getArguments().isEmpty()) {
                    checkName(node.getArguments().getFirst(), type);
                }
                return super.visitNewClass(node, unused);
            }

            private void checkName(ExpressionTree expression, String api) {
                if (containsNameLiteral(expression)) {
                    violations.add(
                            unit.getSourceFile().getName() + " passes a string literal to " + api + ": " + expression);
                } else if (!resolver.reachesCatalog(expression)) {
                    violations.add(unit.getSourceFile().getName() + " passes a name to " + api
                            + " that reaches no module catalog: " + expression);
                }
            }
        }.scan(unit, null);
        return violations;
    }

    /** One fixture is its own world: its forwarding methods are discovered from the same source. */
    private static List<String> literalViolations(CompilationUnitTree unit, Set<String> catalogs) {
        return literalViolations(unit, catalogs, nameForwardingMethods(List.of(unit)));
    }

    private static boolean staticBuilderImported(CompilationUnitTree unit) {
        return unit.getImports().stream().anyMatch(importTree -> {
            String name = importTree.getQualifiedIdentifier().toString();
            return importTree.isStatic()
                    && BUILDERS.stream()
                            .anyMatch(type -> name.equals("io.micrometer.core.instrument." + type + ".builder")
                                    || name.equals("io.micrometer.core.instrument." + type + ".*"));
        });
    }

    /** The arguments of one call that Micrometer, or a method that forwards to it, reads as a meter name. */
    private static Set<ExpressionTree> nameArguments(
            MethodInvocationTree node, boolean staticBuilder, Set<String> forwarders, Set<String> declaredTypes) {
        String select = node.getMethodSelect().toString();
        String method = lastSegment(select);
        String receiver = select.equals(method) ? "" : lastSegment(select.substring(0, select.lastIndexOf('.')));
        boolean builder = method.equals("builder")
                && ((staticBuilder && select.equals("builder"))
                        || BUILDERS.stream()
                                .anyMatch(type ->
                                        select.equals(type + ".builder") || select.endsWith("." + type + ".builder")));
        Set<ExpressionTree> arguments = new LinkedHashSet<>();
        if ((builder || REGISTRATIONS.contains(method)) && !node.getArguments().isEmpty()) {
            arguments.add(node.getArguments().getFirst());
        }
        Set<String> receivers =
                receiver.isEmpty() || receiver.equals("this") ? declaredTypes : Set.of(receiver, "this");
        for (int index = 0; index < node.getArguments().size(); index++) {
            int position = index;
            if (receivers.stream().anyMatch(type -> forwarders.contains(type + "." + method + "#" + position))) {
                arguments.add(node.getArguments().get(index));
            }
        }
        return arguments;
    }

    /**
     * Methods that hand a parameter of theirs to Micrometer as a meter name, keyed
     * {@code DeclaringType.method#index}. Their callers are the registration site as far as this rule
     * is concerned — a literal handed to such a method is as unowned as one handed to
     * {@code Counter.builder} — so the set is grown to a fixed point first, then every call of one is
     * checked at the forwarded position. The declaring type is part of the key because a bare method
     * name collides with unrelated methods, including Micrometer's own {@code gauge} and {@code counter}.
     */
    private static Set<String> nameForwardingMethods(List<CompilationUnitTree> units) {
        Set<String> forwarders = new HashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (var unit : units) {
                grew |= forwarders.addAll(forwardedNameParameters(unit, forwarders));
            }
        }
        return forwarders;
    }

    private static Set<String> forwardedNameParameters(CompilationUnitTree unit, Set<String> forwarders) {
        boolean staticBuilder = staticBuilderImported(unit);
        Set<String> declaredTypes = declaredTypes(unit);
        Set<String> forwarded = new HashSet<>();
        new TreeScanner<Void, String>() {
            @Override
            public Void visitClass(ClassTree node, String enclosing) {
                return super.visitClass(node, node.getSimpleName().toString());
            }

            @Override
            public Void visitMethod(MethodTree method, String enclosing) {
                List<String> parameters = method.getParameters().stream()
                        .map(parameter -> parameter.getName().toString())
                        .toList();
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitMethodInvocation(MethodInvocationTree node, Void ignored) {
                        for (var argument : nameArguments(node, staticBuilder, forwarders, declaredTypes)) {
                            if (argument instanceof IdentifierTree identifier) {
                                int index =
                                        parameters.indexOf(identifier.getName().toString());
                                if (index >= 0) {
                                    forwarded.add(enclosing + "." + method.getName() + "#" + index);
                                }
                            }
                        }
                        return super.visitMethodInvocation(node, ignored);
                    }
                }.scan(method.getBody(), null);
                return super.visitMethod(method, enclosing);
            }
        }.scan(unit, "");
        return forwarded;
    }

    private static Set<String> declaredTypes(CompilationUnitTree unit) {
        Set<String> types = new HashSet<>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree node, Void unused) {
                types.add(node.getSimpleName().toString());
                return super.visitClass(node, unused);
            }
        }.scan(unit, null);
        return types;
    }

    /**
     * Decides whether a registration's name argument reaches a module catalog, per compilation unit.
     * The literal check alone leaves a hole one call frame wide: an argument such as {@code g.name()}
     * carries no literal of its own, so the name it returns can be written anywhere — the shape that
     * kept {@code worker.capacity.*} outside every catalog while the rule reported no violation. An
     * argument that reaches no catalog is therefore reported rather than trusted.
     *
     * <p>A name a method is handed by its caller is accepted: it is the caller's argument that carries
     * the name, and this same rule checks it there. Aliases are matched by identifier across the whole
     * unit rather than by scope, which can only accept a name a stricter reading would reject.
     */
    private static final class NameResolver {

        private final Set<String> catalogTypes;
        private final Set<String> supplied = new HashSet<>();
        private final Map<String, List<ExpressionTree>> aliases = new HashMap<>();

        NameResolver(CompilationUnitTree unit, Set<String> catalogs) {
            this.catalogTypes =
                    catalogs.stream().map(MetricOwnershipArchTest::lastSegment).collect(Collectors.toSet());
            for (var importTree : unit.getImports()) {
                String imported = importTree.getQualifiedIdentifier().toString();
                int lastDot = imported.lastIndexOf('.');
                if (importTree.isStatic() && lastDot > 0 && catalogs.contains(imported.substring(0, lastDot))) {
                    supplied.add(imported.substring(lastDot + 1));
                }
            }
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    node.getParameters()
                            .forEach(parameter ->
                                    supplied.add(parameter.getName().toString()));
                    return super.visitMethod(node, unused);
                }

                @Override
                public Void visitLambdaExpression(LambdaExpressionTree node, Void unused) {
                    node.getParameters()
                            .forEach(parameter ->
                                    supplied.add(parameter.getName().toString()));
                    return super.visitLambdaExpression(node, unused);
                }

                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    if (node.getInitializer() != null) {
                        aliases.computeIfAbsent(node.getName().toString(), name -> new ArrayList<>())
                                .add(node.getInitializer());
                    }
                    return super.visitVariable(node, unused);
                }
            }.scan(unit, null);
        }

        boolean reachesCatalog(ExpressionTree expression) {
            return reachesCatalog(expression, new HashSet<>());
        }

        private boolean reachesCatalog(ExpressionTree expression, Set<String> visited) {
            return switch (expression) {
                case ParenthesizedTree parenthesized -> reachesCatalog(parenthesized.getExpression(), visited);
                case TypeCastTree cast -> reachesCatalog(cast.getExpression(), visited);
                case BinaryTree binary
                when binary.getKind() == Tree.Kind.PLUS ->
                    reachesCatalog(binary.getLeftOperand(), visited)
                            || reachesCatalog(binary.getRightOperand(), visited);
                case ConditionalExpressionTree conditional ->
                    reachesCatalog(conditional.getTrueExpression(), visited)
                            && reachesCatalog(conditional.getFalseExpression(), visited);
                case MemberSelectTree select ->
                    catalogTypes.contains(lastSegment(select.getExpression().toString()));
                case IdentifierTree identifier ->
                    reachesCatalog(identifier.getName().toString(), visited);
                // A wrapper such as Objects.requireNonNull passes its argument through; an accessor
                // with no argument to pass through carries nothing this rule can see.
                case MethodInvocationTree invocation ->
                    invocation.getArguments().stream().anyMatch(argument -> reachesCatalog(argument, visited));
                default -> false;
            };
        }

        private boolean reachesCatalog(String identifier, Set<String> visited) {
            if (supplied.contains(identifier)) {
                return true;
            }
            if (!visited.add(identifier)) {
                return false;
            }
            return aliases.getOrDefault(identifier, List.of()).stream()
                    .anyMatch(initializer -> reachesCatalog(initializer, visited));
        }
    }

    private static String lastSegment(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private static boolean containsNameLiteral(ExpressionTree expression) {
        return switch (expression) {
            case LiteralTree literal -> literal.getValue() instanceof String;
            case ParenthesizedTree parenthesized -> containsNameLiteral(parenthesized.getExpression());
            case TypeCastTree cast -> containsNameLiteral(cast.getExpression());
            case BinaryTree binary
            when binary.getKind() == Tree.Kind.PLUS ->
                containsNameLiteral(binary.getLeftOperand()) || containsNameLiteral(binary.getRightOperand());
            case ConditionalExpressionTree conditional ->
                containsNameLiteral(conditional.getTrueExpression())
                        || containsNameLiteral(conditional.getFalseExpression());
            default -> false;
        };
    }

    @Test
    void shouldRecognizeNameTakingRegistryMethods() {
        for (Class<?> api : List.of(MeterRegistry.class, MeterRegistry.More.class, Metrics.class, Metrics.More.class)) {
            var methods = Arrays.stream(api.getMethods())
                    .filter(method -> method.getParameterCount() > 0 && method.getParameterTypes()[0] == String.class)
                    .map(java.lang.reflect.Method::getName)
                    .filter(name -> !Set.of("find", "get").contains(name))
                    .collect(Collectors.toSet());
            assertThat(REGISTRATIONS)
                    .as("name-taking methods on %s", api.getName())
                    .containsAll(methods);
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Counter.builder",
                "Timer.builder",
                "Gauge.builder",
                "DistributionSummary.builder",
                "LongTaskTimer.builder",
                "FunctionCounter.builder",
                "FunctionTimer.builder",
                "TimeGauge.builder",
                "MultiGauge.builder",
                "Meter.builder",
                "registry.counter",
                "registry.timer",
                "registry.summary",
                "registry.gauge",
                "registry.gaugeCollectionSize",
                "registry.gaugeMapSize",
                "registry.more().counter",
                "registry.more().timer",
                "registry.more().longTaskTimer",
                "registry.more().timeGauge",
                "Metrics.counter",
                "Metrics.gauge",
                "Metrics.more().timeGauge",
                "io.micrometer.core.instrument.Counter.builder",
                "new Meter.Id"
            })
    void shouldRejectLiteralNamesForEveryRegistrationApi(String api) throws IOException {
        assertThat(literalViolations(
                        parse("import io.micrometer.core.instrument.*; class Fixture { void register() { " + api
                                + " /* comment */ (\n(\"test.literal\")); } }"),
                        FIXTURE_CATALOGS))
                .hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "builder(\"inline\")",
                "counter(\"inline\")",
                "counter(NAME + \".suffix\")",
                "counter((String) \"inline\")",
                "counter(flag ? AgentMetrics.COUNT : \"inline\")"
            })
    void shouldRejectStaticImportsAndInlineNameExpressions(String call) throws IOException {
        assertThat(literalViolations(parse("""
                import static io.micrometer.core.instrument.Counter.builder;
                import static io.micrometer.core.instrument.Metrics.counter;
                class Fixture {
                    void register() { %s; }
                }
                """.formatted(call)), FIXTURE_CATALOGS))
                .isNotEmpty();
    }

    @Test
    void shouldRejectLiteralNamesInImportedCustomMeterIds() throws IOException {
        assertThat(literalViolations(parse("""
                import io.micrometer.core.instrument.Meter.Id;
                class Fixture { Object id = new Id("custom.meter"); }
                """), FIXTURE_CATALOGS)).hasSize(1);
    }

    @Test
    void shouldRejectNamesTheGuardCannotTraceToACatalog() throws IOException {
        assertThat(literalViolations(parse("""
                import io.micrometer.core.instrument.Gauge;
                import io.micrometer.core.instrument.MeterRegistry;
                class Fixture {
                    record Named(String name) {}
                    void register(MeterRegistry registry, Object state) {
                        java.util.List.of(new Named("indirect.name"))
                                .forEach(g -> Gauge.builder(g.name(), state, s -> 1.0).register(registry));
                    }
                }
                """), FIXTURE_CATALOGS))
                .singleElement(as(STRING))
                .contains("reaches no module catalog");
    }

    @Test
    void shouldFollowNamesThroughAMethodThatForwardsThemToMicrometer() throws IOException {
        String fixture = """
                import io.micrometer.core.instrument.MeterRegistry;
                class Fixture {
                    void register(MeterRegistry registry) { %s }
                    private static void gauge(MeterRegistry registry, String name) { registry.gauge(name, 1.0); }
                    private static void relay(MeterRegistry registry, String name) { gauge(registry, name); }
                }
                """;
        assertThat(literalViolations(
                        parse(fixture.formatted("gauge(registry, AgentMetrics.COUNT);")), FIXTURE_CATALOGS))
                .as("a catalog constant handed to a forwarding method")
                .isEmpty();
        assertThat(literalViolations(parse(fixture.formatted("gauge(registry, \"inline.name\");")), FIXTURE_CATALOGS))
                .as("a literal handed to a forwarding method")
                .singleElement(as(STRING))
                .contains("passes a string literal to gauge");
        assertThat(literalViolations(parse(fixture.formatted("relay(registry, \"inline.name\");")), FIXTURE_CATALOGS))
                .as("a literal handed to a method that forwards to a forwarding method")
                .singleElement(as(STRING))
                .contains("passes a string literal to relay");
    }

    @Test
    void shouldIgnoreCommentsTagLiteralsAndUnrelatedBuilders() throws IOException {
        assertThat(literalViolations(parse("""
                import io.micrometer.core.instrument.Counter;
                class Fixture {
                    // registry.counter("comment");
                    String example = "Counter.builder(\\"documentation\\")";
                    void register() {
                        Counter.builder(AgentMetrics.COUNT).tag("outcome", "success");
                        registry.counter(AgentMetrics.COUNT, "outcome", "success");
                        registry.find("lookup.only").counter();
                        Something.builder("not.a.meter");
                        registry.counter(flag.equals("on") ? AgentMetrics.COUNT : AgentMetrics.OTHER);
                        registry.counter(Objects.requireNonNull(AgentMetrics.COUNT, "metric name"));
                    }
                }
                """), FIXTURE_CATALOGS)).isEmpty();
    }

    @Test
    void shouldAllowCatalogAliasesWithUnrelatedLocalLiterals() throws IOException {
        assertThat(literalViolations(parse("""
                import io.micrometer.core.instrument.MeterRegistry;
                class Fixture {
                    static final String NAME = AgentMetrics.COUNT;
                    void register(MeterRegistry registry) { registry.counter(NAME); }
                    void unrelated() { final String NAME = "not a metric name"; }
                }
                """), FIXTURE_CATALOGS)).isEmpty();
    }
}
