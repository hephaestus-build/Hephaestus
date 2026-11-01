package de.tum.cit.aet.hephaestus.core.database;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The objects that the changelog decides in a database, one sorted line each ({@code db/schema-fingerprint.sql}). Two
 * databases with the same fingerprint have the same schema, whatever history built them, so a baseline can replace a
 * chain of migrations only where the fingerprints match.
 */
final class SchemaFingerprint {

    private static final String QUERY = load();

    private SchemaFingerprint() {}

    /** The fingerprint of the database behind {@code connection}. */
    static List<String> of(Connection connection) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(QUERY)) {
            while (rows.next()) {
                lines.add(rows.getString(1));
            }
        }
        return List.copyOf(lines);
    }

    /** The lines that only one side has, for a report; empty when the two fingerprints are equal. */
    static String difference(List<String> expected, List<String> actual) {
        Set<String> wanted = new HashSet<>(expected);
        Set<String> found = new HashSet<>(actual);
        StringBuilder report = new StringBuilder();
        expected.stream()
                .filter(line -> !found.contains(line))
                .forEach(line -> report.append("- ").append(line).append('\n'));
        actual.stream()
                .filter(line -> !wanted.contains(line))
                .forEach(line -> report.append("+ ").append(line).append('\n'));
        return report.toString();
    }

    private static String load() {
        try (InputStream in = Objects.requireNonNull(
                SchemaFingerprint.class.getResourceAsStream("/db/schema-fingerprint.sql"),
                "db/schema-fingerprint.sql is on the classpath")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
