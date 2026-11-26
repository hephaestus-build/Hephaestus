package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.tum.cit.aet.hephaestus.core.privacy.PersonDataRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.ErasesColumns;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SecurityRetentionDeletionProof;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import tools.jackson.databind.JsonNode;
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

    @Autowired
    private List<PersonDataCatalog> catalogs;

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
            // A connection id, credential generation and deadline. No credential value or person.
            "provider_credential_pause",
            // GitHub locators left after a product feedback erasure: repository, installation, a random marker.
            "product_feedback_cleanup",
            // What each practice script of a job did: a status, counts and model slots, no content or person.
            "review_job_script_run",
            // A run's queue state: its kind, band, subject key, epoch and times, no content or person; an erasure
            // deletes the runs of the jobs it deletes.
            "agent_run",
            // A sandbox's subject key, claim name and times: a pointer to its subject, like a run's, and no content.
            "agent_sandbox",
            // A thread's inbox item: its kind, dedupe key, state, reason, times and the IDs its producer needs, never
            // text. Deleting the conversation deletes its items.
            "inbox_item",
            "commit_pull_request",
            "connection",
            "connection_activity",
            "fx_rate",
            "identity_provider",
            "issue_blocking",
            "issue_label",
            "privacy_journal_backup_horizon", // Only dates from the instance backup job.
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
            // Rounded instance totals of a half-year that passed the floors of 5 people and 5 workspaces (ADR 0153).
            "usage_release",
            "provider_event_step", // Event ids and step names contain no person data.
            "repository_to_monitor",
            "attempt_public_key",
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
     * The namespace of a source, like a repository's {@code owner/name}: the name of the provider account that a
     * connection is installed on, and the repository that a workspace monitors. It is a person's login when the account
     * is a personal one. Person export lists it, and it stays while the source exists.
     */
    private static final Set<String> SOURCE_NAMESPACE =
            Set.of("connection.display_name", "repository_to_monitor.name_with_owner");

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
        assertThat(withoutRuntimeStore(required, registry.stores()))
                .as("personal tables that a declaration names but no registered store erases")
                .isEmpty();
    }

    @Test
    void aTableThatOnlyADeclarationNamesHasNoRuntimeStore() {
        var stores = List.<PersonDataContributor>of(store("t.account_id = :account", ""));
        assertThat(withoutRuntimeStore(Set.of("conversation", "export_blob"), stores))
                .containsExactly("export_blob");
    }

    /** The required tables that no store in the registry erases: a declaration alone erases nothing. */
    private static List<String> withoutRuntimeStore(
            Set<String> required, List<? extends PersonDataContributor> stores) {
        var covered = new TreeSet<String>();
        for (var store : stores) {
            covered.add(store.store());
            jdbcStore(store, 3).ifPresent(jdbc -> covered.add(storeField(jdbc, "table")));
        }
        return required.stream()
                .filter(table -> !covered.contains(table))
                .sorted()
                .toList();
    }

    /**
     * A person column of a catalogued table needs an erasure action of a registered store that reaches it. A JDBC store
     * reaches the columns that its redaction assigns, and, when it deletes, the columns that its predicate compares
     * with the person's scope. A catalog or a store whose erasure matches otherwise declares its columns with
     * {@link ErasesColumns}. Otherwise
     * {@link #PERSON_COLUMNS_OUTSIDE_THE_CATALOG} states what erases or keeps the column. A store selected by its owner's
     * {@code account_id} would otherwise leave a second person's reference, such as a reviewer's, in place.
     */
    @Test
    void everyPersonColumnOfACataloguedTableHasAnErasureAction() throws IOException {
        var reached = erasedColumns(registry.stores(), catalogs);
        var unerased = unerasedColumns(personColumns(), cataloguedTables(), reached);
        assertThat(unerased)
                .as("person columns that no registered erasure reaches: erase them in a store, or state why")
                .isEmpty();
        assertThat(PERSON_COLUMNS_OUTSIDE_THE_CATALOG.keySet())
                .as("PERSON_COLUMNS_OUTSIDE_THE_CATALOG entries that are no longer a person column")
                .isSubsetOf(personColumns());
    }

    @Test
    void aColumnThatOnlyAPredicateOrAnUnregisteredStoreNamesHasNoErasureAction() {
        var columns = Set.of("conversation.account_id", "conversation.reviewer_account_id");
        PersonDataContributor owners = store("t.account_id = :account AND t.reviewer_account_id IS NOT NULL", "");
        PersonDataContributor redacting = store("t.account_id = :account", "account_id=NULL, last_activity_at = now()");
        assertThat(unerasedColumns(
                        columns, Set.of("conversation"), erasedColumns(List.of(owners, redacting), List.of())))
                .as("an incidental predicate or a column that the redaction leaves alone erases nothing")
                .containsExactly("conversation.reviewer_account_id");

        PersonDataContributor byOwner = store("t.account_id = :account", "");
        PersonDataContributor reviewers = store("t.reviewer_account_id = :account", "");
        assertThat(unerasedColumns(
                        columns, Set.of("conversation"), erasedColumns(List.of(byOwner, reviewers), List.of())))
                .isEmpty();
        PersonDataContributor anyReviewer =
                store("t.account_id = :account AND t.reviewer_account_id IN (SELECT id FROM account)", "");
        assertThat(unerasedColumns(
                        columns, Set.of("conversation"), erasedColumns(List.of(byOwner, anyReviewer), List.of())))
                .as("a subquery that selects no part of the person's scope erases nothing")
                .containsExactly("conversation.reviewer_account_id");
        for (String hidden : List.of(
                "t.account_id = :account AND t.reviewer_account_id IN (SELECT id FROM account /* :account */)",
                "t.account_id = :account AND t.reviewer_account_id IN"
                        + " (SELECT id FROM account ORDER BY CASE WHEN id = :account THEN 0 ELSE 1 END)",
                "t.account_id = :account AND EXISTS (SELECT 1 FROM conversation c WHERE c.reviewer_account_id = :account)",
                "t.account_id = :account AND t.note = 'reviewer_account_id = :account'",
                "t.account_id = :account AND t.reviewer_account_id = :account",
                "(t.account_id = :account) AND (t.reviewer_account_id = :account)",
                "t.account_id = :account AND CASE WHEN t.reviewer_account_id = :account THEN true"
                        + " ELSE t.reviewer_account_id IS NOT NULL END",
                "t.account_id = :account OR CASE WHEN t.reviewer_account_id = :account THEN true ELSE false END",
                "CASE WHEN t.closed_at IS NOT NULL THEN t.account_id = :account OR t.reviewer_account_id = :account"
                        + " OR t.account_id = :users ELSE false END",
                "CASE WHEN t.\"end\" IS NOT NULL THEN t.account_id = :account OR t.reviewer_account_id = :account"
                        + " OR t.account_id = :users ELSE false END")) {
            assertThat(unerasedColumns(
                            columns,
                            Set.of("conversation"),
                            erasedColumns(List.of(byOwner, store(hidden, "")), List.of())))
                    .as("only a disjunct that is one comparison selects every row of its column: %s", hidden)
                    .containsExactly("conversation.reviewer_account_id");
        }
        PersonDataContributor anyOfThem =
                store("(t.reviewer_account_id = ANY(:users)) OR t.account_id IN (:account)", "");
        assertThat(scopedColumns("t.author_id = :account")).containsExactly("author_id");
        assertThat(scopedColumns("t.\"case\" = 2 OR t.reviewer_account_id = :account"))
                .as("a quoted identifier is a name, not a keyword")
                .containsExactly("reviewer_account_id");
        assertThat(
                        scopedColumns(
                                "t.vendor_id = :account OR (CASE WHEN t.ended THEN true END) OR t.reviewer_account_id = :account"))
                .as("an OR after a closed CASE is a top-level OR again")
                .containsExactly("reviewer_account_id", "vendor_id");
        assertThat(unerasedColumns(columns, Set.of("conversation"), erasedColumns(List.of(anyOfThem), List.of())))
                .isEmpty();
        PersonDataContributor clearing = store("t.reviewer_account_id IN (SELECT 1)", "reviewer_account_id=NULL");
        assertThat(unerasedColumns(
                        columns, Set.of("conversation"), erasedColumns(List.of(byOwner, clearing), List.of())))
                .isEmpty();

        assertThat(unerasedColumns(
                        columns,
                        Set.of("conversation"),
                        erasedColumns(List.of(byOwner, new DeclaringStore()), List.of())))
                .as("a registered store with its own erasure declares its columns")
                .isEmpty();
        assertThat(unerasedColumns(columns, Set.of("conversation"), erasedColumns(List.of(byOwner), List.of())))
                .as("a store that the registry does not hold erases nothing")
                .containsExactly("conversation.reviewer_account_id");
        assertThat(assignedColumns(
                        "a = (SELECT x FROM t WHERE y = 1), t.b = CASE WHEN c = 2 THEN NULL END WHERE reviewer = 1"))
                .containsExactly("a", "b");
    }

    /** Person columns of catalogued tables that something other than their catalog erases, or that erasure keeps. */
    private static final Map<String, String> PERSON_COLUMNS_OUTSIDE_THE_CATALOG = Map.of(
            "account.primary_email",
            "AccountPurger clears it on the account row, which erasure keeps as a tombstone",
            "conversation.slack_user_id",
            "The owner's own Slack user id. The row goes when its owner is erased",
            "commit_contributor.email",
            "The email of the contributor that user_id names. The row goes when that user is erased",
            "product_feedback_filing.mention_login",
            "The reporter's own login. The row goes when the reporter's report is erased",
            "repository.name_with_owner",
            "A repository's owner and name: the namespace of its source, kept as SOURCE_NAMESPACE keeps it",
            "auth_event.identity_id",
            "Erasure deletes the identity, and sfk_auth_event_identity sets this column NULL",
            "product_feedback_filing.publish_requested_by",
            "A Hephaestus maintainer's GitHub login on another person's report. It goes when that report is erased",
            "workspace.account_login",
            "The namespace of the workspace's repositories. InstallationAccountPersonDataStore replaces it in a purged"
                    + " workspace, which keeps its row");

    private static final Pattern TARGET = Pattern.compile("^\\s*(?:\\w+\\.)?\"?(\\w+)\"?\\s*=");
    private static final Pattern END_OF_SET = Pattern.compile("(?i)(where|from|returning)\\b");

    /** One comparison of a column of the store's own rows with a parameter: the whole of one disjunct. */
    private static final Pattern SCOPED = Pattern.compile(
            "(?:t\\.)?\"?(\\w+)\"?\\s*(?:=\\s*(?:ANY\\s*\\(\\s*:(\\w+)\\s*\\)|:(\\w+))|IN\\s*\\(\\s*:(\\w+)\\s*\\))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OR = Pattern.compile("(?i)\\bOR\\b");
    private static final Pattern CASE = Pattern.compile("(?i)\\bCASE\\b");
    private static final Pattern END = Pattern.compile("(?i)\\bEND\\b");

    /** The parameters of a person's frozen scope ({@code JdbcPersonDataStore#parameters}). */
    private static final Set<String> SCOPE_PARAMETERS =
            Set.of("account", "users", "identities", "conversations", "documents", "items", "derivedJobs");

    /** The {@code table.column} pairs that the registered stores and their catalogs erase. */
    private static Set<String> erasedColumns(List<? extends PersonDataContributor> stores, List<?> catalogs) {
        var erased = new TreeSet<String>();
        for (Object catalog : catalogs) erased.addAll(declared(catalog));
        for (var store : stores) {
            erased.addAll(declared(store));
            jdbcStore(store, 3).ifPresent(jdbc -> {
                String table = storeField(jdbc, "table");
                String redaction = storeField(jdbc, "redaction");
                for (String column : assignedColumns(redaction)) erased.add(table + "." + column);
                if (redaction.isEmpty())
                    for (String column : scopedColumns(storeField(jdbc, "predicate"))) erased.add(table + "." + column);
            });
        }
        return erased;
    }

    private static List<String> declared(Object owner) {
        ErasesColumns declaration = ClassUtils.getUserClass(owner).getAnnotation(ErasesColumns.class);
        return declaration == null ? List.of() : List.of(declaration.value());
    }

    /** A store with erasure code of its own, which declares the columns that it erases. */
    @ErasesColumns("conversation.reviewer_account_id")
    private static final class DeclaringStore implements PersonDataContributor {
        @Override
        public String store() {
            return "conversation_reviewer";
        }

        @Override
        public PersonDataSelection select(PersonScope person) {
            return new PersonDataSelection(List.of());
        }

        @Override
        public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
            return List.of();
        }

        @Override
        public long erase(PersonDataSelection selection, PersonScope frozenScope) {
            return 0;
        }
    }

    /**
     * The columns that the predicate selects by on their own. The store deletes every row that its predicate selects, so
     * a disjunct that is one comparison of a column with a scope parameter selects every row that names the person in
     * that column. A conjunction, a CASE, a subquery or an EXISTS can leave such a row behind, so a store that matches a
     * person that way declares its columns with {@link ErasesColumns}.
     */
    private static Set<String> scopedColumns(String predicate) {
        var columns = new TreeSet<String>();
        for (String disjunct : topLevelDisjuncts(SourceSql.normalized(predicate))) {
            var scoped = SCOPED.matcher(disjunct.strip());
            if (!scoped.matches()) continue;
            String parameter = scoped.group(2) != null
                    ? scoped.group(2)
                    : scoped.group(3) != null ? scoped.group(3) : scoped.group(4);
            if (SCOPE_PARAMETERS.contains(parameter))
                columns.add(scoped.group(1).toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    /** The parts of a predicate that a top-level OR separates, each without its enclosing parentheses. */
    private static List<String> topLevelDisjuncts(String predicate) {
        String whole = withoutEnclosingParentheses(predicate.strip());
        var disjuncts = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        // Transparent bounds keep the word boundary honest at the region start, so a column like author is no OR.
        var or = OR.matcher(whole).useTransparentBounds(true);
        var caseStart = CASE.matcher(whole).useTransparentBounds(true);
        var caseEnd = END.matcher(whole).useTransparentBounds(true);
        for (int index = 0; index < whole.length(); index++) {
            char next = whole.charAt(index);
            if (next == '"') {
                // A quoted identifier such as "end" or "or" is a name, never a keyword. "" escapes a quote.
                index = whole.indexOf('"', index + 1);
                if (index < 0) break;
                continue;
            }
            // A CASE expression nests like a parenthesis: an OR in one of its branches separates no disjunct.
            if (next == '(' || caseStart.region(index, whole.length()).lookingAt()) depth++;
            if (next == ')' || caseEnd.region(index, whole.length()).lookingAt()) depth--;
            if (depth == 0 && or.region(index, whole.length()).lookingAt()) {
                disjuncts.add(whole.substring(start, index));
                start = index + 2;
                index++;
            }
        }
        disjuncts.add(whole.substring(start));
        return disjuncts.stream()
                .map(disjunct -> withoutEnclosingParentheses(disjunct.strip()))
                .toList();
    }

    private static String withoutEnclosingParentheses(String sql) {
        while (sql.startsWith("(") && closingParenthesis(sql) == sql.length() - 1)
            sql = sql.substring(1, sql.length() - 1).strip();
        return sql;
    }

    private static int closingParenthesis(String sql) {
        int depth = 0;
        for (int index = 0; index < sql.length(); index++) {
            if (sql.charAt(index) == '(') depth++;
            if (sql.charAt(index) == ')' && --depth == 0) return index;
        }
        return -1;
    }

    private static JdbcPersonDataStore store(String predicate, String redaction) {
        return new JdbcPersonDataStore(
                mock(NamedParameterJdbcTemplate.class),
                new ObjectMapper(),
                "conversation",
                "conversation",
                predicate,
                "id",
                "id",
                redaction,
                0);
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

    /**
     * The columns that a SET list assigns. The list ends at the first WHERE, FROM or RETURNING outside parentheses and
     * quotes, so a predicate or a subquery assigns nothing.
     */
    static Set<String> assignedColumns(String setList) {
        var items = new ArrayList<String>();
        var item = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (int index = 0; index < setList.length(); index++) {
            char next = setList.charAt(index);
            if (next == '\'') quoted = !quoted;
            if (!quoted) {
                if (next == '(') depth++;
                if (next == ')') depth--;
                boolean wordStart = index == 0 || !Character.isLetterOrDigit(setList.charAt(index - 1));
                if (depth == 0
                        && wordStart
                        && END_OF_SET
                                .matcher(setList)
                                .region(index, setList.length())
                                .lookingAt()) break;
                if (depth == 0 && next == ',') {
                    items.add(item.toString());
                    item.setLength(0);
                    continue;
                }
            }
            item.append(next);
        }
        items.add(item.toString());
        var assigned = new TreeSet<String>();
        for (String assignment : items) {
            var target = TARGET.matcher(assignment);
            if (target.find()) assigned.add(target.group(1).toLowerCase(Locale.ROOT));
        }
        return assigned;
    }

    private static List<String> unerasedColumns(Set<String> personColumns, Set<String> catalogued, Set<String> erased) {
        return personColumns.stream()
                .filter(column -> catalogued.contains(column.substring(0, column.indexOf('.')))
                        && !erased.contains(column)
                        && !PERSON_COLUMNS_OUTSIDE_THE_CATALOG.containsKey(column)
                        && !NOT_A_PERSON.containsKey(column))
                .sorted()
                .toList();
    }

    /** The tables that a person-data store declares. */
    private static Set<String> cataloguedTables() throws IOException {
        var tables = new TreeSet<String>();
        for (Path source : productionSources()) {
            if (!Files.readString(source).contains("@PersonDataStores")) continue;
            PersonDataStores declaration = sourceClass(source).getDeclaredAnnotation(PersonDataStores.class);
            if (declaration != null) tables.addAll(List.of(declaration.value()));
        }
        return tables;
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
