package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SqlStatementCounterTest extends BaseUnitTest {
    private final SqlStatementCounter counter = new SqlStatementCounter();
    private final Connection connection = mock(Connection.class);

    private DataSource counted() throws SQLException {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT 1")).thenReturn(mock(PreparedStatement.class));
        return (DataSource) counter.postProcessAfterInitialization(source, "dataSource");
    }

    @Test
    void shouldCountOnlyTheMeasuredBlockWhenTheConnectionIsReused() throws SQLException {
        try (Connection jdbc = counted().getConnection()) {
            prepare(jdbc);
            assertThat(counter.measure(() -> {
                        prepare(jdbc);
                        prepare(jdbc);
                        return "read";
                    }))
                    .isEqualTo(new SqlStatementCounter.Measurement<>(
                            "read",
                            List.of(
                                    new SqlStatementCounter.Prepared("SELECT 1", null, false),
                                    new SqlStatementCounter.Prepared("SELECT 1", null, false))));
            prepare(jdbc);
            assertThat(counter.measure(() -> "empty").statements()).isZero();
        }
    }

    @Test
    void shouldExcludeOtherThreadsWhenTheyPrepareStatementsDuringARead() throws Exception {
        try (var workers = Executors.newSingleThreadExecutor();
                Connection jdbc = counted().getConnection()) {
            var measured = counter.measure(() -> {
                CompletableFuture.runAsync(
                                () -> {
                                    prepare(jdbc);
                                    prepare(jdbc);
                                },
                                workers)
                        .orTimeout(30, TimeUnit.SECONDS)
                        .join();
                prepare(jdbc);
                return "read";
            });
            assertThat(measured.statements()).isEqualTo(1);
        }
    }

    @Test
    void shouldReadTheOwnerOfEachStatementOfTheNamedThreadOnlyWhenAWorkerIsMeasured() throws Exception {
        Statement probe = mock(Statement.class);
        ResultSet owner = mock(ResultSet.class);
        when(connection.createStatement()).thenReturn(probe);
        when(probe.executeQuery(anyString())).thenReturn(owner);
        when(owner.getLong(1)).thenReturn(7L);
        when(owner.getBoolean(2)).thenReturn(true);
        ThreadFactory named = Thread.ofPlatform().name("measured-worker").factory();
        try (var worker = Executors.newSingleThreadExecutor(named);
                var other = Executors.newSingleThreadExecutor();
                Connection jdbc = counted().getConnection()) {
            var measured = counter.measureOwnersOn("measured-worker", () -> {
                CompletableFuture.allOf(
                                CompletableFuture.runAsync(() -> prepare(jdbc), worker),
                                CompletableFuture.runAsync(() -> prepare(jdbc), other))
                        .orTimeout(30, TimeUnit.SECONDS)
                        .join();
                prepare(jdbc);
                return "read";
            });
            assertThat(measured.prepared()).containsExactly(new SqlStatementCounter.Prepared("SELECT 1", 7L, true));
            CompletableFuture.runAsync(() -> prepare(jdbc), worker)
                    .orTimeout(30, TimeUnit.SECONDS)
                    .join();
            assertThat(counter.measureOwnersOn("measured-worker", () -> "after").statements())
                    .isZero();
        }
    }

    @Test
    void shouldClearTheScopeWhenTheReadFails() {
        var failure = new IllegalArgumentException("read failed");
        assertThatThrownBy(() -> counter.measure(() -> {
                    throw failure;
                }))
                .isSameAs(failure);
        assertThat(counter.measure(() -> "next").statements()).isZero();
    }

    @Test
    void shouldKeepTheOuterScopeWhenANestedMeasurementIsRejected() throws SQLException {
        try (Connection jdbc = counted().getConnection()) {
            var measured = counter.measure(() -> {
                prepare(jdbc);
                assertThatThrownBy(() -> counter.measure(() -> "nested")).isInstanceOf(IllegalStateException.class);
                prepare(jdbc);
                return "outer";
            });
            assertThat(measured.statements()).isEqualTo(2);
        }
    }

    @Test
    void shouldPreserveTheJdbcFailureWhenPreparationFails() throws SQLException {
        var failure = new SQLException("preparation failed");
        try (Connection jdbc = counted().getConnection()) {
            when(connection.prepareStatement("SELECT 1")).thenThrow(failure);
            assertThatThrownBy(() -> jdbc.prepareStatement("SELECT 1")).isSameAs(failure);
        }
        assertThat(counter.measure(() -> "next").statements()).isZero();
    }

    private static void prepare(Connection jdbc) {
        try (var statement = jdbc.prepareStatement("SELECT 1")) {
            assertThat(statement).isNotNull();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
