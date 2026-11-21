package de.tum.cit.aet.hephaestus.integration.schema;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.util.Arrays;
import java.util.List;
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

/**
 * A column that stores a Java enum accepts exactly the values that the server writes for it: the constants' names, or
 * what its converter writes. A CHECK that misses one refuses a value the server writes. A CHECK that keeps a removed
 * value, or no CHECK at all, lets in a value that the server cannot read.
 */
@Tag("integration")
class EnumColumnConstraintIntegrationTest extends BaseIntegrationTest {

    private static final Pattern LITERAL = Pattern.compile("'([^']*)'");
    /** What is left of a CHECK that is only a list for one column, once the list is taken out. */
    private static final String WHOLE_CHECK_REST = "CHECK[\\s()]*(?:::text(?:\\[\\])?)?[\\s()]*";

    @Autowired
    private EntityManagerFactory entities;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldAcceptExactlyTheEnumNamesWhenAColumnStoresAnEnumByName() {
        var mismatches = new TreeSet<String>();
        var checked = new TreeSet<String>();
        var metamodel = entities.unwrap(SessionFactoryImplementor.class).getMappingMetamodel();
        metamodel.forEachEntityDescriptor(entity -> {
            Consumer<BasicValuedModelPart> check = basic -> {
                Class<?> type = basic.getJavaType().getJavaTypeClass();
                if (!type.isEnum() || !basic.getJdbcMapping().getJdbcType().isString()) return;
                // A reserved name such as "user" arrives quoted.
                String table = basic.getContainingTableExpression().replace("\"", "");
                String column = basic.getSelectionExpression().replace("\"", "");
                var mapping = basic.getJdbcMapping();
                // A converted enum stores what its converter writes, not the constant's name.
                Set<String> expected = Arrays.stream(type.getEnumConstants())
                        .map(constant -> mapping.getValueConverter() == null
                                ? ((Enum<?>) constant).name()
                                : String.valueOf(mapping.convertToRelationalValue(constant)))
                        .collect(Collectors.toCollection(TreeSet::new));
                checked.add(table + "." + column);
                Set<String> accepted = accepted(table, column);
                if (!accepted.equals(expected)) {
                    mismatches.add(table + "." + column + " (" + type.getSimpleName() + "): accepts " + accepted
                            + ", the enum has " + expected);
                }
            };
            visit(entity.getIdentifierMapping(), check);
            entity.forEachAttributeMapping(attribute -> visit(attribute, check));
        });

        // One column on each path: a plain attribute, an embeddable's attribute and an embedded id's attribute.
        assertThat(checked)
                .contains(
                        "activity_event.event_type", "workspace.practice_delivery_status", "catalog_entry.entry_type");
        assertThat(mismatches).as("""
                        An enum column's CHECK must list exactly the enum's values. Write a new changelog that drops \
                        and adds the column's check again, such as ck_<table>_<column>. Never edit a changelog that \
                        reached main. See docs/contributor/database-migration.mdx § Enum columns.""").isEmpty();
    }

    /** The basic parts of a model part: the part itself, or the attributes of an embeddable, also when nested. */
    private static void visit(ModelPart part, Consumer<BasicValuedModelPart> check) {
        if (part instanceof BasicValuedModelPart basic) {
            check.accept(basic);
        } else if (part instanceof EmbeddableValuedModelPart embeddable) {
            embeddable.getEmbeddableTypeDescriptor().forEachAttributeMapping(attribute -> visit(attribute, check));
        }
    }

    /**
     * The values that the CHECK constraints on this column accept. PostgreSQL enforces each CHECK, so a value must be
     * in each CHECK that is only a list for this column. A column with no such CHECK takes the lists that wider
     * CHECKs compare it with, joined. Empty when no CHECK lists values for the column.
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
        // PostgreSQL prints `col IN ('A', 'B')` on a varchar column as `(col)::text = ANY (ARRAY[...])`, on a text
        // column as `col = ANY (ARRAY[...])`, and a one-value list as `(col)::text = 'A'::text`. A single value
        // counts only as a whole CHECK: inside a wider one it is a condition, such as `col = 'A' OR other IS NULL`.
        String subject = "(?:\\(|(?<![\\w\"]))" + Pattern.quote(column) + "\\)?(?:::text)? = ";
        var list = Pattern.compile(subject + "(?:ANY \\(+ARRAY\\[([^\\]]*)\\]|('[^']*'))");
        Set<String> only = null;
        Set<String> joined = new TreeSet<>();
        for (String definition : definitions.stream().filter(Objects::nonNull).toList()) {
            var matches = list.matcher(definition).results().toList();
            boolean whole = matches.size() == 1
                    && (definition.substring(0, matches.getFirst().start())
                                    + definition.substring(matches.getFirst().end()))
                            .matches(WHOLE_CHECK_REST);
            if (whole) {
                Set<String> listed = values(Objects.requireNonNullElse(
                        matches.getFirst().group(1), matches.getFirst().group(2)));
                if (only == null) {
                    only = listed;
                } else {
                    only.retainAll(listed);
                }
            } else {
                matches.stream()
                        .map(match -> match.group(1))
                        .filter(Objects::nonNull)
                        .forEach(values -> joined.addAll(values(values)));
            }
        }
        return only == null ? joined : only;
    }

    private static Set<String> values(String literals) {
        return LITERAL.matcher(literals)
                .results()
                .map(literal -> literal.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
