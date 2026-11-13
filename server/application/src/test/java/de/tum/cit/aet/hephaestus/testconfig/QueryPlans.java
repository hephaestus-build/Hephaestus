package de.tum.cit.aet.hephaestus.testconfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Finds the statements that PostgreSQL can answer only by reading a whole table, whatever its size.
 *
 * <p>Test tables are too small for real plans: on a few rows a sequential scan is the cheapest plan. With
 * {@code enable_seqscan} off, the planner takes any usable index, and PostgreSQL 18 marks a sequential scan that it
 * still had to use as {@code "Disabled": true}. A filtered scan that stays disabled has no index that serves its
 * predicate, for example a query that omits the predicate of a partial index. {@code EXPLAIN (GENERIC_PLAN)} plans the
 * statement with its parameters unbound, so the statements that a test captured can be planned as they are.
 */
public class QueryPlans {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** PostgreSQL cannot type a parameter without bound values, for example in {@code ? IS NULL}. */
    private static final Set<String> UNTYPED_PARAMETER = Set.of("42P18", "42P08");

    private final Environment environment;

    public QueryPlans(Environment environment) {
        this.environment = environment;
    }

    /** One sequential scan that no index could replace, with the statement that needs it. */
    public record FullScan(String table, String filter, String sql) {}

    /**
     * Plans each distinct statement and returns the filtered sequential scans that no index could replace. A statement
     * with a parameter whose type PostgreSQL cannot infer without a value is skipped. Any other planning error fails, and
     * so does a list in which no statement could be planned.
     */
    public List<FullScan> fullScans(Collection<String> statements) {
        List<FullScan> scans = new ArrayList<>();
        // pgjdbc sends even a plain statement through the extended protocol, where the server reads $1 as a protocol
        // parameter that has no value. The simple query protocol passes the placeholders to EXPLAIN as text.
        Properties settings = new Properties();
        settings.setProperty("user", environment.getRequiredProperty("spring.datasource.username"));
        settings.setProperty("password", environment.getRequiredProperty("spring.datasource.password"));
        settings.setProperty("preferQueryMode", "simple");
        try (Connection connection = DriverManager.getConnection(
                        environment.getRequiredProperty("spring.datasource.url"), settings);
                Statement statement = connection.createStatement()) {
            requirePostgres18(statement);
            statement.execute("SET enable_seqscan = off");
            int planned = 0;
            for (String sql : new LinkedHashSet<>(statements)) {
                JsonNode plan = plan(statement, sql);
                if (plan == null) continue;
                planned++;
                collect(plan, sql, scans);
            }
            if (planned == 0 && !statements.isEmpty()) {
                throw new IllegalStateException("No statement could be planned, so none was checked: " + statements);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Could not open a connection to plan statements", failure);
        }
        return scans;
    }

    /** Before PostgreSQL 18, a plan does not mark a scan that the planner was forced to use, so every check passes. */
    private static void requirePostgres18(Statement statement) throws SQLException {
        try (ResultSet version = statement.executeQuery("SHOW server_version_num")) {
            version.next();
            if (Integer.parseInt(version.getString(1)) < 180000) {
                throw new IllegalStateException("Plan checks need PostgreSQL 18 or later");
            }
        }
    }

    private static @Nullable JsonNode plan(Statement statement, String sql) {
        try (ResultSet plan =
                statement.executeQuery("EXPLAIN (GENERIC_PLAN, COSTS OFF, FORMAT JSON) " + numberedParameters(sql))) {
            plan.next();
            return JSON.readTree(plan.getString(1)).get(0).get("Plan");
        } catch (SQLException failure) {
            if (UNTYPED_PARAMETER.contains(failure.getSQLState())) return null;
            throw new IllegalStateException("Could not plan: " + sql, failure);
        }
    }

    private static void collect(JsonNode node, String sql, List<FullScan> scans) {
        if ("Seq Scan".equals(node.path("Node Type").asString())
                && node.path("Disabled").asBoolean()
                && node.has("Filter")) {
            scans.add(new FullScan(
                    node.path("Relation Name").asString(), node.path("Filter").asString(), sql));
        }
        for (JsonNode child : node.path("Plans")) collect(child, sql, scans);
    }

    /**
     * Rewrites JDBC {@code ?} placeholders to PostgreSQL's {@code $1, $2, ...}. A {@code ?} inside a literal, a quoted
     * identifier or a comment stays, and pgjdbc's {@code ??} escape becomes the {@code ?} operator.
     */
    static String numberedParameters(String sql) {
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int parameter = 0;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            int end = switch (c) {
                case '\'', '"' -> closing(sql, i, String.valueOf(c));
                case '-' -> sql.startsWith("--", i) ? lineEnd(sql, i) : i + 1;
                case '/' -> sql.startsWith("/*", i) ? closing(sql, i + 1, "*/") : i + 1;
                case '$' -> dollarQuoteEnd(sql, i);
                default -> i + 1;
            };
            if (c == '?' && sql.startsWith("??", i)) {
                out.append('?');
                end = i + 2;
            } else if (c == '?') {
                out.append('$').append(++parameter);
            } else {
                out.append(sql, i, end);
            }
            i = end;
        }
        return out.toString();
    }

    private static int closing(String sql, int start, String quote) {
        int end = sql.indexOf(quote, start + 1);
        return end < 0 ? sql.length() : end + quote.length();
    }

    private static int lineEnd(String sql, int start) {
        int end = sql.indexOf('\n', start);
        return end < 0 ? sql.length() : end + 1;
    }

    private static int dollarQuoteEnd(String sql, int start) {
        int tagEnd = start + 1;
        while (tagEnd < sql.length() && (Character.isLetterOrDigit(sql.charAt(tagEnd)) || sql.charAt(tagEnd) == '_'))
            tagEnd++;
        if (tagEnd >= sql.length() || sql.charAt(tagEnd) != '$' || Character.isDigit(sql.charAt(start + 1))) {
            return start + 1;
        }
        return closing(sql, tagEnd, sql.substring(start, tagEnd + 1));
    }
}
