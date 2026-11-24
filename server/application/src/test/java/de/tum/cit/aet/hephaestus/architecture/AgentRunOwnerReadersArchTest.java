package de.tum.cit.aet.hephaestus.architecture;

import static de.tum.cit.aet.hephaestus.architecture.ArchitectureTestConstants.BASE_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import de.tum.cit.aet.hephaestus.agent.adapter.AgentPersonDataCatalog;
import de.tum.cit.aet.hephaestus.agent.proxy.LlmProxyController;
import de.tum.cit.aet.hephaestus.agent.run.AgentRun;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunState;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunStimulus;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.ErasesColumns;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR 0127: a conversation run stores its owner ({@code agent_run.account_id}). Work that starts from a run then binds
 * the owner without a read across owners. The run table has no owner policy, so only the code that schedules and drives
 * runs reads the owner: the scheduler, the worker, the LLM proxy and the sandbox. A route, a DTO or a staff path never
 * reads it, so none can name or count the accounts that have runs. Neither does erasure: a run goes with its turn, so
 * no person-data store selects or counts runs by their owner.
 *
 * <p>Outside that reader code, the test finds each of these reads:
 *
 * <ul>
 *   <li>a call of, a method reference to or a field access to {@link AgentRun#getAccountId()},
 *       {@link AgentRunStimulus#accountId()}, or the {@code accountId} of a projection that a run repository returns;
 *   <li>any use of {@link AgentRunStimulus}, the type that carries the owner, and a field or a return value of a route
 *       or a DTO that is an {@link AgentRun} or a run projection, which a serializer reads whole;
 *   <li>a Spring Data repository of {@link AgentRun}, whose derived and inherited queries read whole runs;
 *   <li>a native statement that names {@code agent_run}, with its schema or without, and its {@code account_id}, each
 *       quoted or not, or that reads whole runs: {@code *}, {@code r.*}, {@code TABLE agent_run}, or the table or its
 *       alias as a value, as in {@code to_jsonb(r)}, {@code (r).account_id} and {@code SELECT r};
 *   <li>a JPQL statement that names {@code AgentRun} and its {@code accountId}, or that selects the whole entity;
 *   <li>a call of a method of reader code that itself reads the owner, through an access, a query or a statement that it
 *       holds, and returns a value.
 * </ul>
 *
 * <p>The SQL is every string that a compiled class holds: its constants, which include the SQL that it joins from
 * constants of its own or of another class, the constant parts of a concatenation, and the values of its annotations.
 * A field that an initializer sets, such as {@code static final String SQL = "…".strip()}, holds the strings that the
 * initializer loads for it, and each method that reads the field holds them too.
 * The values of {@link ErasesColumns} are column names that no code runs, so they are no read. SQL that code assembles
 * from values at run time stays invisible. So does an owner that reader code hands on through a chain of its own
 * methods, or inside a type that carries other values too: the last rule sees only the method that reads the owner.
 * Reads of the other run columns stay open: the review queries read review runs.
 */
class AgentRunOwnerReadersArchTest extends HephaestusArchitectureTest {

    /** The packages, with their subpackages, of the scheduler, the worker, the LLM proxy and the sandbox. */
    static final List<String> READER_PACKAGES = List.of(
            // The scheduler: the claim order, the claimable rule, the run counts and the wait publication.
            BASE_PACKAGE + ".agent.run",
            // The fleet dispatch of sandboxes and the gateway that serves a sandbox its run.
            BASE_PACKAGE + ".agent.gateway",
            // The sandbox claim and the sandbox drivers.
            BASE_PACKAGE + ".agent.sandbox",
            // The LLM proxy.
            BASE_PACKAGE + ".agent.proxy",
            // The conversation worker that starts and ends a turn's attempt.
            BASE_PACKAGE + ".agent.conversation.turn",
            // The sandbox run kinds of a turn and of a digest.
            BASE_PACKAGE + ".agent.conversation.host",
            // A turn's attempt inside its sandbox.
            BASE_PACKAGE + ".agent.conversation.sandbox");

    /** The accessors and fields of the owner on the run, on its stimulus and on a run projection. */
    private static final Set<String> OWNER_MEMBERS = Set.of("getAccountId", "accountId");

    private static final String NOT_AN_ALIAS = "(?!(?:on|where|set|join|left|right|inner|outer|full|cross|using|group"
            + "|order|limit|offset|for|returning|union|except|intersect|natural|values|and|or|with|select|from|then"
            + "|when|else|end|is|not|in|exists|fetch|having|window|lateral)\\b)";

    /** The run table, with the schema or without, each quoted or not, and the alias that follows it. */
    private static final Pattern RUN_TABLE = Pattern.compile(
            "(?<![\\w\".])(?:\"?public\"?\\s*\\.\\s*)?\"?(agent_run)\"?(?![\\w\"])(?:\\s+(?:as\\s+)?" + NOT_AN_ALIAS
                    + "\"?([a-z_][a-z0-9_]*)\"?)?",
            Pattern.CASE_INSENSITIVE);

    /** The run entity in JPQL, and the alias that follows it. */
    private static final Pattern RUN_ENTITY = Pattern.compile(
            "(?<![\\w.])(AgentRun)\\b(?:\\s+(?:(?i:as)\\s+)?(?i:" + NOT_AN_ALIAS + ")([A-Za-z_][A-Za-z0-9_]*))?");

    /** The end of the text before a table or entity that declares it, with its alias, rather than reads it. */
    private static final Pattern DECLARES =
            Pattern.compile("(?:\\b(?:from|join|update|into|using|only)|,)\\s*$", Pattern.CASE_INSENSITIVE);

    /** A select list or a returning list that is, or starts with, {@code *}, or a {@code *} later in the list. */
    private static final Pattern BARE_STAR =
            Pattern.compile("\\b(?:select(?:\\s+distinct)?|returning)\\s+\\*|,\\s*\\*", Pattern.CASE_INSENSITIVE);

    /** {@code TABLE agent_run}, which selects every column. */
    private static final Pattern TABLE_STATEMENT =
            Pattern.compile("\\btable\\s+(?:\"?public\"?\\s*\\.\\s*)?\"?agent_run\\b", Pattern.CASE_INSENSITIVE);

    /** A JPQL statement that selects its entity without naming it: {@code FROM AgentRun r WHERE …}. */
    private static final Pattern IMPLICIT_SELECT = Pattern.compile("^\\s*from\\b", Pattern.CASE_INSENSITIVE);

    /** The query annotations, whose SQL the test reads with the method that declares it. */
    private static final Set<String> QUERY_ANNOTATIONS = Stream.of(Query.class, NativeQuery.class)
            .map(annotation -> "L" + annotation.getName().replace('.', '/') + ";")
            .collect(Collectors.toUnmodifiableSet());

    /**
     * The person-data map's column declaration: its {@code table.column} values name the columns that an erasure
     * reaches, and no code runs them.
     */
    private static final String COLUMN_DECLARATION =
            "L" + ErasesColumns.class.getName().replace('.', '/') + ";";

    /** A lambda's synthetic method, named after the method that declares it. */
    private static final Pattern LAMBDA = Pattern.compile("^lambda\\$(.+)\\$\\d+$");

    @Test
    void shouldLetOnlyTheSchedulerTheWorkerTheProxyAndTheSandboxReadTheOwnerOfARun() {
        assertThat(violations(classes, AgentRunOwnerReadersArchTest::inReaderCode))
                .as("reads of agent_run.account_id outside the scheduler, the worker, the proxy and the sandbox")
                .isEmpty();
    }

    @Test
    void shouldFindEveryFormOfReadOutsideTheReaders() {
        JavaClasses fixtures = new ClassFileImporter()
                .importClasses(
                        AgentRun.class,
                        AgentRunStimulus.class,
                        Reader.class,
                        ReaderRepository.class,
                        RunOwner.class,
                        ReadsTheOwner.class,
                        ReadsTheStimulusOwner.class,
                        PassesTheStimulusOn.class,
                        ReadsAProjectedOwner.class,
                        RouteThatReturnsARun.class,
                        RunDTO.class,
                        RunOwners.class,
                        QueriesTheOwner.class,
                        JoinsTheOwnerFromConstants.class,
                        Columns.class,
                        CallsTheReader.class,
                        DeclaresTheErasedOwner.class);
        Set<String> readerFixtures = Stream.of(
                        AgentRun.class, AgentRunStimulus.class, Reader.class, ReaderRepository.class, RunOwner.class)
                .map(Class::getName)
                .collect(Collectors.toSet());

        List<String> found = violations(fixtures, type -> readerFixtures.contains(type.getName()));

        List<String> readers = found.stream()
                .map(violation -> violation.substring(0, violation.indexOf(':')))
                .distinct()
                .toList();
        assertThat(readers)
                .as("each read of the owner, named by the member that reads it, in %s", found)
                .containsExactlyInAnyOrder(
                        "ReadsTheOwner.owner",
                        "ReadsTheOwner.ownerLater",
                        "ReadsTheStimulusOwner.owner",
                        "PassesTheStimulusOn.position",
                        "ReadsAProjectedOwner.owner",
                        "RouteThatReturnsARun.run",
                        "RunDTO.run",
                        "RunOwners",
                        "QueriesTheOwner.owners",
                        "QueriesTheOwner.runs",
                        "JoinsTheOwnerFromConstants.ownColumn",
                        "JoinsTheOwnerFromConstants.otherClassColumn",
                        "CallsTheReader.owner",
                        "CallsTheReader.ownerLater",
                        "CallsTheReader.ownerInALambda",
                        "CallsTheReader.ownerFromSql",
                        "CallsTheReader.ownerFromAField",
                        "CallsTheReader.ownerFromAnInstanceField");
    }

    @Test
    void shouldReadEveryStatementThatReadsTheOwnerAndNoneOther() {
        Map<String, String> reads = new LinkedHashMap<>();
        reads.put("bare", "SELECT account_id FROM public.agent_run WHERE id = ?");
        reads.put("bareQuoted", "SELECT \"account_id\" FROM agent_run WHERE id = ?");
        reads.put("quotedSchema", "SELECT account_id FROM \"public\".\"agent_run\" WHERE id = ?");
        reads.put("quotedSchemaAlias", "SELECT r.account_id FROM \"public\".agent_run r");
        reads.put("quotedSchemaTable", "TABLE \"public\".\"agent_run\"");
        reads.put("quotedColumn", "SELECT r.\"account_id\" FROM agent_run AS r");
        reads.put("quotedAlias", "SELECT \"r\".account_id FROM \"agent_run\" AS \"r\"");
        reads.put(
                "joined",
                "SELECT held_run.account_id FROM conversation_turn t JOIN agent_run held_run ON held_run.id = t.run_id");
        reads.put("direct", "SELECT agent_run.account_id FROM agent_run");
        reads.put("composite", "SELECT (r).account_id FROM agent_run r");
        reads.put("toJsonb", "SELECT to_jsonb(r) FROM agent_run r WHERE r.id = ?");
        reads.put("rowToJson", "SELECT row_to_json(r) FROM agent_run r");
        reads.put("tableToJsonb", "SELECT to_jsonb(agent_run) FROM agent_run");
        reads.put("wholeRow", "SELECT r FROM agent_run r");
        reads.put("table", "TABLE agent_run");
        reads.put("everyColumn", "SELECT * FROM agent_run WHERE id = ?");
        reads.put("everyRunColumn", "SELECT t.id, r.* FROM conversation_turn t JOIN agent_run r ON r.id = t.run_id");
        reads.put("returned", "UPDATE agent_run SET state = 'ENDED' RETURNING *");
        reads.put("jpql", "SELECT r.accountId FROM AgentRun r WHERE r.id = :id");
        reads.put("jpqlEntity", "SELECT r FROM AgentRun r WHERE r.id = :id");
        reads.put("jpqlImplicitEntity", "FROM AgentRun r WHERE r.state = :state");
        reads.put("jpqlConstructor", "SELECT new Run(r) FROM AgentRun r");
        Map<String, String> others = new LinkedHashMap<>();
        others.put("turnOwner", "SELECT c.account_id FROM conversation c JOIN agent_run r ON r.id = c.id");
        others.put("turnQuotedOwner", "SELECT c.\"account_id\" FROM conversation c JOIN agent_run r ON r.id = c.id");
        others.put("turnColumns", "SELECT t.* FROM conversation_turn t JOIN agent_run r ON r.id = t.run_id");
        others.put("position", "SELECT r.subject_seq FROM agent_run r");
        others.put("counted", "SELECT count(*) FROM agent_run r WHERE r.state = 'QUEUED'");
        others.put("countedEntities", "SELECT count(r) FROM AgentRun r WHERE r.state = :state");
        others.put("literal", "SELECT id FROM agent_run WHERE kind = 'account_id'");
        others.put("kind", "SELECT r.accountId FROM AgentRunKind r");
        others.put("inserted", "INSERT INTO agent_run (id, workspace_id) VALUES (?, ?)");
        others.put("updated", "UPDATE agent_run r SET state = 'ENDED' WHERE r.id = ?");
        others.put("deletedInJpql", "DELETE FROM AgentRun r WHERE r.id = :id");
        others.put("otherTable", "SELECT account_id FROM agent_run_history");
        others.put("heldRun", "EXISTS (SELECT 1 FROM AgentRun held WHERE held.id = t.runId AND held.state = :leased)");

        assertThat(reads.entrySet().stream()
                        .filter(read -> !readsTheOwner(SourceSql.normalized(read.getValue())))
                        .map(Map.Entry::getKey))
                .as("statements that read the owner of a run")
                .isEmpty();
        assertThat(others.entrySet().stream()
                        .filter(other -> readsTheOwner(SourceSql.normalized(other.getValue())))
                        .map(Map.Entry::getKey))
                .as("statements that read another column or another owner")
                .isEmpty();
    }

    @Test
    void shouldLetNoRouteOrDtoOfAReaderPackageRead() {
        Predicate<JavaClass> readers = AgentRunOwnerReadersArchTest::inReaderCode;
        assertThat(mayRead(classes.get(AgentRuns.class), readers)).isTrue();
        assertThat(mayRead(classes.get(AgentPersonDataCatalog.class), readers))
                .as("the person-data catalog, whose staff preview counts each store")
                .isFalse();
        assertThat(mayRead(classes.get(LlmProxyController.class), readers))
                .as("a route of the proxy")
                .isFalse();
        assertThat(classes.stream()
                        .filter(type -> type.getPackageName().endsWith(".agent.proxy.dto"))
                        .filter(type -> mayRead(type, readers))
                        .toList())
                .as("the proxy's DTOs")
                .isEmpty();
        assertThat(mayRead(classes.get(ConversationTurnService.class), readers))
                .as("the conversation ledger")
                .isFalse();
    }

    /** Whether this class, or the class that it is nested in, belongs to the scheduler, worker, proxy or sandbox. */
    static boolean inReaderCode(JavaClass type) {
        JavaClass outer = outermost(type);
        String packageName = outer.getPackageName();
        return READER_PACKAGES.stream()
                .anyMatch(reader -> packageName.equals(reader) || packageName.startsWith(reader + "."));
    }

    /** Whether code of this class may read the owner of a run: reader code that is no route and no DTO. */
    static boolean mayRead(JavaClass type, Predicate<JavaClass> readerCode) {
        return readerCode.test(type) && !routeOrDto(type);
    }

    private static boolean routeOrDto(JavaClass type) {
        JavaClass outer = outermost(type);
        return outer.isMetaAnnotatedWith(Controller.class)
                || type.isMetaAnnotatedWith(Controller.class)
                || outer.getPackageName().endsWith(".dto")
                || outer.getSimpleName().endsWith("DTO")
                || type.getSimpleName().endsWith("DTO");
    }

    private static JavaClass outermost(JavaClass type) {
        Optional<JavaClass> enclosing = type.getEnclosingClass();
        return enclosing.isPresent() ? outermost(enclosing.get()) : type;
    }

    /** Every read of the owner of a run by code that may not read it, each as {@code Class.member: what}. */
    private static List<String> violations(JavaClasses inspected, Predicate<JavaClass> readerCode) {
        Set<String> repositories = inspected.stream()
                .filter(AgentRunOwnerReadersArchTest::isRunRepository)
                .map(JavaClass::getName)
                .collect(Collectors.toSet());
        Set<String> projections = projections(inspected, repositories);
        Map<String, Map<String, List<String>>> strings = compiledStrings(inspected);
        Set<String> handedOn = handedOn(inspected, readerCode, repositories, projections, strings);
        var violations = new ArrayList<String>();
        for (JavaClass type : inspected) {
            if (mayRead(type, readerCode)) continue;
            String name = type.getSimpleName();
            if (repositories.contains(type.getName())) violations.add(name + ": is a repository of whole runs");
            for (JavaMember member : type.getMembers()) {
                String where = name + "." + declaringMethod(member.getName());
                Set<JavaClass> signature = signature(member);
                if (signature.stream().anyMatch(carried -> carried.isEquivalentTo(AgentRunStimulus.class)))
                    violations.add(where + ": carries the run's stimulus");
                if (routeOrDto(type)
                        && returned(member).stream()
                                .anyMatch(carried -> carried.isEquivalentTo(AgentRun.class)
                                        || projections.contains(carried.getName())))
                    violations.add(where + ": hands a run or its projection to a serializer");
                if (!(member instanceof JavaCodeUnit unit)) continue;
                unit.getAccessesFromSelf().stream()
                        .map(access -> access.getTarget())
                        .filter(target -> readsTheOwner(target, projections)
                                || target.getOwner().isEquivalentTo(AgentRunStimulus.class))
                        .forEach(target -> violations.add(where + ": reads " + target.getFullName()));
                unit.getAccessesFromSelf().stream()
                        .map(access -> access.getTarget())
                        .filter(target -> target.resolveMember()
                                .filter(resolved -> handedOn.contains(resolved.getFullName()))
                                .isPresent())
                        .forEach(target -> violations.add(where + ": takes the owner from " + target.getFullName()));
                if (unit instanceof JavaMethod method && readsTheOwner(annotationSql(method)))
                    violations.add(where + ": queries the owner");
            }
            strings.getOrDefault(type.getName(), Map.of())
                    .forEach((member, held) -> held.stream()
                            .filter(sql -> readsTheOwner(SourceSql.normalized(sql)))
                            .forEach(sql -> violations.add(name + "." + declaringMethod(member) + ": reads " + sql)));
        }
        return violations;
    }

    /** The method that declares a lambda, or the member itself. */
    private static String declaringMethod(String member) {
        Matcher lambda = LAMBDA.matcher(member);
        return lambda.matches() ? lambda.group(1) : member;
    }

    /** The types that a field holds, or that a method or constructor takes or returns. */
    private static Set<JavaClass> signature(JavaMember member) {
        var types = new HashSet<>(returned(member));
        if (member instanceof JavaCodeUnit unit)
            for (JavaParameter parameter : unit.getParameters())
                types.addAll(parameter.getType().getAllInvolvedRawTypes());
        return types;
    }

    /** The types that a field holds, or that a method returns. */
    private static Set<JavaClass> returned(JavaMember member) {
        if (member instanceof JavaField field) return field.getType().getAllInvolvedRawTypes();
        if (member instanceof JavaMethod method) return method.getReturnType().getAllInvolvedRawTypes();
        return Set.of();
    }

    /** A Spring Data repository whose domain type is the run. */
    private static boolean isRunRepository(JavaClass type) {
        return type.isInterface()
                && type.getInterfaces().stream()
                        .filter(JavaParameterizedType.class::isInstance)
                        .map(JavaParameterizedType.class::cast)
                        .anyMatch(repository -> Repository.class.isAssignableFrom(
                                        repository.toErasure().reflect())
                                && !repository.getActualTypeArguments().isEmpty()
                                && repository
                                        .getActualTypeArguments()
                                        .getFirst()
                                        .toErasure()
                                        .isEquivalentTo(AgentRun.class));
    }

    /** The types of Hephaestus, other than the run, that a run repository returns: its projections. */
    private static Set<String> projections(JavaClasses inspected, Set<String> repositories) {
        return inspected.stream()
                .filter(type -> repositories.contains(type.getName()))
                .flatMap(repository -> repository.getMethods().stream())
                .flatMap(method -> method.getReturnType().getAllInvolvedRawTypes().stream())
                .filter(type -> type.getPackageName().startsWith(BASE_PACKAGE) && !type.isEquivalentTo(AgentRun.class))
                .map(JavaClass::getName)
                .collect(Collectors.toSet());
    }

    /** Whether an access reads the owner: from the run, from its stimulus, or from a run projection. */
    private static boolean readsTheOwner(AccessTarget target, Set<String> projections) {
        JavaClass owner = target.getOwner();
        return OWNER_MEMBERS.contains(target.getName())
                && (owner.isEquivalentTo(AgentRun.class)
                        || owner.isEquivalentTo(AgentRunStimulus.class)
                        || projections.contains(owner.getName()));
    }

    /**
     * The methods of reader code that read the owner themselves and return a value, by their full names: a method that
     * reads it through an access or a lambda, a query method of the owner, a derived query that names it, and a method
     * that holds a statement that reads it, as a literal {@code JdbcTemplate} query does.
     */
    private static Set<String> handedOn(
            JavaClasses inspected,
            Predicate<JavaClass> readerCode,
            Set<String> repositories,
            Set<String> projections,
            Map<String, Map<String, List<String>>> compiled) {
        var handedOn = new HashSet<String>();
        for (JavaClass type : inspected) {
            if (!mayRead(type, readerCode)) continue;
            Set<String> reading = new HashSet<>();
            for (JavaCodeUnit unit : type.getCodeUnits()) {
                boolean reads = unit.getAccessesFromSelf().stream()
                                .anyMatch(access -> readsTheOwner(access.getTarget(), projections))
                        || (unit instanceof JavaMethod method
                                && (readsTheOwner(annotationSql(method))
                                        || (repositories.contains(type.getName())
                                                && method.getName().contains("AccountId"))));
                if (reads) reading.add(declaringMethod(unit.getName()));
            }
            compiled.getOrDefault(type.getName(), Map.of()).forEach((member, strings) -> {
                if (strings.stream().map(SourceSql::normalized).anyMatch(AgentRunOwnerReadersArchTest::readsTheOwner))
                    reading.add(declaringMethod(member));
            });
            type.getMethods().stream()
                    .filter(method -> reading.contains(method.getName()))
                    .filter(method -> !method.getRawReturnType().isEquivalentTo(void.class))
                    .forEach(method -> handedOn.add(method.getFullName()));
        }
        return handedOn;
    }

    private static List<String> annotationSql(JavaMethod method) {
        var sql = new ArrayList<String>();
        if (method.isAnnotatedWith(NativeQuery.class)) {
            NativeQuery query = method.getAnnotationOfType(NativeQuery.class);
            sql.add(query.value());
            sql.add(query.countQuery());
        }
        if (method.isAnnotatedWith(Query.class)) {
            Query query = method.getAnnotationOfType(Query.class);
            sql.add(query.value());
            sql.add(query.countQuery());
        }
        return sql.stream()
                .filter(statement -> !statement.isEmpty())
                .map(SourceSql::normalized)
                .toList();
    }

    private static boolean readsTheOwner(List<String> statements) {
        return statements.stream().anyMatch(AgentRunOwnerReadersArchTest::readsTheOwner);
    }

    /**
     * The strings that each compiled class holds, by the class and the member that holds them: the constants that its
     * code loads, which javac joins from constants, the constant parts of its concatenations, the values of its constant
     * fields, and the values of its annotations other than the query annotations, which {@link #annotationSql} reads,
     * and the column declaration. A method that reads a field that an initializer sets also holds the strings that the
     * initializer loads before it sets the field.
     */
    private static Map<String, Map<String, List<String>>> compiledStrings(JavaClasses inspected) {
        Map<String, ClassStrings> classes = new HashMap<>();
        Map<String, List<String>> initialized = new HashMap<>();
        for (JavaClass type : inspected) {
            ClassStrings held = classStrings(type);
            classes.put(type.getName(), held);
            held.fields().forEach((field, strings) -> initialized.put(type.getName() + "." + field, strings));
        }
        Map<String, Map<String, List<String>>> compiled = new HashMap<>();
        classes.forEach((type, held) -> {
            Map<String, List<String>> members = new LinkedHashMap<>();
            held.members().forEach((member, strings) -> members.put(member, new ArrayList<>(strings)));
            held.reads()
                    .forEach((member, fields) -> fields.stream()
                            .map(initialized::get)
                            .filter(Objects::nonNull)
                            .forEach(strings -> members.computeIfAbsent(member, ignored -> new ArrayList<>())
                                    .addAll(strings)));
            compiled.put(type, members);
        });
        return compiled;
    }

    /**
     * The strings of one compiled class: those that each member holds, those that an initializer loads for each field
     * that it sets, and the fields, as {@code Class.field}, that each member reads.
     */
    private record ClassStrings(
            Map<String, List<String>> members, Map<String, List<String>> fields, Map<String, Set<String>> reads) {}

    private static ClassStrings classStrings(JavaClass type) {
        Optional<Source> source = type.getSource();
        if (source.isEmpty()) return new ClassStrings(Map.of(), Map.of(), Map.of());
        byte[] bytes;
        try (InputStream input = source.get().getUri().toURL().openStream()) {
            bytes = input.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the class file of " + type.getName(), e);
        }
        Map<String, List<String>> strings = new LinkedHashMap<>();
        Map<String, List<String>> fields = new LinkedHashMap<>();
        Map<String, Set<String>> reads = new LinkedHashMap<>();
        Function<String, List<String>> of = member -> strings.computeIfAbsent(member, ignored -> new ArrayList<>());
        String self = type.getName().replace('.', '/');
        int api = SpringAsmInfo.ASM_VERSION;
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(api) {
                            @Override
                            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                                return annotationStrings(api, descriptor, of.apply("class"));
                            }

                            @Override
                            public FieldVisitor visitField(
                                    int access,
                                    String name,
                                    String descriptor,
                                    @Nullable String signature,
                                    @Nullable Object value) {
                                if (value instanceof String constant)
                                    of.apply(name).add(constant);
                                return new FieldVisitor(api) {
                                    @Override
                                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                                        return annotationStrings(api, annotation, of.apply(name));
                                    }
                                };
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    @Nullable String signature,
                                    String @Nullable [] exceptions) {
                                List<String> held = of.apply(name);
                                boolean initializer = name.equals("<clinit>") || name.equals("<init>");
                                // The strings that an initializer loaded since it last set a field of this class.
                                List<String> pending = new ArrayList<>();
                                return new MethodVisitor(api) {
                                    @Override
                                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                                        return annotationStrings(api, annotation, held);
                                    }

                                    @Override
                                    public void visitLdcInsn(Object value) {
                                        if (!(value instanceof String constant)) return;
                                        held.add(constant);
                                        pending.add(constant);
                                    }

                                    @Override
                                    public void visitInvokeDynamicInsn(
                                            String indyName,
                                            String indyDescriptor,
                                            Handle bootstrap,
                                            Object... arguments) {
                                        for (Object argument : arguments)
                                            if (argument instanceof String constant) {
                                                held.add(constant);
                                                pending.add(constant);
                                            }
                                    }

                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String field, String fieldDescriptor) {
                                        boolean sets = opcode == Opcodes.PUTSTATIC || opcode == Opcodes.PUTFIELD;
                                        if (initializer && sets && owner.equals(self)) {
                                            fields.computeIfAbsent(field, ignored -> new ArrayList<>())
                                                    .addAll(pending);
                                            pending.clear();
                                        } else if (!sets)
                                            reads.computeIfAbsent(name, ignored -> new LinkedHashSet<>())
                                                    .add(owner.replace('/', '.') + "." + field);
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new ClassStrings(strings, fields, reads);
    }

    private static AnnotationVisitor annotationStrings(int api, String descriptor, List<String> held) {
        boolean query = QUERY_ANNOTATIONS.contains(descriptor) || COLUMN_DECLARATION.equals(descriptor);
        return new AnnotationVisitor(api) {
            @Override
            public void visit(@Nullable String name, Object value) {
                if (!query && value instanceof String constant) held.add(constant);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return this;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String nested) {
                return this;
            }
        };
    }

    /**
     * Whether a statement reads the owner of a run. A native statement names the run table and its {@code account_id},
     * through an alias, the table or bare, or reads whole runs. A JPQL statement names the run entity and its
     * {@code accountId}, or selects whole entities. A {@code count(*)} or {@code count(r)} returns no column.
     */
    static boolean readsTheOwner(String sql) {
        return reads(sql, RUN_TABLE, "account_id", Pattern.CASE_INSENSITIVE, true)
                || reads(sql, RUN_ENTITY, "accountId", 0, false);
    }

    private static boolean reads(String sql, Pattern table, String column, int flags, boolean nativeSql) {
        Set<String> qualifiers = new LinkedHashSet<>();
        var undeclared = new StringBuilder(sql);
        Matcher named = table.matcher(sql);
        while (named.find()) {
            qualifiers.add(named.group(1));
            @Nullable String alias = named.group(2);
            if (alias != null) qualifiers.add(alias);
            if (DECLARES.matcher(sql.substring(0, named.start())).find())
                for (int index = named.start(); index < named.end(); index++) undeclared.setCharAt(index, ' ');
        }
        if (qualifiers.isEmpty()) return false;
        String owner = Pattern.quote(column);
        var forms = new ArrayList<Pattern>();
        forms.add(Pattern.compile("(?<![.\\w\"])" + owner + "(?![\\w\"])|(?<![.\\w])\"" + owner + "\"", flags));
        for (String qualifier : qualifiers) {
            String token = "(?<![\\w\".])\"?" + Pattern.quote(qualifier) + "\"?";
            forms.add(Pattern.compile(
                    token + "\\s*\\.\\s*(?:\"?" + owner + "\"?(?![\\w\"])" + (nativeSql ? "|\\*" : "") + ")", flags));
        }
        if (forms.stream().anyMatch(form -> form.matcher(sql).find())) return true;
        if (nativeSql
                && (BARE_STAR.matcher(sql).find()
                        || TABLE_STATEMENT.matcher(sql).find())) return true;
        if (!nativeSql && IMPLICIT_SELECT.matcher(sql).find()) return true;
        // The table or an alias as a value is the whole row: to_jsonb(r), (r).account_id, SELECT r.
        String values = undeclared.toString();
        for (String qualifier : qualifiers) {
            String token = "(?<![\\w\".])\"?" + Pattern.quote(qualifier) + "\"?(?![\\w\"])";
            String counted = Pattern.compile("\\bcount\\s*\\(\\s*(?:distinct\\s+)?" + token + "\\s*\\)", flags)
                    .matcher(values)
                    .replaceAll(" ");
            if (Pattern.compile(token + "(?!\\s*\\.)", flags).matcher(counted).find()) return true;
        }
        return false;
    }

    // The fixtures. Each one that reads the owner is named, with the member that reads it, in the test above.

    /** Reader code that reads the owner, which proves that the readers may, and hands it on to its callers. */
    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class Reader {
        // Not constants: the class initializer and the constructor load the SQL and set the fields.
        private static final String OWNER_SQL = "SELECT account_id FROM agent_run WHERE id = ?".strip();
        private static final String POSITION_SQL = "SELECT subject_seq FROM agent_run WHERE id = ?".strip();
        private final String ownerStatement = "SELECT r.account_id FROM agent_run r WHERE r.id = ?".strip();

        @Nullable
        Long owner(AgentRun run) {
            return run.getAccountId();
        }

        @Nullable
        Long ownerLater(Optional<AgentRun> run) {
            return run.map(AgentRun::getAccountId).orElse(null);
        }

        @Nullable
        Long ownerInALambda(Optional<AgentRun> run) {
            return run.map(held -> held.getAccountId()).orElse(null);
        }

        void bind(AgentRun run) {
            Objects.requireNonNull(run.getAccountId());
        }

        @Nullable
        Long position(AgentRun run) {
            return run.getSubjectSeq();
        }

        @Nullable
        Long ownerFromSql(JdbcTemplate jdbc, UUID run) {
            return jdbc.queryForObject("SELECT account_id FROM agent_run WHERE id = ?", Long.class, run);
        }

        @Nullable
        Long positionFromSql(JdbcTemplate jdbc, UUID run) {
            return jdbc.queryForObject("SELECT subject_seq FROM agent_run WHERE id = ?", Long.class, run);
        }

        @Nullable
        Long ownerFromAField(JdbcTemplate jdbc, UUID run) {
            return jdbc.queryForObject(OWNER_SQL, Long.class, run);
        }

        @Nullable
        Long ownerFromAnInstanceField(JdbcTemplate jdbc, UUID run) {
            return jdbc.queryForObject(ownerStatement, Long.class, run);
        }

        @Nullable
        Long positionFromAField(JdbcTemplate jdbc, UUID run) {
            return jdbc.queryForObject(POSITION_SQL, Long.class, run);
        }
    }

    /** A projection of the reader repository, with the owner and the position. */
    @SuppressWarnings("unused") // Read by ArchUnit only.
    private interface RunOwner {
        @Nullable
        Long getAccountId();

        @Nullable
        Long getSubjectSeq();
    }

    @SuppressWarnings({"unused", "UnusedMethod"}) // Read by ArchUnit only.
    private interface ReaderRepository extends Repository<AgentRun, UUID> {
        List<RunOwner> findByState(AgentRunState state);
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class CallsTheReader {
        @Nullable
        Long owner(Reader reader, AgentRun run) {
            return reader.owner(run);
        }

        @Nullable
        Long ownerLater(Reader reader, AgentRun run) {
            return reader.ownerLater(Optional.of(run));
        }

        @Nullable
        Long ownerInALambda(Reader reader, AgentRun run) {
            return reader.ownerInALambda(Optional.of(run));
        }

        void bind(Reader reader, AgentRun run) {
            reader.bind(run);
        }

        @Nullable
        Long position(Reader reader, AgentRun run) {
            return reader.position(run);
        }

        @Nullable
        Long ownerFromSql(Reader reader, JdbcTemplate jdbc, UUID run) {
            return reader.ownerFromSql(jdbc, run);
        }

        @Nullable
        Long positionFromSql(Reader reader, JdbcTemplate jdbc, UUID run) {
            return reader.positionFromSql(jdbc, run);
        }

        @Nullable
        Long ownerFromAField(Reader reader, JdbcTemplate jdbc, UUID run) {
            return reader.ownerFromAField(jdbc, run);
        }

        @Nullable
        Long ownerFromAnInstanceField(Reader reader, JdbcTemplate jdbc, UUID run) {
            return reader.ownerFromAnInstanceField(jdbc, run);
        }

        @Nullable
        Long positionFromAField(Reader reader, JdbcTemplate jdbc, UUID run) {
            return reader.positionFromAField(jdbc, run);
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class ReadsTheOwner {
        @Nullable
        Long owner(AgentRun run) {
            return run.getAccountId();
        }

        Function<AgentRun, @Nullable Long> ownerLater() {
            return AgentRun::getAccountId;
        }

        @Nullable
        Long position(AgentRun run) {
            return run.getSubjectSeq();
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class ReadsTheStimulusOwner {
        long owner(AgentRunStimulus stimulus) {
            return stimulus.accountId();
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class PassesTheStimulusOn {
        long position(AgentRunStimulus stimulus) {
            return stimulus.subjectSeq();
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class ReadsAProjectedOwner {
        @Nullable
        Long owner(RunOwner run) {
            return run.getAccountId();
        }

        @Nullable
        Long position(RunOwner run) {
            return run.getSubjectSeq();
        }
    }

    @TestComponent
    @RestController
    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class RouteThatReturnsARun {
        Optional<AgentRun> run(AgentRuns runs, UUID id, long workspaceId) {
            return runs.find(new AgentRunRef(id, workspaceId));
        }
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private record RunDTO(AgentRun run) {}

    @SuppressWarnings({"unused", "UnusedMethod"}) // Read by ArchUnit only.
    private interface RunOwners extends Repository<AgentRun, UUID> {
        boolean existsByAccountId(long accountId);
    }

    @SuppressWarnings({"unused", "UnusedMethod"}) // Read by ArchUnit only.
    private interface QueriesTheOwner {
        @Query(value = "SELECT r.account_id FROM agent_run r WHERE r.id = :id", nativeQuery = true)
        List<Long> owners();

        @Query("SELECT r FROM AgentRun r")
        List<AgentRun> runs();

        @Query(value = "SELECT r.subject_seq FROM agent_run r", nativeQuery = true)
        List<Long> positions();

        @Query(value = "SELECT count(*) FROM agent_run", nativeQuery = true)
        long count();
    }

    /** A catalog that declares the owner column that its erasure reaches, without a statement that reads it. */
    @SuppressWarnings("unused") // Read by ArchUnit only.
    @ErasesColumns("agent_run.account_id")
    private static final class DeclaresTheErasedOwner {}

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class Columns {
        static final String OWNER = "account_id";
    }

    @SuppressWarnings("unused") // Read by ArchUnit only.
    private static final class JoinsTheOwnerFromConstants {
        private static final String OWNER = "account_id";
        private static final String POSITION = "subject_seq";

        String ownColumn() {
            return "SELECT " + OWNER + " FROM agent_run WHERE id = ?";
        }

        String otherClassColumn() {
            return "SELECT r." + Columns.OWNER + " FROM agent_run r";
        }

        String position() {
            return "SELECT r." + POSITION + " FROM agent_run r";
        }
    }
}
