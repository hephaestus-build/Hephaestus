package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import de.tum.cit.aet.hephaestus.practices.model.ResultSourceKindsChange;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.SqlStatementCounter;
import java.sql.Array;
import java.sql.Connection;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A result records the source kinds that it rests on when it is written, once. A history read then carries those kinds
 * and loads no evidence, and the upgrade records them for earlier rows with the same extraction.
 */
class ResultSourceKindsIntegrationTest extends BaseIntegrationTest {
    private static final String KIND = "scm.pull-request.diff";

    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    ResultClaimWriter claims;

    @Autowired
    ResultRepository queries;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    // The upgrade runs as the schema owner, and only the owner can lift the guard to stage a row an earlier release
    // wrote. So this test commits its rows: an open test transaction would hold the lock that the owner's DDL waits
    // for.
    private final JdbcTemplate owner = PostgreSQLTestContainer.ownerJdbc();

    @Autowired
    SqlStatementCounter statements;

    @Test
    void shouldRecordTheSourceKindsOnceAndReadAHistoryWithoutItsEvidence() throws Exception {
        var scenario = data.scenario();
        long workspaceId = scenario.workspace().getId();
        var pending =
                admissions.pending(scenario, Instant.now(), scenario.account().getId());
        var id = Objects.requireNonNull(new TransactionTemplate(transactions)
                .execute(status -> claims.write(
                                workspaceId,
                                pending.review(),
                                admissions.candidate(pending, ResultOutcome.NOT_MET),
                                Instant.now())
                        .getId()));
        assertThat(kinds(id)).containsExactly(KIND);
        var read = statements.measure(() -> history(workspaceId, pending.developerId()));
        // The light row says what the full record says.
        assertThat(read.value()).isEqualTo(queries.findById(id).orElseThrow().facts());
        assertThat(read.value().sourceKinds()).containsExactly(KIND);
        // One statement, and a row with recorded kinds reads its evidence only as a presence flag.
        assertThat(read.sql())
                .singleElement()
                .satisfies(sql -> assertThat(sql)
                        .doesNotContainPattern("(?<!THEN )\\br\\.(evidence|example)\\b(?! IS NOT NULL)"));

        // A row that an older server wrote during the upgrade has no kinds: a history extracts them from its evidence.
        withoutGuard("UPDATE result SET source_kinds = NULL WHERE id = ?", id);
        assertThat(history(workspaceId, pending.developerId()).sourceKinds()).containsExactly(KIND);
        // The application cannot record kinds after the insert; the upgrade, as the schema owner, does.
        assertThatThrownBy(() -> jdbc.update("UPDATE result SET source_kinds = '{" + KIND + "}' WHERE id = ?", id))
                .hasMessageContaining("result source kinds are recorded once");
        backfill();
        assertThat(kinds(id)).containsExactly(KIND);
        assertThat(history(workspaceId, pending.developerId()).sourceKinds()).containsExactly(KIND);

        // Recorded kinds never change, not even for the schema owner.
        assertThatThrownBy(() -> owner.update("UPDATE result SET source_kinds = '{docs.document}' WHERE id = ?", id))
                .hasMessageContaining("result source kinds are recorded once");

        // A citation that names no kind keeps the column NULL, so a history withholds the result.
        withoutGuard(
                "UPDATE result SET source_kinds = NULL, evidence = evidence #- '{citations,0,sourceKind}'"
                        + " WHERE id = ?",
                id);
        backfill();
        assertThat(kinds(id)).isNull();
        assertThat(history(workspaceId, pending.developerId()).sourceKinds()).isNull();
    }

    private ResultFacts history(long workspaceId, @Nullable Long developerId) {
        Objects.requireNonNull(developerId);
        var rows = queries.forDevelopersSince(workspaceId, List.of(developerId), Instant.EPOCH, false);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    @Test
    void shouldRecordTheKindsOfEveryPageWhenAWithheldRowLeadsTheFirstPage() throws Exception {
        var written = List.of(write(), write(), write());
        // PostgreSQL's uuid order decides the pages, so the row it puts first is the withheld one.
        var ordered = jdbc.queryForList(
                "SELECT id FROM result WHERE id IN (?, ?, ?) ORDER BY id",
                UUID.class,
                written.get(0),
                written.get(1),
                written.get(2));
        UUID withheld = Objects.requireNonNull(ordered.get(0));
        withoutGuard(
                "UPDATE result SET source_kinds = NULL, evidence = evidence #- '{citations,0,sourceKind}'"
                        + " WHERE id = ?",
                withheld);
        for (var id : ordered.subList(1, 3))
            withoutGuard("UPDATE result SET source_kinds = NULL WHERE id = ?", Objects.requireNonNull(id));

        // Two rows a page: the withheld row and the next share the first page, the last row is on the second.
        backfill(2);

        assertThat(kinds(withheld)).isNull();
        assertThat(kinds(Objects.requireNonNull(ordered.get(1)))).containsExactly(KIND);
        assertThat(kinds(Objects.requireNonNull(ordered.get(2)))).containsExactly(KIND);
    }

    private UUID write() {
        var scenario = data.scenario();
        var pending =
                admissions.pending(scenario, Instant.now(), scenario.account().getId());
        return Objects.requireNonNull(new TransactionTemplate(transactions)
                .execute(status -> claims.write(
                                scenario.workspace().getId(),
                                pending.review(),
                                admissions.candidate(pending, ResultOutcome.NOT_MET),
                                Instant.now())
                        .getId()));
    }

    private @Nullable List<String> kinds(UUID id) {
        return jdbc.queryForObject(
                "SELECT source_kinds FROM result WHERE id = ?",
                (rows, n) -> {
                    Array array = rows.getArray(1);
                    return array == null ? null : Arrays.asList((String[]) array.getArray());
                },
                id);
    }

    // One owner transaction: no other test sees the guard lifted.
    private void withoutGuard(String update, UUID id) {
        new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(owner.getDataSource())))
                .executeWithoutResult(status -> {
                    owner.execute("ALTER TABLE result DISABLE TRIGGER result_facts");
                    owner.update(update, id);
                    // The deferred result checks must run before the guard comes back: DDL refuses pending events.
                    owner.execute("SET CONSTRAINTS ALL IMMEDIATE");
                    owner.execute("ALTER TABLE result ENABLE TRIGGER result_facts");
                });
    }

    // As the migration runs it: as the owner, committed by Liquibase after the change.
    private void backfill() throws Exception {
        backfill(500);
    }

    private void backfill(int pageSize) throws Exception {
        try (Connection connection =
                Objects.requireNonNull(owner.getDataSource()).getConnection()) {
            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            new ResultSourceKindsChange(pageSize).execute(database);
            database.commit();
        }
    }
}
