package de.tum.cit.aet.hephaestus.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * The connections that the server holds as a whole session, not for one transaction (docs/contributor/transactions.mdx
 * § 6). A transaction-mode connection pooler cannot carry them, and the chart's connection budget counts the
 * {@code LISTEN} sessions.
 */
class DatabaseSessionArchitectureTest extends HephaestusArchitectureTest {

    /** Each opens one {@code LISTEN} session of its own, outside the pool. */
    private static final Set<String> LISTENERS = Set.of(
            "de.tum.cit.aet.hephaestus.agent.adapter.EvidenceErasureNotifications",
            "de.tum.cit.aet.hephaestus.agent.proxy.LlmPoolNotifications",
            "de.tum.cit.aet.hephaestus.agent.run.AgentRunNotifications",
            "de.tum.cit.aet.hephaestus.conversation.ConversationLedgerNotifications",
            "de.tum.cit.aet.hephaestus.integration.core.sync.push.SyncPushService");

    /** Each holds a pooled connection as a session for its session lock. */
    private static final Set<String> POOLED_SESSIONS = Set.of(
            "de.tum.cit.aet.hephaestus.core.lock.ClusterLock",
            "de.tum.cit.aet.hephaestus.core.privacy.PostgresPersonDataCopyFence");

    /** The migration, which takes its own connections outside the pool while the schema changes. */
    private static final Set<String> MIGRATION = Set.of(
            "de.tum.cit.aet.hephaestus.core.database.MigrationLock",
            "de.tum.cit.aet.hephaestus.core.database.BaselineSynchronization");

    /** Sets session settings on a migration connection that it is given, and resets them. */
    private static final String MIGRATION_SETTINGS = "de.tum.cit.aet.hephaestus.core.database.SchemaFingerprint";

    /**
     * SQL that changes the session: a session advisory lock, {@code LISTEN}, or a setting that outlives its transaction.
     * {@code SET CONSTRAINTS} and {@code SET TRANSACTION} end with their transaction.
     */
    private static final Pattern SESSION_SQL = Pattern.compile(
            "pg_(try_)?advisory_(un)?lock(_shared)?\\b|\"LISTEN |\"SET (?!(LOCAL|CONSTRAINTS|TRANSACTION)\\b)|set_config\\([^)]*,\\s*false\\s*\\)");

    private static final Path SOURCES = Path.of("src/main/java");

    private static final Path CHART_HELPERS = Path.of("../../deploy/chart/hephaestus/templates/_helpers.tpl");

    @Test
    void onlyTheListenersOpenASessionOfTheirOwn() {
        assertThat(callersOf(DriverManager.class)).isEqualTo(LISTENERS);
    }

    @Test
    void onlyTheSessionLocksAndTheMigrationTakeAConnectionFromADataSource() {
        assertThat(union(callersOf(DataSource.class), callersOf(DataSourceUtils.class)))
                .isEqualTo(union(POOLED_SESSIONS, MIGRATION));
    }

    @Test
    void onlyTheSessionHoldersWriteSqlThatChangesTheSession() throws IOException {
        Set<String> writers;
        try (var files = Files.walk(SOURCES)) {
            writers = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> SESSION_SQL.matcher(read(file)).find())
                    .map(file -> SOURCES.relativize(file)
                            .toString()
                            .replace(".java", "")
                            .replace('/', '.'))
                    .collect(Collectors.toSet());
        }

        assertThat(writers)
                .isSubsetOf(union(union(LISTENERS, POOLED_SESSIONS), union(MIGRATION, Set.of(MIGRATION_SETTINGS))));
    }

    @Test
    void theChartBudgetsOneSessionForEachListener() throws IOException {
        var budget = Pattern.compile("\\$listeners := mul (\\d+) \\$servers").matcher(Files.readString(CHART_HELPERS));

        assertThat(budget.find()).isTrue();
        assertThat(Integer.parseInt(budget.group(1))).isEqualTo(LISTENERS.size());
    }

    /** Classes that call or reference {@code getConnection} of {@code owner}, by the class that declares the code. */
    private static Set<String> callersOf(Class<?> owner) {
        return classes.stream()
                .filter(type -> type.getCodeUnitAccessesFromSelf().stream()
                        .anyMatch(access -> access.getTargetOwner().isAssignableTo(owner)
                                && access.getName().equals("getConnection")))
                .map(DatabaseSessionArchitectureTest::topLevelName)
                .collect(Collectors.toSet());
    }

    /** A lambda or anonymous class belongs to the class that declares it. */
    private static String topLevelName(JavaClass type) {
        var name = type.getName();
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        return Stream.concat(first.stream(), second.stream()).collect(Collectors.toSet());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
