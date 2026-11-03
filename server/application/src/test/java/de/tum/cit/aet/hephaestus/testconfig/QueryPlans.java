package de.tum.cit.aet.hephaestus.testconfig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    QueryPlans(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** One sequential scan that no index could replace, with the statement that needs it. */
    public record FullScan(String table, String filter, String sql) {}

    /**
     * Plans each distinct statement and returns the filtered sequential scans that no index could replace. A statement
     * that PostgreSQL cannot plan without bound values, such as {@code ? IS NULL}, is skipped.
     */
    public List<FullScan> fullScans(Collection<String> statements) {
        List<FullScan> scans = new ArrayList<>();
        for (String sql : new LinkedHashSet<>(statements)) {
            JsonNode plan = plan(sql);
            if (plan != null) collect(plan, sql, scans);
        }
        return scans;
    }

    private @Nullable JsonNode plan(String sql) {
        try {
            return transaction.execute(status -> {
                jdbc.execute("SET LOCAL enable_seqscan = off");
                String json = jdbc.queryForObject(
                        "EXPLAIN (GENERIC_PLAN, COSTS OFF, FORMAT JSON) " + numberedParameters(sql), String.class);
                status.setRollbackOnly();
                return JSON.readTree(Objects.requireNonNull(json)).get(0).get("Plan");
            });
        } catch (DataAccessException unplannable) {
            return null;
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
        while (tagEnd < sql.length()
                && (Character.isLetterOrDigit(sql.charAt(tagEnd)) || sql.charAt(tagEnd) == '_')) tagEnd++;
        if (tagEnd >= sql.length() || sql.charAt(tagEnd) != '$' || Character.isDigit(sql.charAt(start + 1))) {
            return start + 1;
        }
        return closing(sql, tagEnd, sql.substring(start, tagEnd + 1));
    }
}
