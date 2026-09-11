package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Makes {@code docs/admin/dsms/personal-data-map.md} enforceable: a new table has to be classified
 * before it can ship, and the map's evidence links cannot rot silently.
 *
 * <p>The authority is the mapped set — every {@code @Entity} plus every physical table an association
 * or secondary-table annotation creates — rather than the Liquibase chain, because the chain also
 * carries tables that later changelogs renamed or dropped and the drops inside {@code <rollback>}
 * blocks are indistinguishable from real ones.
 */
@Tag("architecture")
class PersonalDataMapArchTest {

    private static final Path REPOSITORY_ROOT = Path.of("../..");
    private static final Path PRODUCTION_SOURCES = Path.of("src/main/java/de/tum/cit/aet/hephaestus");
    private static final Path MAP = REPOSITORY_ROOT.resolve("docs/admin/dsms/personal-data-map.md");

    private static final Pattern ENTITY = Pattern.compile("(?m)^@Entity\\b");
    private static final Pattern TABLE_NAME =
            Pattern.compile("@Table\\s*\\(\\s*name\\s*=\\s*\"(?:\\\\\")?([a-z0-9_]+)");
    /** A mapping annotation that creates a physical table of its own, alongside the entity's. */
    private static final Pattern SECONDARY_TABLE_ANNOTATION =
            Pattern.compile("@(?:JoinTable|CollectionTable|SecondaryTable)\\s*\\(");

    private static final Pattern SECONDARY_TABLE_NAME =
            Pattern.compile("@(?:JoinTable|CollectionTable|SecondaryTable)\\s*\\(\\s*name\\s*=\\s*\"([a-z0-9_]+)\"");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<!^)(?=[A-Z])");
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
            "commit_pull_request",
            "connection",
            "connection_activity",
            "curated_group_override",
            "curated_practice_override",
            "discussion_category",
            "discussion_label",
            "fx_rate",
            "identity_provider",
            "issue_blocking",
            "issue_label",
            "issue_type",
            "jwt_signing_key",
            "label",
            "llm_connection",
            "llm_model",
            "llm_model_price",
            "llm_model_workspace_grant",
            "login_provider",
            "oauth_state_nonce",
            "practice",
            "practice_catalog_installation",
            "practice_group",
            "practice_review_repository_target",
            "practice_revision",
            "project_field",
            "repository_to_monitor",
            "worker_registry",
            "worker_token_denylist",
            "workspace",
            "workspace_agent_binding",
            "workspace_llm_connection",
            "workspace_llm_model",
            "workspace_slug_history",
            "workspace_team_label_filter",
            "workspace_team_repository_settings",
            "workspace_team_settings");

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
    void noAllowlistedTableHasBeenRenamedAway() throws IOException {
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

    /**
     * A {@code @JoinTable}, {@code @CollectionTable} or {@code @SecondaryTable} whose name is not the
     * annotation's first attribute is one the scanner cannot classify. JPA lets that name default from
     * the owning entity and attribute, and a table nobody can name is a table nobody classifies.
     */
    @Test
    void everySecondaryTableNamesItself() throws IOException {
        List<String> unnamed = new ArrayList<>();
        for (Path path : productionSources()) {
            String source = Files.readString(path);
            long annotations =
                    SECONDARY_TABLE_ANNOTATION.matcher(source).results().count();
            long named = SECONDARY_TABLE_NAME.matcher(source).results().count();
            if (annotations != named) {
                unnamed.add(path.toString());
            }
        }

        assertThat(unnamed)
                .as("sources whose @JoinTable/@CollectionTable/@SecondaryTable does not open with name = \"...\"")
                .isEmpty();
    }

    private static Set<String> codeSpans() throws IOException {
        Set<String> spans = new TreeSet<>();
        Matcher matcher = CODE_SPAN.matcher(Files.readString(MAP));
        while (matcher.find()) {
            spans.add(matcher.group(1));
        }
        return spans;
    }

    private static Set<String> tableNames() throws IOException {
        Set<String> tables = new TreeSet<>();
        for (Path path : productionSources()) {
            String source = Files.readString(path);
            Matcher secondary = SECONDARY_TABLE_NAME.matcher(source);
            while (secondary.find()) {
                tables.add(secondary.group(1));
            }
            if (!ENTITY.matcher(source).find()) {
                continue;
            }
            Matcher name = TABLE_NAME.matcher(source);
            tables.add(name.find() ? name.group(1) : defaultTableName(path));
        }
        return tables;
    }

    private static List<Path> productionSources() throws IOException {
        try (var paths = Files.walk(PRODUCTION_SOURCES)) {
            return paths.filter(candidate -> candidate.toString().endsWith(".java"))
                    .toList();
        }
    }

    /** Hibernate's default strategy when an entity declares no {@code @Table} name. */
    private static String defaultTableName(Path entity) {
        String className = entity.getFileName().toString().replace(".java", "");
        return CAMEL_BOUNDARY.matcher(className).replaceAll("_").toLowerCase(Locale.ROOT);
    }
}
