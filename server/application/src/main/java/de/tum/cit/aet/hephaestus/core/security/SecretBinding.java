package de.tum.cit.aet.hephaestus.core.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The row and column that a system secret belongs to. {@link SystemSecrets} authenticates it with the value, so a value
 * moved to another row or column does not open.
 *
 * @param column the {@code table.column} that stores the value
 * @param rowKey the immutable natural key of the row, one part for each key column
 */
public record SecretBinding(String column, List<String> rowKey) {

    public SecretBinding {
        if (column.isBlank()) throw new IllegalArgumentException("A secret binding needs a column");
        if (rowKey.isEmpty()) throw new IllegalArgumentException("A secret binding needs a row key");
        rowKey = List.copyOf(rowKey);
    }

    public static SecretBinding of(String column, String... rowKey) {
        return new SecretBinding(column, List.of(rowKey));
    }

    List<byte[]> encodedParts() {
        List<byte[]> parts = new ArrayList<>(rowKey.size() + 1);
        parts.add(column.getBytes(StandardCharsets.UTF_8));
        for (String part : rowKey) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 0xFFFF) throw new IllegalArgumentException("A secret binding part is too long");
            parts.add(bytes);
        }
        return parts;
    }
}
