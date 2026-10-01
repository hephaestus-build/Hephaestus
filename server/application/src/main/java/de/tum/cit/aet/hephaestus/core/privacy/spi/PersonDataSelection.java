package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Stable primary keys selected by preview. No row content, SQL, credentials or predicates. */
public record PersonDataSelection(List<RowKey> rows) {
    public PersonDataSelection {
        rows = List.copyOf(rows);
        if (new HashSet<>(rows).size() != rows.size()) {
            throw new IllegalArgumentException("A store selection cannot contain duplicate row keys");
        }
    }

    /** Composite keys preserve all identity and tenancy columns required by the store. */
    public record RowKey(Map<String, String> columns) {
        public RowKey {
            columns = Map.copyOf(columns);
            if (columns.isEmpty() || columns.keySet().stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("A row key must name its primary-key columns");
            }
        }
    }
}
