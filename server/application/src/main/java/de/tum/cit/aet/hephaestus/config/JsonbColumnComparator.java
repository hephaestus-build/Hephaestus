package de.tum.cit.aet.hephaestus.config;

import java.util.Objects;
import java.util.Set;
import liquibase.database.Database;
import liquibase.diff.ObjectDifferences;
import liquibase.diff.compare.CompareControl;
import liquibase.diff.compare.DatabaseObjectComparatorChain;
import liquibase.diff.compare.core.ColumnComparator;
import liquibase.statement.DatabaseFunction;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.Column;
import org.jspecify.annotations.Nullable;

public class JsonbColumnComparator extends ColumnComparator {

    @Override
    public int getPriority(Class<? extends DatabaseObject> objectType, Database database) {
        int priority = super.getPriority(objectType, database);
        return priority == PRIORITY_NONE ? priority : priority + 1;
    }

    @Override
    public ObjectDifferences findDifferences(
            DatabaseObject reference,
            DatabaseObject actual,
            Database database,
            CompareControl control,
            DatabaseObjectComparatorChain chain,
            Set<String> excluded) {
        if (!(reference instanceof Column referenceColumn)
                || !(actual instanceof Column actualColumn)
                || !isJsonb(referenceColumn)
                || !isJsonb(actualColumn)) {
            return super.findDifferences(reference, actual, database, control, chain, excluded);
        }
        excluded.add("defaultValue");
        ObjectDifferences differences = super.findDifferences(reference, actual, database, control, chain, excluded);
        differences.compare(
                "defaultValue",
                reference,
                actual,
                (first, second) -> Objects.equals(first, second) || (isEmptyObject(first) && isEmptyObject(second)));
        return differences;
    }

    private boolean isJsonb(Column column) {
        return column.getType() != null
                && "jsonb".equalsIgnoreCase(column.getType().getTypeName());
    }

    private boolean isEmptyObject(@Nullable Object value) {
        // PostgreSQL's column snapshot removes a JSONB literal's quotes and cast.
        @Nullable
        String expression = switch (value) {
            case DatabaseFunction function -> function.getValue();
            case String text -> text;
            case null, default -> null;
        };
        return "{}".equals(expression) || "'{}'".equals(expression) || "'{}'::jsonb".equals(expression);
    }
}
