package de.tum.cit.aet.hephaestus.integration.schema;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.BasicValuedModelPart;
import org.hibernate.metamodel.mapping.EmbeddableValuedModelPart;
import org.hibernate.metamodel.mapping.ModelPart;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Each JSON column accepts one JSON type: an object, or an array where the server stores a list. A value of another
 * type, such as a JSON string or {@code null} inside the column, fails only when the server reads it.
 */
@Tag("integration")
class JsonColumnShapeIntegrationTest extends BaseIntegrationTest {

    private static final Pattern LITERAL = Pattern.compile("'(\\w+)'");

    @Autowired
    private EntityManagerFactory entities;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldAcceptOneJsonTypeWhenAColumnStoresJson() {
        Map<String, String> mapped = mappedTypes();
        var mismatches = new TreeSet<String>();
        List<Column> columns = jdbc.query(
                """
                SELECT c.table_name, c.column_name
                FROM information_schema.columns c
                JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = current_schema() AND t.table_type = 'BASE TABLE'
                  AND c.data_type IN ('json', 'jsonb')
                ORDER BY 1, 2
                """,
                (rows, number) -> new Column(
                        Objects.requireNonNull(rows.getString(1)), Objects.requireNonNull(rows.getString(2))));
        for (var json : columns) {
            String table = json.table();
            String column = json.column();
            Set<String> accepted = accepted(table, column);
            @Nullable String expected = mapped.get(table + "." + column);
            boolean matches = expected == null ? accepted.size() == 1 : accepted.equals(Set.of(expected));
            if (!matches) {
                mismatches.add(table + "." + column + ": accepts " + accepted + ", the server stores "
                        + Objects.requireNonNullElse(expected, "one type"));
            }
        }

        assertThat(columns).as("JSON columns found").isNotEmpty();
        assertThat(mismatches).as("""
                        A JSON column needs a CHECK that names the one JSON type the server stores, such as \
                        jsonb_typeof(col) = 'object'. See docs/contributor/database-migration.mdx § JSON columns.""").isEmpty();
    }

    private record Column(String table, String column) {}

    /**
     * The JSON type that the server stores in each mapped column: an array for a collection or an array type, an
     * object for any other type except {@link JsonNode}, which can hold any type.
     */
    private Map<String, String> mappedTypes() {
        var types = new HashMap<String, String>();
        var metamodel = entities.unwrap(SessionFactoryImplementor.class).getMappingMetamodel();
        metamodel.forEachEntityDescriptor(entity -> {
            Consumer<BasicValuedModelPart> record = basic -> {
                if (!basic.getJdbcMapping().getJdbcType().isJson()) return;
                Class<?> type = basic.getJavaType().getJavaTypeClass();
                if (JsonNode.class.isAssignableFrom(type)) return;
                String table = basic.getContainingTableExpression().replace("\"", "");
                String column = basic.getSelectionExpression().replace("\"", "");
                types.put(
                        table + "." + column,
                        Collection.class.isAssignableFrom(type) || type.isArray() ? "array" : "object");
            };
            visit(entity.getIdentifierMapping(), record);
            entity.forEachAttributeMapping(attribute -> visit(attribute, record));
        });
        return types;
    }

    private static void visit(ModelPart part, Consumer<BasicValuedModelPart> record) {
        if (part instanceof BasicValuedModelPart basic) {
            record.accept(basic);
        } else if (part instanceof EmbeddableValuedModelPart embeddable) {
            embeddable.getEmbeddableTypeDescriptor().forEachAttributeMapping(attribute -> visit(attribute, record));
        }
    }

    /**
     * The JSON types that the CHECK constraints on this column accept: the types that each CHECK compares
     * {@code jsonb_typeof(col)} with, kept only when every such CHECK accepts them.
     */
    private Set<String> accepted(String table, String column) {
        List<@Nullable String> definitions = jdbc.queryForList("""
                SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace AND n.nspname = current_schema()
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (c.conkey)
                WHERE c.contype = 'c' AND t.relname = ? AND a.attname = ?
                """, String.class, table, column);
        // PostgreSQL prints `jsonb_typeof(col) = 'object'` as `(jsonb_typeof(col) = 'object'::text)`, and a list of
        // types as `jsonb_typeof(col) = ANY (ARRAY['object'::text, 'array'::text])`.
        var typeOf = Pattern.compile("jsonb?_typeof\\(\\(?" + Pattern.quote(column)
                + "\\)?(?:::jsonb)?\\) = (?:ANY \\(+ARRAY\\[([^\\]]*)\\]|('\\w+'))");
        Set<String> accepted = null;
        for (String definition : definitions.stream().filter(Objects::nonNull).toList()) {
            Set<String> listed = typeOf.matcher(definition)
                    .results()
                    .map(match -> Objects.requireNonNullElse(match.group(1), match.group(2)))
                    .flatMap(values -> LITERAL.matcher(values).results().map(literal -> literal.group(1)))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (listed.isEmpty()) continue;
            if (accepted == null) {
                accepted = listed;
            } else {
                accepted.retainAll(listed);
            }
        }
        return accepted == null ? new TreeSet<>() : accepted;
    }
}
