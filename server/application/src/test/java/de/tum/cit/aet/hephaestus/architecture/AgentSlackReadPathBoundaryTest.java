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
 * Guards the structural inversion of the Slack conversation read path (ADR: "invert the Slack conversation
 * read-path off agent's raw-JDBC tunnel").
 *
 * <p>The {@code agent} module consumes settled Slack conversation threads only through the agent-owned
 * {@code agent.conversation} SPIs ({@code ConversationThreadProjection} / {@code ConversationSourceLiveness} /
 * {@code ConversationCandidateSource}), which {@code integration.slack} implements. The agent must therefore:
 *
 * <ul>
 *   <li>never import an {@code integration.slack} type (the Modulith import-check already forbids this for named
 *       interfaces, but this rule pins it explicitly for the whole bounded context), and</li>
 *   <li>never open a raw-SQL tunnel back into Slack's private schema — neither via {@code JdbcTemplate} (the exact
 *       mechanism the old tunnel used to evade the tenancy {@code StatementInspector} AND the Modulith import
 *       check, which sees Java imports, not SQL strings) nor via a raw SQL string naming a {@code slack_*}
 *       table.</li>
 * </ul>
 *
 * <p>With this in place a column rename in Slack becomes a compile error <em>inside Slack</em>, not a silent
 * runtime break in the agent.
 */
class AgentSlackReadPathBoundaryTest extends HephaestusArchitectureTest {

    private static final String AGENT = "de.tum.cit.aet.hephaestus.agent..";

    /** SQL keyword immediately preceding a Slack table — precise enough to ignore job-metadata JSON keys
     * ({@code "slack_thread_ts"}) and javadoc mentions ({@code {@code slack_thread}}). {@code \\s+} spans newlines
     * so it also catches multi-line text-block SQL. */
    private static final Pattern SLACK_TABLE_IN_SQL =
            Pattern.compile("(?is)\\b(from|join|into|update)\\s+slack_(thread|message|monitored_channel)\\b");

    @Test
    @DisplayName("the slack_* SQL detector matches real tunnels and ignores JSON-key / javadoc lookalikes")
    void patternMatchesTunnelsAndIgnoresLookalikes() {
        // Self-test of the guard's regex: if a future weakening made it match nothing (or match the lookalikes
        // vacuously), agentSourcesDoNotNameSlackTablesInSql would silently pass forever. This pins that the detector
        // still fires on a reintroduced raw-SQL Slack tunnel and still ignores the two things that must NOT trip it.
        assertThat(SLACK_TABLE_IN_SQL
                        .matcher("SELECT 1 FROM slack_thread WHERE workspace_id = ?")
                        .find())
                .isTrue();
        assertThat(SLACK_TABLE_IN_SQL.matcher("... JOIN slack_message m ON ...").find())
                .isTrue();
        assertThat(SLACK_TABLE_IN_SQL
                        .matcher("UPDATE slack_monitored_channel SET consent_state = 'ACTIVE'")
                        .find())
                .isTrue();
        assertThat(SLACK_TABLE_IN_SQL
                        .matcher("INSERT INTO slack_message (workspace_id) VALUES (?)")
                        .find())
                .isTrue();

        // Lookalikes that must NOT match: a JSON metadata key and a javadoc {@code} mention.
        assertThat(SLACK_TABLE_IN_SQL
                        .matcher("payload.path(\"slack_thread_ts\").asString()")
                        .find())
                .isFalse();
        assertThat(SLACK_TABLE_IN_SQL
                        .matcher(" * reads the {@code slack_thread} aggregate.")
                        .find())
                .isFalse();
    }

    @Test
    @DisplayName("agent must not import any integration.slack type")
    void agentDoesNotImportSlack() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(AGENT)
                .should()
                .dependOnClassesThat()
                .resideInAPackage("de.tum.cit.aet.hephaestus.integration.slack..")
                .because(
                        "The agent conversation read path is inverted through the agent-owned agent.conversation SPIs "
                                + "(implemented by integration.slack); the agent must never depend on the Slack module directly");
        rule.check(classes);
    }

    @Test
    @DisplayName("agent must not use JdbcTemplate (the raw-JDBC tunnel vector)")
    void agentDoesNotUseJdbcTemplate() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(AGENT)
                .and()
                .doNotImplement(de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog.class)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
                .because(
                        "Raw JdbcTemplate is how the agent used to tunnel into Slack's private tables past the tenancy "
                                + "StatementInspector and the Modulith import check; the agent reads its own storage via JPA "
                                + "repositories and reaches Slack only through the agent.conversation SPIs. Registered person-data "
                                + "adapters are the instance-wide erasure boundary; their mapped ownership and private-source "
                                + "SQL prohibition are checked separately");
        rule.check(classes);
    }

    @Test
    @DisplayName("no agent source names a slack_* table in a raw SQL string")
    void agentSourcesDoNotNameSlackTablesInSql() {
        Path agentDir = Path.of("src/main/java/de/tum/cit/aet/hephaestus/agent");
        if (!Files.isDirectory(agentDir)) {
            fail("Could not locate the agent source directory at %s (cwd=%s)"
                    .formatted(agentDir, Path.of("").toAbsolutePath()));
        }
        List<String> offenders;
        try (Stream<Path> paths = Files.walk(agentDir)) {
            offenders = paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(AgentSlackReadPathBoundaryTest::namesSlackTableInSql)
                    .map(Path::toString)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(offenders)
                .as("agent source files must not embed raw SQL against Slack's private tables "
                        + "(slack_thread/slack_message/slack_monitored_channel) — read them through the "
                        + "agent.conversation SPIs implemented by integration.slack")
                .isEmpty();
    }

    private static boolean namesSlackTableInSql(Path javaFile) {
        try {
            String content = Files.readString(javaFile);
            Matcher m = SLACK_TABLE_IN_SQL.matcher(content);
            return m.find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
