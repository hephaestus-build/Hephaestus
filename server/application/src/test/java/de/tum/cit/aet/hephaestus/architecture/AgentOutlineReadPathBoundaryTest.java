package de.tum.cit.aet.hephaestus.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.tngtech.archunit.lang.ArchRule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the structural inversion of the Outline documentation read path.
 *
 * <p>The {@code agent} module consumes a workspace's mirrored Outline documents only through the agent-owned
 * {@code agent.documentation} SPI ({@code DocumentProjection}), which {@code integration.outline} implements. The
 * agent must therefore:
 *
 * <ul>
 *   <li>never import an {@code integration.outline} type (the Modulith import-check already forbids this for named
 *       interfaces, but this rule pins it explicitly for the whole bounded context), and</li>
 *   <li>never open a raw-SQL tunnel back into Outline's private schema through a raw SQL string naming the
 *       {@code outline_document} table — the Modulith import check sees Java imports, not SQL strings. The other
 *       tunnel, {@code JdbcTemplate}, is closed for every module by {@link CodeQualityTest}.</li>
 * </ul>
 *
 * <p>With this in place a column rename in Outline becomes a compile error <em>inside Outline</em>, not a silent
 * runtime break in the agent.
 */
class AgentOutlineReadPathBoundaryTest extends HephaestusArchitectureTest {

    private static final String AGENT = "de.tum.cit.aet.hephaestus.agent..";

    /** SQL keyword immediately preceding the Outline table — precise enough to ignore JSON keys and javadoc
     * mentions ({@code {@code outline_document}}). {@code \\s+} spans newlines so it also catches multi-line
     * text-block SQL. */
    private static final Pattern OUTLINE_TABLE_IN_SQL =
            Pattern.compile("(?is)\\b(from|join|into|update)\\s+outline_document\\b");

    @Test
    @DisplayName("the outline_document SQL detector matches real tunnels and ignores JSON-key / javadoc lookalikes")
    void patternMatchesTunnelsAndIgnoresLookalikes() {
        // Self-test of the guard's regex: if a future weakening made it match nothing (or match lookalikes
        // vacuously), agentSourcesDoNotNameOutlineTableInSql would silently pass forever. This pins that the
        // detector still fires on a reintroduced raw-SQL Outline tunnel and still ignores the two things that
        // must NOT trip it.
        assertThat(OUTLINE_TABLE_IN_SQL
                        .matcher("SELECT 1 FROM outline_document WHERE workspace_id = ?")
                        .find())
                .isTrue();
        assertThat(OUTLINE_TABLE_IN_SQL
                        .matcher("... JOIN outline_document d ON ...")
                        .find())
                .isTrue();
        assertThat(OUTLINE_TABLE_IN_SQL
                        .matcher("UPDATE outline_document SET body_markdown = NULL")
                        .find())
                .isTrue();
        assertThat(OUTLINE_TABLE_IN_SQL
                        .matcher("INSERT INTO outline_document (workspace_id) VALUES (?)")
                        .find())
                .isTrue();

        // Lookalikes that must NOT match: a JSON metadata key and a javadoc {@code} mention.
        assertThat(OUTLINE_TABLE_IN_SQL
                        .matcher("payload.path(\"outline_document_id\").asString()")
                        .find())
                .isFalse();
        assertThat(OUTLINE_TABLE_IN_SQL
                        .matcher(" * reads the {@code outline_document} mirror.")
                        .find())
                .isFalse();
    }

    @Test
    @DisplayName("agent must not import any integration.outline type")
    void agentDoesNotImportOutline() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(AGENT)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("de.tum.cit.aet.hephaestus.integration.outline..")
                .because(
                        "The agent documentation read path is inverted through the agent-owned agent.documentation SPI "
                                + "(implemented by integration.outline); the agent must never depend on the Outline module directly");
        rule.check(classes);
    }

    @Test
    @DisplayName("no agent source names the outline_document table in a raw SQL string")
    void agentSourcesDoNotNameOutlineTableInSql() {
        Path agentDir = Path.of("src/main/java/de/tum/cit/aet/hephaestus/agent");
        if (!Files.isDirectory(agentDir)) {
            fail("Could not locate the agent source directory at %s (cwd=%s)"
                    .formatted(agentDir, Path.of("").toAbsolutePath()));
        }
        List<String> offenders;
        try (Stream<Path> paths = Files.walk(agentDir)) {
            offenders = paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(AgentOutlineReadPathBoundaryTest::namesOutlineTableInSql)
                    .map(Path::toString)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(offenders)
                .as("agent source files must not embed raw SQL against Outline's private table (outline_document) — "
                        + "read it through the agent.documentation SPI implemented by integration.outline")
                .isEmpty();
    }

    private static boolean namesOutlineTableInSql(Path javaFile) {
        try {
            String content = Files.readString(javaFile);
            Matcher m = OUTLINE_TABLE_IN_SQL.matcher(content);
            return m.find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
