package de.tum.cit.aet.hephaestus.core.tenancy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import io.micrometer.core.instrument.Counter;
import java.io.Serial;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hibernate {@link StatementInspector} that asserts every SQL statement against a
 * workspace-scoped table carries a {@code workspace_id} predicate.
 *
 * <p>Pipeline (cheapest checks first):
 * <ol>
 *   <li>Active bypass on the thread ({@code @WorkspaceAgnostic}) — pass</li>
 *   <li>Mode {@link TenancyEnforcement#OFF} — pass</li>
 *   <li>Caffeine cache hit on the literal SQL — return cached decision</li>
 *   <li>Word-boundary regex: {@code workspace_id} mentioned anywhere — pass, unless the statement reads a
 *       provider mirror table, which must bind the workspace to a parameter (ADR 0077)</li>
 *   <li>Load by the table's complete composite primary key: a WHERE clause that is only
 *       {@code (a.k1, a.k2) IN ((?, ?), …)} over exactly the mapped key columns — pass (ADR 0004 update)</li>
 *   <li>Table-extract regex: pull table names from {@code FROM/JOIN/UPDATE/INTO} clauses,
 *       intersect with {@link WorkspaceScopedTables#scopedTables()}. Any match without
 *       a {@code workspace_id} reference anywhere in the statement is a violation.</li>
 * </ol>
 *
 * <p><b>Why regex, not a SQL parser?</b> JSqlParser was tried and rejected: adding it to
 * the classpath caused Spring Data JPA to auto-activate its {@code JSqlParserQueryEnhancer},
 * which fails on legitimate Postgres-escaped {@code @Query} natives (e.g.,
 * {@code CONCAT(:id\:\:text, ...)}), breaking application boot. A regex-only inspector
 * has no transitive blast radius and is sufficient for the {@code workspace_id} predicate
 * shape we actually care about.
 *
 * <p>Wired via Spring Boot's {@code HibernatePropertiesCustomizer} in
 * {@link TenancyConfiguration}.
 */
// Hibernate requires Serializable, but this Spring-managed inspector is recreated with each session factory.
// Its metric registry, reporter and cache must not be serialized or restored as detached service state.
@SuppressWarnings("serial")
public class WorkspaceStatementInspector implements StatementInspector {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(WorkspaceStatementInspector.class);

    /**
     * Matches the {@code workspace_id} column as a word boundary. Tolerates every
     * predicate shape Hibernate emits (simple {@code col = ?}, tuple-IN
     * {@code (a, workspace_id) IN ((?, ?))}, schema-qualified
     * {@code wm1_0.workspace_id}, etc.). Trade-off: a literal string containing the
     * bare word would falsely pass. Acceptable because Hibernate-emitted SQL is
     * parameterized — column names rarely appear inside string-literal values — and
     * the alternative (tighter predicate matching) breaks legitimate composite-key
     * queries.
     */
    private static final Pattern WORKSPACE_ID_PATTERN = Pattern.compile("\\bworkspace_id\\b", Pattern.CASE_INSENSITIVE);
    /** {@code workspace_id = ?}, {@code = CAST(? …)}, {@code = ANY(?)} or {@code IN (?, …)}: the workspace bound to a value. */
    private static final Pattern BOUND_WORKSPACE_PREDICATE_PATTERN = Pattern.compile(
            "\\bworkspace_id\"?\\s*(?:=\\s*(?:ANY\\s*\\(\\s*)?(?:CAST\\s*\\(\\s*)?\\?|IN\\s*\\(\\s*\\?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern COMMENT_OR_LITERAL_PATTERN =
            Pattern.compile("--[^\\n]*|/\\*.*?\\*/|'(?:''|[^'])*'", Pattern.DOTALL);
    /** The alias of the {@code workspace} table in a FROM or JOIN. */
    private static final Pattern WORKSPACE_ALIAS_PATTERN = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+(?:public\\s*\\.\\s*)?\"?workspace\"?\\s+(?:AS\\s+)?(?!(?:ON|WHERE|JOIN|LEFT|INNER|CROSS)\\b)([A-Za-z_][A-Za-z0-9_]*)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NEGATION_PATTERN =
            Pattern.compile("\\bNOT\\s*(?:EXISTS\\s*|IN\\s*)?$", Pattern.CASE_INSENSITIVE);

    // Inspect read tables in Hibernate SQL with explicit JOIN syntax; INSERT is outside this read-leak check.
    // Native queries with comma joins require explicit workspace predicates.
    private static final Pattern TABLE_REFERENCE_PATTERN = Pattern.compile(
            "(?:\\bFROM\\b|\\bJOIN\\b|\\bUPDATE\\b)\\s+(\"?[A-Za-z_][A-Za-z0-9_]*\"?(?:\\s*\\.\\s*\"?[A-Za-z_][A-Za-z0-9_]*\"?)?)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Matches the one statement Hibernate emits to remove an element of a {@code @ManyToMany}:
     * the join-table row deleted by both of its foreign keys,
     * {@code DELETE FROM issue_label WHERE issue_id = ? AND label_id = ?}. Fully anchored, DELETE
     * only, exactly two key predicates, so neither a comment nor a SET clause can hide anything
     * in it. Which tables it may apply to is not a naming convention: the captured table must be
     * a many-to-many join table in the mapping metamodel, so an entity table with two
     * {@code *_id} columns never qualifies however it is named.
     */
    private static final Pattern JOIN_TABLE_ROW_DELETE_PATTERN = Pattern.compile(
            "^\\s*DELETE\\s+FROM\\s+\"?([A-Za-z_][A-Za-z0-9_]*)\"?\\s+WHERE\\s+"
                    + "\"?([A-Za-z_][A-Za-z0-9_]*_id)\"?\\s*=\\s*\\?\\s+AND\\s+"
                    + "\"?([A-Za-z_][A-Za-z0-9_]*_id)\"?\\s*=\\s*\\?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /**
     * The shape of a single-row DML whose WHERE clause might be a complete primary key:
     * {@code UPDATE table SET ... WHERE <conjunction>} or {@code DELETE FROM table WHERE
     * <conjunction>}. The conjunction is captured whole rather than matched column by column,
     * because whether it covers the key is a question for the mapping metamodel, not for a regex —
     * {@link #keyedSingleRowDml} answers it.
     */
    private static final Pattern FULL_KEY_DML_PATTERN = Pattern.compile(
            "^\\s*(?:DELETE\\s+FROM|UPDATE)\\s+\"?([A-Za-z_][A-Za-z0-9_]*)\"?"
                    // Captured, not skipped: what a SET assigns reaches as far as what a WHERE
                    // selects, and it is checked below.
                    + "(?:\\s+SET\\s+(.+?))?"
                    // Greedy to the end, trimmed in Java. A reluctant tail against a trailing
                    // `\\s*$` retries at every end position, which a long whitespace run makes
                    // quadratic.
                    + "\\s+WHERE\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * The only assignment this exemption accepts: a column set to a bound parameter, optionally
     * cast. An allowlist, because the question is not whether an expression contains a keyword — it
     * is whether the expression reads anything. PostgreSQL spells a read several ways
     * ({@code SELECT}, a bare {@code TABLE t}, a set-returning function), so a blacklist of
     * keywords cannot answer it, while "every assignment is a bound parameter" can. That is also
     * all Hibernate emits.
     */
    private static final Pattern BOUND_ASSIGNMENT_PATTERN =
            Pattern.compile("^\\s*\"?[A-Za-z_][A-Za-z0-9_]*\"?\\s*=\\s*\\?(?:::[A-Za-z_][A-Za-z0-9_ ]*)?\\s*$");

    private static final Pattern COMMA_SPLIT_PATTERN = Pattern.compile(",");
    private static final Pattern QUALIFIED_COLUMN_SPLIT_PATTERN = Pattern.compile("\\s*\\.\\s*");
    private static final Pattern TUPLE_SPLIT_PATTERN = Pattern.compile("\\)\\s*,\\s*\\(");

    /** A surrogate key column, for the single-key form: {@code id} or {@code <something>_id}. */
    private static final Pattern SURROGATE_KEY_COLUMN =
            Pattern.compile("^(?:id|[A-Za-z_][A-Za-z0-9_]*_id)$", Pattern.CASE_INSENSITIVE);

    private static final int FAST_PATH_SQL_LIMIT = 8192;

    /** One conjunct of that WHERE clause, and the only shape allowed in it: {@code column = ?}. */
    private static final Pattern KEY_PREDICATE_PATTERN =
            Pattern.compile("^\\s*\"?([A-Za-z_][A-Za-z0-9_]*)\"?\\s*=\\s*\\?\\s*$");

    private static final Pattern AND_SPLIT_PATTERN = Pattern.compile("\\s+AND\\s+", Pattern.CASE_INSENSITIVE);

    /** Hibernate's optimistic-lock predicate, which rides along with the key on a versioned entity. */
    private static final String VERSION_COLUMN = "version";

    /**
     * Matches a Hibernate-emitted load that pins the result set to a single row (or a
     * parent-FK-bounded set) via a surrogate-key predicate. Three concrete shapes:
     * <ul>
     *   <li>{@code EntityManager.find(Class, id)} / lazy {@code @ManyToOne} fetch →
     *       {@code FROM table alias … WHERE alias.id = ?}</li>
     *   <li>Single-table inheritance load by id →
     *       {@code FROM table alias … WHERE alias.id = ? AND alias.<discriminator> = ?}
     *       (or the discriminator appears first).</li>
     *   <li>Lazy {@code @OneToMany}/{@code @ManyToMany} collection initialisation →
     *       {@code FROM join_or_child_table alias … WHERE alias.<parent>_id = ?}</li>
     *   <li>That same initialisation under {@code @BatchSize}, where Hibernate fills several
     *       parents' collections in one round trip →
     *       {@code FROM child_table alias … WHERE alias.<parent>_id = ANY(?)}</li>
     * </ul>
     *
     * <p>Safe by construction: the caller already had the surrogate key — or, in the batched form,
     * every key in the bound array, each obtained the same way. {@code ANY(?)} takes a single array
     * parameter, so the predicate stays pinned to those keys. Surrogate keys are
     * opaque to URL inputs and only come into existence via a workspace-scoped path
     * ({@code findByWorkspaceIdAndSlug} → {@code entity.getRelation()} → lazy fetch).
     *
     * <p>The check requires {@code SELECT … FROM <one-table> <alias>} (alias capture) and a
     * WHERE clause containing {@code <alias>.<id|*_id> = ?}. The WHERE may contain
     * additional conjunctive predicates (discriminators, soft-delete filters), but the
     * shape stays anchored to the captured alias — leaking across workspaces would require
     * dropping the alias-PK predicate entirely.
     */
    private static final Pattern PK_ONLY_SELECT_PATTERN = Pattern.compile(
            "^\\s*SELECT\\b.+?\\bFROM\\s+\"?[A-Za-z_][A-Za-z0-9_]*\"?\\s+([A-Za-z_][A-Za-z0-9_]*)\\b" + ".*?"
                    + "\\bWHERE\\b"
                    + ".*?"
                    // workspace_id is a workspace predicate, not a key: the workspace check below reads it, negations
                    // included (ADR 0077).
                    + "\\b\\1\\s*\\.\\s*\"?(?!workspace_id\\b)(?:id|[A-Za-z_][A-Za-z0-9_]*_id)\"?\\s*=\\s*(?:\\?|ANY\\s*\\(\\s*\\?\\s*\\))"
                    + ".*?$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * A load by the table's complete composite key, which Hibernate emits for an {@code @EmbeddedId} entity:
     * {@code SELECT … FROM t a WHERE (a.k1, a.k2) IN ((?, ?), …)}. The whole WHERE clause is the tuple, so nothing else
     * can widen it; {@link #keyedTupleSelect} then requires the tuple to be exactly the table's primary key. Safe for
     * the reason the single-key load is: the caller already held each key.
     */
    private static final Pattern KEY_TUPLE_SELECT_PATTERN = Pattern.compile(
            "^\\s*SELECT\\b.+?\\bFROM\\s+\"?([A-Za-z_][A-Za-z0-9_]*)\"?\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+WHERE\\s+"
                    + "\\(([^()]+)\\)\\s+IN\\s+\\(\\s*(\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\)"
                    + "(?:\\s*,\\s*\\(\\s*\\?(?:\\s*,\\s*\\?)*\\s*\\))*)\\s*\\)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Identifies INSERTs cheaply without anchoring to whitespace shape. */
    private static final Pattern INSERT_STATEMENT_PATTERN =
            Pattern.compile("^\\s*INSERT\\s+INTO\\b", Pattern.CASE_INSENSITIVE);

    /**
     * A standalone {@code OR} token. The PK-only fast paths anchor on a single
     * {@code <alias>.id = ?} predicate but otherwise tolerate trailing/leading {@code .*?},
     * so {@code WHERE id = ? OR is_public = true} would otherwise satisfy the pattern while
     * actually broadening the result set well past the single keyed row — defeating the
     * "already had the surrogate key" safety argument. A disjunction therefore disqualifies the
     * fast path and forces the standard {@code workspace_id} check. Hibernate never emits a
     * disjunctive PK load, so this only over-reports on exotic hand-written SQL (the safe
     * direction). Word-boundary so identifiers like {@code coordinator} don't trip it.
     */
    private static final Pattern OR_TOKEN_PATTERN = Pattern.compile("\\bOR\\b", Pattern.CASE_INSENSITIVE);

    private final WorkspaceScopedTables scopedTables;
    private final TenancyEnforcement mode;
    private final TenancyViolationReporter reporter;
    private final Counter parseFailureCounter;

    private final Cache<String, Decision> decisionCache =
            Caffeine.newBuilder().maximumSize(10_000).build();

    public WorkspaceStatementInspector(
            WorkspaceScopedTables scopedTables,
            TenancyEnforcement mode,
            TenancyViolationReporter reporter,
            Counter parseFailureCounter) {
        this.scopedTables = scopedTables;
        this.mode = mode;
        this.reporter = reporter;
        this.parseFailureCounter = parseFailureCounter;
    }

    @Override
    public @Nullable String inspect(@Nullable String sql) {
        if (sql == null || sql.isBlank()) return sql;
        if (mode == TenancyEnforcement.OFF) return sql;
        if (TenancyBypass.isActive()) {
            Set<String> silencing = TenancyBypass.silencingTypes();
            if (!silencing.isEmpty()) {
                Decision bypassed = decisionCache.get(sql, this::analyze);
                if (bypassed != null && bypassed.violated()) {
                    reporter.reportBypassed(sql, bypassed.unguardedTables(), silencing);
                }
            }
            return sql;
        }

        Decision decision = decisionCache.get(sql, this::analyze);
        if (decision != null && decision.violated()) {
            reporter.report(sql, decision.unguardedTables(), mode);
        }
        return sql;
    }

    /**
     * True iff {@code sql} is DML the database will apply to at most the one row its key names.
     *
     * <p>Both keyed exemptions come through here, because a safeguard that one of them can be
     * routed around is not one: the single-key form accepted
     * {@code UPDATE t SET c=? \n-- WHERE k=?} — every row, in every workspace — long before the
     * composite form existed.
     *
     * <p>Three things have to hold, and every way of being unsure is a {@code false}, which sends
     * the statement to the standard {@code workspace_id} check:
     * <ol>
     *   <li>every assignment is a column set to a bound parameter, so nothing the statement writes
     *       was read from rows this check never saw. This is also what makes a comment harmless:
     *       {@code SET c=? \n-- WHERE k=?} leaves the marker in the assignment, which is then not
     *       a bound parameter. Rejecting comment markers separately was tried and removed — no
     *       test could distinguish it, because this rule had already caught every case;</li>
     *   <li>every predicate is {@code column = ?}, and the columns are either the table's complete
     *       primary key per the mapping metamodel, or one surrogate key column — each optionally
     *       with Hibernate's {@code version} optimistic lock.</li>
     * </ol>
     */
    private boolean keyedSingleRowDml(String sql) {
        if (sql.length() > FAST_PATH_SQL_LIMIT) return false;
        Matcher dml = FULL_KEY_DML_PATTERN.matcher(sql);
        if (!dml.matches()) return false;
        // An OR anywhere would reach rows the key does not name.
        if (OR_TOKEN_PATTERN.matcher(sql).find()) return false;

        String setClause = dml.group(2);
        if (setClause != null) {
            for (String assignment : COMMA_SPLIT_PATTERN.split(setClause, -1)) {
                if (!BOUND_ASSIGNMENT_PATTERN.matcher(assignment).matches()) return false;
            }
        }

        Set<String> predicateColumns = new HashSet<>();
        for (String conjunct : AND_SPLIT_PATTERN.split(dml.group(3).strip(), -1)) {
            Matcher predicate = KEY_PREDICATE_PATTERN.matcher(conjunct);
            if (!predicate.matches()) return false;
            if (!predicateColumns.add(predicate.group(1).toLowerCase(Locale.ROOT))) return false;
        }

        // The optimistic lock rides along with the key on a versioned entity, so it is set aside —
        // unless the table really does key on a column of that name, where it is the key column.
        Set<String> keyColumns = new HashSet<>(predicateColumns);
        boolean versioned = keyColumns.remove(VERSION_COLUMN);

        // One surrogate key: the long-standing form, and the metamodel is not consulted for it.
        if (keyColumns.size() == 1
                && SURROGATE_KEY_COLUMN.matcher(keyColumns.iterator().next()).matches()) {
            return true;
        }
        // The table's whole key, which is the only way a composite-key entity can be written.
        Set<String> mapped = scopedTables.primaryKeyColumns(unqualify(dml.group(1)));
        if (mapped.isEmpty()) return false;
        return predicateColumns.equals(mapped) || (versioned && keyColumns.equals(mapped));
    }

    private Decision analyze(String sql) {
        // INSERTs cannot leak data across workspaces — they create new rows.
        if (INSERT_STATEMENT_PATTERN.matcher(sql).find()) {
            return Decision.ok();
        }
        // Hibernate-emitted DML the database applies to at most the row its key names: the row was
        // already loaded within a workspace-checked scope, and the caller had to hold its identity
        // to write it. One key column or the whole composite key, validated together.
        if (keyedSingleRowDml(sql)) {
            return Decision.ok();
        }
        Matcher joinTableRowDelete = JOIN_TABLE_ROW_DELETE_PATTERN.matcher(sql);
        if (joinTableRowDelete.matches()
                // Two different foreign keys: the row's whole key, not one key named twice.
                && !joinTableRowDelete.group(2).equalsIgnoreCase(joinTableRowDelete.group(3))
                && scopedTables.isManyToManyJoinTable(unqualify(joinTableRowDelete.group(1)))) {
            return Decision.ok();
        }
        // Hibernate-emitted entity load / lazy-fetch by PK is safe for the same reason —
        // the caller already had the primary key. A disjunction (OR) would broaden the result
        // set past the single keyed row, so it disqualifies the fast path.
        if (PK_ONLY_SELECT_PATTERN.matcher(sql).matches()
                && !OR_TOKEN_PATTERN.matcher(sql).find()) {
            return Decision.ok();
        }
        if (WORKSPACE_ID_PATTERN.matcher(sql).find()) {
            // A mirror row carries no workspace_id, so the word alone proves nothing about it (ADR 0077).
            Set<String> mirrors = mirrorTablesIn(sql);
            return mirrors.isEmpty() || bindsWorkspace(sql) ? Decision.ok() : Decision.violation(mirrors);
        }
        // After the workspace_id check, so a key that includes the workspace is settled there without a metamodel
        // lookup.
        if (keyedTupleSelect(sql)) {
            return Decision.ok();
        }
        try {
            Set<String> unguarded = new HashSet<>();
            Matcher matcher = TABLE_REFERENCE_PATTERN.matcher(sql);
            while (matcher.find()) {
                String name = unqualify(matcher.group(1)).toLowerCase(Locale.ROOT);
                if (scopedTables.isScoped(name)) {
                    unguarded.add(name);
                }
            }
            return unguarded.isEmpty() ? Decision.ok() : Decision.violation(Set.copyOf(unguarded));
        } catch (Exception e) {
            // The inspector must NEVER throw. Fail-open with an observable counter so
            // pathological inputs surface in metrics rather than as request failures.
            parseFailureCounter.increment();
            if (log.isDebugEnabled()) {
                log.debug(
                        "Tenancy regex analysis failed ({}): {}",
                        e.getClass().getName(),
                        LoggingUtils.truncate(sql, 200));
            }
            return Decision.ok();
        }
    }

    /** True iff {@code sql} loads rows by tuples of exactly the table's complete primary key, and by nothing else. */
    private boolean keyedTupleSelect(String sql) {
        if (sql.length() > FAST_PATH_SQL_LIMIT) return false;
        Matcher select = KEY_TUPLE_SELECT_PATTERN.matcher(sql);
        if (!select.matches() || OR_TOKEN_PATTERN.matcher(sql).find()) return false;
        String alias = select.group(2);
        Set<String> columns = new HashSet<>();
        for (String column : COMMA_SPLIT_PATTERN.split(select.group(3), -1)) {
            String[] parts = QUALIFIED_COLUMN_SPLIT_PATTERN.split(column.strip(), -1);
            if (parts.length != 2 || !parts[0].equalsIgnoreCase(alias)) return false;
            if (!columns.add(parts[1].replace("\"", "").toLowerCase(Locale.ROOT))) return false;
        }
        for (String tuple : TUPLE_SPLIT_PATTERN.split(select.group(4), -1)) {
            if (tuple.chars().filter(c -> c == '?').count() != columns.size()) return false;
        }
        Set<String> key = scopedTables.primaryKeyColumns(unqualify(select.group(1)));
        return !key.isEmpty() && columns.equals(key);
    }

    private static Set<String> mirrorTablesIn(String sql) {
        Set<String> mirrors = new HashSet<>();
        Matcher matcher = TABLE_REFERENCE_PATTERN.matcher(sql);
        while (matcher.find()) {
            String name = unqualify(matcher.group(1)).toLowerCase(Locale.ROOT);
            if (WorkspaceScopedTables.MIRROR_TABLES.contains(name)) mirrors.add(name);
        }
        return mirrors;
    }

    /**
     * True iff the statement binds the workspace: a {@code workspace_id}, or the {@code id} of the {@code workspace}
     * table, equal to or in bound parameters, outside comments and string literals and not under a negation. A join
     * of two {@code workspace_id} columns binds nothing.
     */
    private static boolean bindsWorkspace(String sql) {
        String text = COMMENT_OR_LITERAL_PATTERN.matcher(sql).replaceAll(" ");
        if (anyUnnegated(text, BOUND_WORKSPACE_PREDICATE_PATTERN)) return true;
        // The organization path binds the workspace row itself: workspace w … WHERE w.id = ?.
        Matcher workspace = WORKSPACE_ALIAS_PATTERN.matcher(text);
        while (workspace.find()) {
            Pattern boundId = Pattern.compile("\\b" + Pattern.quote(workspace.group(1)) + "\\s*\\.\\s*id\\s*=\\s*\\?");
            if (anyUnnegated(text, boundId)) return true;
        }
        return false;
    }

    private static boolean anyUnnegated(String text, Pattern pattern) {
        Matcher predicate = pattern.matcher(text);
        while (predicate.find()) {
            if (!negated(text, predicate.start())) return true;
        }
        return false;
    }

    /** True iff the predicate at {@code start} sits under {@code NOT}, {@code NOT EXISTS} or {@code NOT IN}. */
    private static boolean negated(String text, int start) {
        Deque<Boolean> groups = new ArrayDeque<>();
        for (int i = 0; i < start; i++) {
            char c = text.charAt(i);
            if (c == '(') {
                groups.push(endsWithNegation(text, i));
            } else if (c == ')' && !groups.isEmpty()) {
                groups.pop();
            }
        }
        if (groups.contains(true)) return true;
        int operand = start;
        while (operand > 0 && isIdentifierPart(text.charAt(operand - 1))) operand--;
        return endsWithNegation(text, operand);
    }

    private static boolean endsWithNegation(String text, int end) {
        return NEGATION_PATTERN
                .matcher(text.substring(Math.max(0, end - 24), end))
                .find();
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '"';
    }

    /** Strip dialect quotes and the schema prefix from an identifier. */
    private static String unqualify(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // Drop schema prefix first (it may be quoted too).
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            s = s.substring(dot + 1).trim();
        }
        // Strip any stray dialect quote chars (", `, ') wherever they sit. Matched-pair stripping alone
        // leaves a malformed single-quote identifier (e.g. "practice) verbatim, so the lowercase lookup
        // would miss the scoped set and the table would escape enforcement. Removing every quote char
        // makes a malformed identifier degrade toward over-reporting (safe), not under-reporting.
        return s.replace("\"", "").replace("`", "").replace("'", "");
    }

    record Decision(boolean violated, Set<String> unguardedTables) {
        static Decision ok() {
            return new Decision(false, Set.of());
        }

        static Decision violation(Set<String> tables) {
            return new Decision(true, tables);
        }
    }
}
