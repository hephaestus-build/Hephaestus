package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.privacy.PersonDataRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SecurityRetentionDeletionProof;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.ReflectionUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes {@code docs/admin/dsms/personal-data-map.mdx} enforceable: a new table has to be classified
 * before it can ship, and the map's evidence links cannot rot silently.
 *
 * <p>The authority is the migrated PostgreSQL schema, so a native table without a JPA mapping is
 * classified too. A table declared free of personal data must not reference a person: the schema,
 * not the declaration, decides which columns do. The schema needs a database, so this rule runs in
 * the integration tier.
 */
class PersonalDataMapArchTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PersonDataRegistry registry;

    private static final Path REPOSITORY_ROOT = Path.of("../..");
    private static final Path PRODUCTION_SOURCES = Path.of("src/main/java/de/tum/cit/aet/hephaestus");
    private static final Path MAP = REPOSITORY_ROOT.resolve("docs/admin/dsms/personal-data-map.mdx");

    private static final Pattern CODE_SPAN = Pattern.compile("`([^`]+)`");

    /**
     * Tables that hold no personal data and therefore need no row in the map: instance and workspace
     * configuration, the practice and LLM catalogues, provider connections and their credentials,
     * repository metadata vocabularies, and runtime bookkeeping. A table that stores an account
     * reference or a contributor identity belongs in the map instead, including when the row records
     * an operator's own action rather than a contributor's work. An association table qualifies only
     * when neither side of the link is a person: {@code issue_assignee} and
     * {@code pull_request_requested_reviewers} carry a {@code user_id} and are mapped.
     */
    private static final Set<String> NOT_PERSONAL_DATA = Set.of(
            // Scoped authorization reference, no provider identifier or local person; workspace purge cascades it.
            "connection_provider_repair",
            // The queue points at a commit and holds no email and no user.
            "commit_identity_resolution",
            // GitHub locators left after a product feedback erasure: repository, installation, a random marker.
            "product_feedback_cleanup",
            // What each practice script of a job did: a status, counts and model slots, no content or person.
            "review_job_script_run",
            // A run's queue state: its kind, band, subject key, epoch and times, no content or person; an erasure
            // deletes the runs of the jobs it deletes.
            "agent_run",
            // A sandbox's subject key, claim name and times: a pointer to its subject, like a run's, and no content.
            "agent_sandbox",
            "commit_pull_request",
            "connection",
            "connection_activity",
            "fx_rate",
            "identity_provider",
            "issue_blocking",
            "issue_label",
            "issue_type",
            "jwt_signing_key",
            // Sealed instance secrets, such as the key of the namespace reservations: key material with no person.
            "system_secret",
            // Signatures of handled Slack requests: opaque HMAC values with no Slack user or payload, deleted after
            // 15 minutes by a scheduled job.
            "slack_request_claim",
            // Instance keys that sign provenance records: key material with no person, kept for the life of the
            // instance.
            "provenance_signing_key",
            "label",
            "llm_call_permit",
            "llm_call_pool",
            "llm_connection",
            "llm_model",
            "llm_model_price",
            "llm_model_workspace_grant",
            "login_provider",
            "item", // Technical source references; personal proof is stored in the mapped result tables.
            "practice",
            "review_batch_target",
            // Frozen practice/model configuration and typed readiness flags contain no person or captured words.
            "review_practice",
            "catalog_entry",
            "practice_group",
            "workspace_practice_repository", // A repository item id and a directory.
            "review_scope_repository",
            "pull_request_closing_issue",
            "pull_request_requested_team",
            "release_start",
            "provider_event_step", // Event ids and step names contain no person data.
            "repository_to_monitor",
            "attempt_public_key",
            "workspace",
            "workspace_model_assignment",
            "workspace_llm_connection",
            "workspace_llm_model",
            "workspace_slug_history",
            // Namespace labels with no account or contributor reference; erasure replaces a person's with a token.
            "workspace_slug_reservation",
            "workspace_team_label_filter",
            "workspace_team_repository_settings",
            "workspace_team_settings");

    /** Closed list of personal data retained for security: every addition requires an accepted ADR. */
    private static final Map<String, SecurityRetention> SECURITY_RETENTION = Map.of(
            "provider_resource_repair",
            new SecurityRetention(
                    "docs/decisions/0101-a-connection-cannot-adopt-an-upstream-resource-whose-removal-is-unresolved.md",
                    "Protect the integrity of adopted resources against unresolved provider removals and authorization exchanges",
                    "Legitimate interest in integrity and security, GDPR Art. 6(1)(f)",
                    "Delete when the fence resolves or an instance admin releases it",
                    "de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairDeletionIntegrationTest"));

    private record SecurityRetention(
            String adr, String purpose, String legalBasis, String deletionTrigger, String deletionProof) {}

    @Test
    void securityRetentionIsExplicitPersonalDataWithAnAcceptedDecisionAndDeletionProof() throws Exception {
        String map = Files.readString(MAP);
        assertThat(SECURITY_RETENTION.keySet())
                .as(
                        "SECURITY_RETENTION is closed: adding a table requires an accepted ADR and deletion integration proofs")
                .isSubsetOf(tableNames());
        assertThat(map).contains("## Security retention");
        for (var entry : SECURITY_RETENTION.entrySet()) {
            SecurityRetention retention = entry.getValue();
            assertThat(retention.adr()).startsWith("docs/decisions/").endsWith(".md");
            assertThat(Files.readString(REPOSITORY_ROOT.resolve(retention.adr())))
                    .contains("**Status:** Accepted");
            assertThat(retention.purpose()).isNotBlank();
            assertThat(retention.legalBasis()).contains("integrity", "security", "6(1)(f)");
            assertThat(retention.deletionTrigger()).contains("resolves", "admin releases");
            assertThat(map)
                    .contains(
                            "`" + entry.getKey() + "`",
                            retention.purpose(),
                            retention.legalBasis(),
                            retention.deletionTrigger());
            Class<?> proof = Class.forName(retention.deletionProof());
            assertThat(BaseIntegrationTest.class.isAssignableFrom(proof)).isTrue();
            SecurityRetentionDeletionProof declaration =
                    proof.getDeclaredAnnotation(SecurityRetentionDeletionProof.class);
            assertThat(declaration).isNotNull();
            assertThat(declaration.table()).isEqualTo(entry.getKey());
            assertThat(declaration.resolution()).isNotEqualTo(declaration.adminRelease());
            assertThat(proof.getDeclaredMethod(declaration.resolution()).isAnnotationPresent(Test.class))
                    .isTrue();
            assertThat(proof.getDeclaredMethod(declaration.adminRelease()).isAnnotationPresent(Test.class))
                    .isTrue();
        }
    }

    @Test
    void securityRetentionHasNoWorkspaceOrPersonPointer() {
        for (String table : SECURITY_RETENTION.keySet()) {
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
                    JOIN pg_namespace n ON n.oid = t.relnamespace
                    WHERE n.nspname = 'public' AND t.relname = ? AND c.contype = 'f'
                    """, Long.class, table))
                    .as("SECURITY_RETENTION holds provider identifiers, never foreign keys to owners: %s", table)
                    .isZero();
            assertThat(jdbc.queryForList("""
                    SELECT column_name FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? AND column_name IN
                    ('workspace_id', 'user_id', 'person_id', 'account_id', 'identity_id', 'subject_id', 'connection_id')
                    """, String.class, table))
                    .as("SECURITY_RETENTION has no workspace or person pointer: %s", table)
                    .isEmpty();
        }
    }

    /**
     * The login of the provider account that a workspace is installed on is the namespace of its repositories, like a
     * repository's {@code owner/name}. It is a person's login when the account is a personal one. Person export lists
     * it, and it stays while the workspace exists. Person erasure replaces it in a purged workspace, which keeps its
     * row (InstallationAccountPersonDataStore).
     */
    private static final Set<String> SOURCE_NAMESPACE =
            Set.of("workspace.account_login", "connection.display_name", "repository_to_monitor.name_with_owner");

    /** Columns whose name matches the person heuristic but that hold no person. */
    private static final Map<String, String> NOT_A_PERSON = Map.of(
            "git_commit.authored_by_committer",
            "Whether the author also committed the commit: a flag",
            "git_commit.signature_was_signed_by_github",
            "Whether GitHub signed the commit: a flag",
            "item_event.found_by",
            "How the event was found: a fixed code",
            "organization.login",
            "The login of a provider organization",
            "git_commit.on_behalf_of_login",
            "The login of the organization on whose behalf a web commit was made",
            "llm_model.operated_by",
            "Who operates the model: the own organisation or the provider",
            "workspace_llm_model.operated_by",
            "Who operates the model: the own organisation or the provider",
            "workspace.public_activity_contributors_without_account",
            "Whether the public activity page names contributors without an account: a workspace setting");

    @Test
    void everyTableIsEitherMappedOrDeclaredFreeOfPersonalData() throws IOException {
        Set<String> mapped = codeSpans();
        List<String> unclassified = new ArrayList<>();
        for (String table : tableNames()) {
            if (!mapped.contains(table) && !NOT_PERSONAL_DATA.contains(table)) {
                unclassified.add(table);
            }
        }

        assertThat(unclassified)
                .as("tables named in neither personal-data-map.mdx nor NOT_PERSONAL_DATA")
                .isEmpty();
    }

    @Test
    void noAllowlistedTableHasBeenRenamedAway() {
        assertThat(NOT_PERSONAL_DATA)
                .as("NOT_PERSONAL_DATA entries that are no longer a table")
                .isSubsetOf(tableNames());
    }

    @Test
    void everyCitedEvidenceFileExists() throws IOException {
        List<String> missing = codeSpans().stream()
                .filter(span -> span.endsWith(".java"))
                .filter(span -> !Files.exists(REPOSITORY_ROOT.resolve(span)))
                .toList();

        assertThat(missing)
                .as("evidence cited by personal-data-map.mdx that no longer exists")
                .isEmpty();
    }

    @Test
    void everyPersonalStoreHasImplementedExportAndErasureCitations() throws IOException {
        String map = Files.readString(MAP);
        Set<String> declared = new TreeSet<>();
        List<String> inventory = implementationRows(map);
        for (Path source : productionSources()) {
            if (!Files.readString(source).contains("@PersonDataStores")) continue;
            Class<?> owner = sourceClass(source);
            PersonDataStores declaration = owner.getDeclaredAnnotation(PersonDataStores.class);
            assertThat(declaration)
                    .as("runtime store declaration on %s", source)
                    .isNotNull();
            assertThat(PersonDataCatalog.class.isAssignableFrom(owner))
                    .as("store owner implements PersonDataCatalog: %s", source)
                    .isTrue();
            String citation = "`server/application/" + source.toString().replace('\\', '/') + "`";
            for (String store : declaration.value()) {
                assertThat(declared.add(store))
                        .as("single contributor owner for %s", store)
                        .isTrue();
                List<String> rows = inventory.stream()
                        .filter(line -> inventoryStores(line).contains(store))
                        .toList();
                assertThat(rows)
                        .as("export and erasure implementation citations for %s", store)
                        .singleElement()
                        .satisfies(row -> {
                            String[] cells = row.split("\\|", -1);
                            assertThat(cells.length).isGreaterThanOrEqualTo(4);
                            assertThat(cells[2]).contains(citation);
                            assertThat(cells[3]).contains(citation);
                        });
            }
        }
        Set<String> documented = new TreeSet<>();
        for (String row : inventory) {
            for (String store : inventoryStores(row)) {
                assertThat(documented.add(store))
                        .as("single inventory row for %s", store)
                        .isTrue();
            }
        }
        assertThat(documented)
                .as("the implementation inventory matches the runtime declarations")
                .containsExactlyInAnyOrderElementsOf(declared);
        Set<String> required = tableNames();
        required.removeAll(NOT_PERSONAL_DATA);
        required.removeAll(SECURITY_RETENTION.keySet());
        assertThat(declared)
                .as("every personal table must have a full contributor, not an operator path")
                .containsAll(required);
    }

    /**
     * A person column of a catalogued table needs an erasure action that reaches it. A store that selects the person's
     * rows through the column deletes them, or its redaction assigns the column. In the catalog's package, an UPDATE of
     * the table that assigns the column counts, and so does a DELETE from the table whose WHERE names it. Otherwise {@link #PERSON_COLUMNS_OUTSIDE_THE_CATALOG} states
     * what erases or keeps it. A mention in a comment or an export projection is no action: a catalog that selects rows
     * by their owner's {@code account_id} would leave a second person's reference, such as a reviewer's, in place.
     */
    @Test
    void everyPersonColumnOfACataloguedTableHasAnErasureAction() throws IOException {
        var catalogs = catalogSources();
        var erasures = new ArrayList<Erasure>();
        for (var store : registry.stores())
            jdbcStore(store, 3).map(PersonalDataMapArchTest::erasure).ifPresent(erasures::add);
        for (String source : catalogs.values()) erasures.addAll(statementErasures(source));
        var unerased = unerasedColumns(personColumns(), catalogs.keySet(), erasures);
        assertThat(unerased)
                .as(
                        "person columns that no erasure action reaches: erase or redact them in the catalog, or state why."
                                + " Erasures of their tables: %s",
                        erasures.stream()
                                .filter(erasure ->
                                        unerased.stream().anyMatch(column -> column.startsWith(erasure.table() + ".")))
                                .toList())
                .isEmpty();
        assertThat(PERSON_COLUMNS_OUTSIDE_THE_CATALOG.keySet())
                .as("PERSON_COLUMNS_OUTSIDE_THE_CATALOG entries that are no longer a person column")
                .isSubsetOf(personColumns());
    }

    @Test
    void aPersonColumnThatOnlyACommentOrAnExportNamesHasNoErasureAction() {
        var columns = Set.of("conversation.account_id", "conversation.reviewer_account_id");
        var ownerStore = erasure(new JdbcPersonDataStore(
                mock(NamedParameterJdbcTemplate.class),
                new ObjectMapper(),
                "conversation",
                "conversation",
                "t.account_id = :account",
                "id,account_id,reviewer_account_id",
                "id",
                "account_id=NULL",
                0));
        String mentions = """
                class Catalog {
                    // reviewer_account_id goes with the conversation.
                    String export = "SELECT reviewer_account_id FROM conversation WHERE account_id = :account";
                }
                """;
        var mentioned = new ArrayList<Erasure>(statementErasures(mentions));
        mentioned.add(ownerStore);
        assertThat(unerasedColumns(columns, Set.of("conversation"), mentioned))
                .containsExactly("conversation.reviewer_account_id");

        mentioned.addAll(
                statementErasures(
                        "String touch = \"UPDATE conversation SET last_activity_at = now() WHERE reviewer_account_id = :reviewer\";"));
        assertThat(unerasedColumns(columns, Set.of("conversation"), mentioned))
                .as("an update that only selects by the column leaves the reference in place")
                .containsExactly("conversation.reviewer_account_id");

        var cleared = new ArrayList<Erasure>(mentioned);
        cleared.addAll(
                statementErasures(
                        "String erase = \"UPDATE conversation SET reviewer_account_id = NULL WHERE reviewer_account_id = :account\";"));
        assertThat(unerasedColumns(columns, Set.of("conversation"), cleared)).isEmpty();
        var deleted = new ArrayList<Erasure>(mentioned);
        deleted.addAll(statementErasures(
                "String delete = \"DELETE FROM conversation WHERE reviewer_account_id = :account\";"));
        assertThat(unerasedColumns(columns, Set.of("conversation"), deleted)).isEmpty();
    }

    /** Person columns of catalogued tables that something other than their catalog erases, or that erasure keeps. */
    private static final Map<String, String> PERSON_COLUMNS_OUTSIDE_THE_CATALOG = Map.of(
            "account.primary_email",
            "AccountPurger clears it on the account row, which erasure keeps as a tombstone",
            "commit_contributor.email",
            "The email of the contributor that user_id names. The row goes when that user is erased",
            "product_feedback_filing.mention_login",
            "The reporter's own login. The row goes when the reporter's report is erased",
            "repository.name_with_owner",
            "A repository's owner and name: the namespace of its source, kept as SOURCE_NAMESPACE keeps it",
            "auth_event.identity_id",
            "Erasure deletes the identity, and sfk_auth_event_identity sets this column NULL",
            "product_feedback_filing.publish_requested_by",
            "A Hephaestus maintainer's GitHub login on another person's report. It goes when that report is erased");

    /**
     * What an erasure reaches in a table: the columns that it assigns, or the columns by which it selects the rows that
     * it deletes.
     */
    private record Erasure(String table, String assigned, String deletedBy) {}

    /** A store deletes the rows that its predicate selects, or assigns the columns of its redaction on them. */
    private static Erasure erasure(JdbcPersonDataStore store) {
        String redaction = storeField(store, "redaction");
        String predicate = storeField(store, "predicate");
        String table = storeField(store, "table");
        return redaction.isEmpty() ? new Erasure(table, "", predicate) : new Erasure(table, redaction, "");
    }

    /** The JDBC store that a contributor is, or that it delegates to, such as a store that a wrapper fences. */
    private static Optional<JdbcPersonDataStore> jdbcStore(Object contributor, int depth) {
        if (contributor instanceof JdbcPersonDataStore store) return Optional.of(store);
        if (depth == 0) return Optional.empty();
        for (Class<?> type = contributor.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!PersonDataContributor.class.isAssignableFrom(field.getType())) continue;
                ReflectionUtils.makeAccessible(field);
                Object delegate = ReflectionUtils.getField(field, contributor);
                if (delegate != null) return jdbcStore(delegate, depth - 1);
            }
        }
        return Optional.empty();
    }

    /** A field of the store itself: a subclass may declare its own field of the same name. */
    private static String storeField(JdbcPersonDataStore store, String name) {
        Field field = Objects.requireNonNull(ReflectionUtils.findField(JdbcPersonDataStore.class, name));
        ReflectionUtils.makeAccessible(field);
        return (String) Objects.requireNonNull(ReflectionUtils.getField(field, store));
    }

    private static final Pattern UPDATE = Pattern.compile(
            "\\bupdate\\s+(?:only\\s+)?\"?(\\w+)\"?.*?\\bset\\b(.*)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DELETE = Pattern.compile(
            "\\bdelete\\s+from\\s+(?:only\\s+)?\"?(\\w+)\"?.*?\\bwhere\\b(.*)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern WHERE = Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE);

    /** The SET list of an UPDATE: up to its last WHERE, since a subquery inside the list has a WHERE of its own. */
    private static String setList(String tail) {
        var where = WHERE.matcher(tail);
        int end = tail.length();
        while (where.find()) end = where.start();
        return tail.substring(0, end);
    }

    /** The UPDATE and DELETE statements of a source file, by what they reach. */
    private static List<Erasure> statementErasures(String source) {
        var erasures = new ArrayList<Erasure>();
        for (String sql : SourceSql.statements(source)) {
            var update = UPDATE.matcher(sql);
            if (update.find())
                erasures.add(new Erasure(update.group(1).toLowerCase(Locale.ROOT), setList(update.group(2)), ""));
            var delete = DELETE.matcher(sql);
            if (delete.find()) erasures.add(new Erasure(delete.group(1).toLowerCase(Locale.ROOT), "", delete.group(2)));
        }
        return erasures;
    }

    private static List<String> unerasedColumns(
            Set<String> personColumns, Set<String> catalogued, List<Erasure> erasures) {
        return personColumns.stream()
                .filter(column -> {
                    String table = column.substring(0, column.indexOf('.'));
                    String name = column.substring(column.indexOf('.') + 1);
                    return catalogued.contains(table)
                            && erasures.stream().noneMatch(erasure -> erases(erasure, table, name))
                            && !PERSON_COLUMNS_OUTSIDE_THE_CATALOG.containsKey(column)
                            && !NOT_A_PERSON.containsKey(column);
                })
                .sorted()
                .toList();
    }

    private static boolean erases(Erasure erasure, String table, String column) {
        return table.equals(erasure.table())
                && (Pattern.compile("\\b" + column + "\\s*=")
                                .matcher(erasure.assigned())
                                .find()
                        || names(erasure.deletedBy(), column));
    }

    private static boolean names(String sql, String identifier) {
        return Pattern.compile("\\b" + identifier + "\\b").matcher(sql).find();
    }

    /**
     * The sources of each store's catalog package, by store: the class that declares {@code @PersonDataStores} and the
     * contributors beside it.
     */
    private static Map<String, String> catalogSources() throws IOException {
        Map<String, String> sources = new HashMap<>();
        for (Path source : productionSources()) {
            if (!Files.readString(source).contains("@PersonDataStores")) continue;
            PersonDataStores declaration = sourceClass(source).getDeclaredAnnotation(PersonDataStores.class);
            if (declaration == null) continue;
            var text = new StringBuilder();
            try (var siblings = Files.list(source.getParent())) {
                for (Path sibling : siblings.filter(path -> path.toString().endsWith(".java"))
                        .toList()) text.append(Files.readString(sibling));
            }
            for (String store : declaration.value()) sources.merge(store, text.toString(), String::concat);
        }
        return sources;
    }

    @Test
    void proseAndImplementationCitationsDoNotDeclareStoreOwnership() {
        String map = "| Store | Person export implementation | Person erasure implementation |\n"
                + "|---|---|---|\n"
                + "| `owned_store` | `Owner.java` mentions `unowned_store` | `Owner.java` |\n"
                + "\n### Notes\n"
                + "| `unowned_store` | `Owner.java` | `Owner.java` |\n";
        assertThat(implementationRows(map)).hasSize(1);
        assertThat(inventoryStores(implementationRows(map).getFirst())).containsExactly("owned_store");
    }

    private static List<String> implementationRows(String map) {
        String header = "| Store | Person export implementation | Person erasure implementation |";
        int start = map.indexOf(header);
        assertThat(start).as("dedicated person-store implementation inventory").isNotNegative();
        return map.substring(start + header.length())
                .stripLeading()
                .lines()
                .takeWhile(line -> line.startsWith("|"))
                .filter(line -> line.startsWith("| `"))
                .toList();
    }

    private static List<String> inventoryStores(String row) {
        Matcher spans = CODE_SPAN.matcher(row.split("\\|", -1)[1]);
        return spans.results().map(match -> match.group(1)).toList();
    }

    private static Class<?> sourceClass(Path source) {
        String className = "de.tum.cit.aet.hephaestus."
                + PRODUCTION_SOURCES
                        .relativize(source)
                        .toString()
                        .replace('/', '.')
                        .replace('\\', '.')
                        .replace(".java", "");
        try {
            return Class.forName(className, false, PersonalDataMapArchTest.class.getClassLoader());
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Cannot inspect a production class", exception);
        }
    }

    private static Set<String> codeSpans() throws IOException {
        Set<String> spans = new TreeSet<>();
        Matcher matcher = CODE_SPAN.matcher(Files.readString(MAP));
        while (matcher.find()) {
            spans.add(matcher.group(1));
        }
        return spans;
    }

    @Test
    void noTableDeclaredFreeOfPersonalDataReferencesAPerson() {
        assertThat(Stream.concat(SOURCE_NAMESPACE.stream(), NOT_A_PERSON.keySet().stream()))
                .as("excepted columns that the schema still has")
                .allSatisfy(column -> assertThat(jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public'"
                                        + " AND table_name || '.' || column_name = ?",
                                Long.class,
                                column))
                        .isOne());
        assertThat(personColumns().stream()
                        .filter(column -> NOT_PERSONAL_DATA.contains(column.substring(0, column.indexOf('.'))))
                        .filter(column -> !SOURCE_NAMESPACE.contains(column) && !NOT_A_PERSON.containsKey(column))
                        .toList())
                .as("person-referencing columns in tables that NOT_PERSONAL_DATA declares free of personal data")
                .isEmpty();
    }

    /** Every table in the migrated schema, mapped or native, except Liquibase's and ShedLock's bookkeeping. */
    private Set<String> tableNames() {
        return new TreeSet<>(jdbc.queryForList("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                  AND c.relname NOT IN ('databasechangelog', 'databasechangeloglock', 'shedlock')
                """, String.class));
    }

    /**
     * Columns, as {@code table.column}, that may reference a person: a foreign key to an account, a
     * contributor profile, an identity or a membership's account, a name that says it holds one even
     * without a foreign key, or the source namespace. A name is a heuristic, and a key that embeds an
     * id inside a text value is invisible to it.
     */
    private Set<String> personColumns() {
        return new TreeSet<>(jdbc.queryForList("""
                SELECT t.relname || '.' || a.attname
                FROM pg_class t JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum > 0 AND NOT a.attisdropped
                WHERE n.nspname = 'public' AND t.relkind IN ('r', 'p') AND NOT t.relispartition
                  AND t.relname NOT IN ('databasechangelog', 'databasechangeloglock', 'shedlock')
                  AND (a.attname ~ '(^|_)(account|user|actor|author|creator|developer|requester|owner|sender|recipient|assignee|reviewer|member|subject)(_ids?)?$'
                    OR a.attname ~ '(^|_)(login|email|ip|ip_address)$'
                    OR a.attname ~ '_by(_[a-z]+)?(_ids?)?$'
                    OR EXISTS (SELECT 1 FROM pg_constraint fk
                        JOIN pg_class parent ON parent.oid = fk.confrelid
                        JOIN LATERAL unnest(fk.conkey, fk.confkey) k(child_key, parent_key) ON true
                        JOIN pg_attribute pa ON pa.attrelid = parent.oid AND pa.attnum = k.parent_key
                        WHERE fk.contype = 'f' AND fk.conrelid = t.oid AND k.child_key = a.attnum
                          AND (parent.relname IN ('account', 'user', 'identity')
                            OR (parent.relname = 'membership' AND pa.attname = 'account_id'))))
                """, String.class));
    }

    private static List<Path> productionSources() throws IOException {
        try (var paths = Files.walk(PRODUCTION_SOURCES)) {
            return paths.filter(candidate -> candidate.toString().endsWith(".java"))
                    .toList();
        }
    }
}
