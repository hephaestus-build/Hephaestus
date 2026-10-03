package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import jakarta.persistence.Entity;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
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
            "practice",
            "practice_catalog_installation",
            "practice_group",
            "practice_review_repository_target",
            "practice_revision",
            "project_field",
            "pull_request_closing_issue",
            "pull_request_requested_team",
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
            if (name.find()) {
                tables.add(name.group(1));
            } else if (!usesParentSingleTable(path)) {
                tables.add(defaultTableName(path));
            }
        }
        return tables;
    }

    private static List<Path> productionSources() throws IOException {
        try (var paths = Files.walk(PRODUCTION_SOURCES)) {
            return paths.filter(candidate -> candidate.toString().endsWith(".java"))
                    .toList();
        }
    }

    private static boolean usesParentSingleTable(Path source) {
        Class<?> parent = sourceClass(source).getSuperclass();
        while (parent != null && parent.isAnnotationPresent(Entity.class)) {
            var inheritance = parent.getDeclaredAnnotation(Inheritance.class);
            if (inheritance != null) return inheritance.strategy() == InheritanceType.SINGLE_TABLE;
            parent = parent.getSuperclass();
        }
        return false;
    }

    /** Hibernate's default strategy when an entity declares no {@code @Table} name. */
    private static String defaultTableName(Path entity) {
        String className = entity.getFileName().toString().replace(".java", "");
        return CAMEL_BOUNDARY.matcher(className).replaceAll("_").toLowerCase(Locale.ROOT);
    }
}
