package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.SecurityRetentionDeletionProof;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Makes {@code docs/admin/dsms/personal-data-map.md} enforceable: a new table has to be classified
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

    private static final Path REPOSITORY_ROOT = Path.of("../..");
    private static final Path PRODUCTION_SOURCES = Path.of("src/main/java/de/tum/cit/aet/hephaestus");
    private static final Path MAP = REPOSITORY_ROOT.resolve("docs/admin/dsms/personal-data-map.md");

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
            // Digests of what a compose run's claim was given and served, and its tool call IDs and names, never the
            // content; it goes with its run.
            "composer_transcript_part",
            "commit_pull_request",
            "connection",
            "connection_activity",
            "fx_rate",
            "identity_provider",
            "issue_blocking",
            "issue_label",
            "issue_type",
            "jwt_signing_key",
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
                .as("tables named in neither personal-data-map.md nor NOT_PERSONAL_DATA")
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
                .as("evidence cited by personal-data-map.md that no longer exists")
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
