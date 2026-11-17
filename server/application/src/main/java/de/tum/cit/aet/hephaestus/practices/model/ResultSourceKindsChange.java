package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;
import liquibase.change.custom.CustomTaskChange;
import liquibase.database.Database;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.CustomChangeException;
import liquibase.exception.ValidationErrors;
import liquibase.resource.ResourceAccessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records the source kinds of each result that an earlier release wrote, with {@link Result#sourceKinds(JsonNode,
 * JsonNode)}, the extraction that a new result records at its insert. A result whose sources are not all named keeps
 * NULL, which withholds it, as the extraction would on every read. Liquibase checksums only this class's name, so this extraction
 * must stay the one that new results use.
 */
@WorkspaceAgnostic("Upgrade-time record of a value derived from each result's own row, across all workspaces")
public class ResultSourceKindsChange implements CustomTaskChange {

    private static final Logger log = LoggerFactory.getLogger(ResultSourceKindsChange.class);
    private static final String READ = """
        SELECT id, evidence::text, example::text FROM result
        WHERE source_kinds IS NULL AND id > ? ORDER BY id LIMIT ?
        """;
    private static final String RECORD = "UPDATE result SET source_kinds = ? WHERE id = ?";

    private final JsonMapper json = JsonMapper.builder().build();
    private final int pageSize;
    private int recorded;
    private int withheld;

    /** Liquibase creates the change with this constructor. */
    public ResultSourceKindsChange() {
        this(500);
    }

    /** A smaller page lets a test cross page boundaries with a few rows. */
    public ResultSourceKindsChange(int pageSize) {
        this.pageSize = pageSize;
    }

    @Override
    public void execute(Database database) throws CustomChangeException {
        if (!(database.getConnection() instanceof JdbcConnection connection)) {
            throw new CustomChangeException("Recording source kinds requires a JDBC database");
        }
        try {
            record(connection.getUnderlyingConnection());
        } catch (SQLException | RuntimeException exception) {
            throw new CustomChangeException("Source kinds could not be recorded", exception);
        }
        log.info("Recorded the source kinds of earlier results: recorded={}, withheld={}", recorded, withheld);
    }

    private void record(Connection connection) throws SQLException {
        // The keyset starts at the nil UUID, the smallest in PostgreSQL's uuid order.
        var after = new UUID(0, 0);
        while (true) {
            var page = new ArrayList<Row>();
            try (var read = connection.prepareStatement(READ)) {
                read.setObject(1, after);
                read.setInt(2, pageSize);
                try (var rows = read.executeQuery()) {
                    while (rows.next())
                        page.add(new Row(
                                rows.getObject(1, UUID.class), parse(rows.getString(2)), parse(rows.getString(3))));
                }
            }
            if (page.isEmpty()) return;
            try (var update = connection.prepareStatement(RECORD)) {
                for (var row : page) {
                    var kinds = Result.sourceKinds(row.evidence(), row.example());
                    if (kinds == null) {
                        withheld++;
                        continue;
                    }
                    Array array = connection.createArrayOf("text", kinds.toArray());
                    update.setArray(1, array);
                    update.setObject(2, row.id());
                    update.addBatch();
                    recorded++;
                }
                update.executeBatch();
            }
            after = page.getLast().id();
        }
    }

    private @Nullable JsonNode parse(@Nullable String text) {
        return text == null ? null : json.readTree(text);
    }

    private record Row(
            UUID id, @Nullable JsonNode evidence, @Nullable JsonNode example) {}

    @Override
    public String getConfirmationMessage() {
        return "Recorded the source kinds of " + recorded + " earlier results; " + withheld + " stay withheld";
    }

    @Override
    public void setUp() {}

    @Override
    public void setFileOpener(ResourceAccessor resourceAccessor) {}

    @Override
    public ValidationErrors validate(Database database) {
        return new ValidationErrors();
    }
}
