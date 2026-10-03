package de.tum.cit.aet.hephaestus.testconfig;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserts a row into a physical table by asking the database what the table requires: every NOT NULL
 * column without a default that the caller did not supply is filled with a type-appropriate dummy,
 * sized to the column. A schema test seeding through this keeps working when a table it does not
 * care about gains a column.
 *
 * <p>Foreign keys are not resolved, so callers seed under {@code session_replication_role =
 * 'replica'} — the Testcontainers role is a superuser and may set it.
 */
public final class SchemaRowSeeder {

    private final JdbcTemplate jdbcTemplate;

    public SchemaRowSeeder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String table, Map<String, ?> overrides) {
        List<Map<String, @Nullable Object>> columns = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, is_nullable, column_default, character_maximum_length "
                        + "FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
                table);

        Map<String, Object> values = new LinkedHashMap<>(overrides);
        Map<String, String> dataTypes = new HashMap<>();
        for (Map<String, @Nullable Object> column : columns) {
            String name = String.valueOf(column.get("column_name"));
            String dataType = String.valueOf(column.get("data_type"));
            dataTypes.put(name, dataType);
            boolean supplied = values.containsKey(name);
            boolean optional = "YES".equals(column.get("is_nullable")) || column.get("column_default") != null;
            if (supplied || optional) {
                continue;
            }
            values.put(name, dummyFor(dataType, name, (Integer) column.get("character_maximum_length")));
        }

        String placeholders = values.keySet().stream()
                .map(name -> switch (dataTypes.getOrDefault(name, "")) {
                    case "json" -> "?::json";
                    case "jsonb" -> "?::jsonb";
                    default -> "?";
                })
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow(() -> new IllegalArgumentException("Nothing to insert into " + table));
        jdbcTemplate.update(
                "INSERT INTO " + table + " (" + String.join(", ", values.keySet()) + ") VALUES (" + placeholders + ")",
                values.values().toArray());
    }

    /** Type-appropriate dummy for a NOT NULL column the caller does not otherwise care about. */
    private static Object dummyFor(String dataType, String columnName, @Nullable Integer maximumLength) {
        return switch (dataType) {
            case "uuid" -> UUID.randomUUID();
            case "bigint", "integer", "smallint" -> 1L;
            case "real", "double precision", "numeric" -> 0.0;
            case "boolean" -> Boolean.FALSE;
            case "jsonb", "json" -> "{}";
            case "timestamp with time zone", "timestamp without time zone" -> Timestamp.from(Instant.now());
            // Unique, so a UNIQUE NOT NULL column does not collide across seeded rows, and within the
            // column's own width, so a narrow VARCHAR does not reject the filler.
            default -> {
                String readable = "seed-" + columnName + "-" + UUID.randomUUID();
                yield maximumLength == null || maximumLength >= readable.length()
                        ? readable
                        : UUID.randomUUID().toString().replace("-", "").substring(0, Math.min(32, maximumLength));
            }
        };
    }
}
