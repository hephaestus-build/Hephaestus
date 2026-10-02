package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Frozen primary keys and an optional fingerprint of external-write inspection facts. No personal content. */
public record PersonDataSelection(
        List<RowKey> rows, @Nullable String externalDeliveryRevision) {
    public PersonDataSelection(List<RowKey> rows) {
        this(rows, null);
    }

    public PersonDataSelection {
        rows = List.copyOf(rows);
        if (new HashSet<>(rows).size() != rows.size()) {
            throw new IllegalArgumentException("A store selection cannot contain duplicate row keys");
        }
    }

    /** Only external-write operational facts and locators belong here, never personal content or credentials. */
    public PersonDataSelection withExternalDeliveryFacts(List<JsonNode> facts) {
        try {
            String ordered = String.join(
                    "\n", facts.stream().map(JsonNode::toString).sorted().toList());
            String revision = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(ordered.getBytes(StandardCharsets.UTF_8)));
            return new PersonDataSelection(rows, revision);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
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
