package de.tum.cit.aet.hephaestus.testconfig;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class SqlReadMeasurementIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private SqlReadMeasurement reads;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transaction;

    @Test
    void shouldKeepReadCostsSeparateWhenAnotherThreadReadsDuringTheMeasurement() {
        var user = persistUser("sql-background-developer");
        try (var worker = Executors.newSingleThreadExecutor()) {
            var measured = reads.measure(() -> {
                var background = CompletableFuture.supplyAsync(
                        () -> reads.measure(() -> {
                            assertThat(userRepository
                                            .findById(user.getId())
                                            .orElseThrow()
                                            .getLogin())
                                    .isEqualTo(user.getLogin());
                            return Objects.requireNonNull(jdbc.queryForObject("SELECT ?", Integer.class, 42));
                        }),
                        worker);
                var completed = background.orTimeout(30, TimeUnit.SECONDS).join();
                assertThat(completed.cost().entities()).isPositive();
                assertThat(completed.value()).isEqualTo(42);
                return Objects.requireNonNull(jdbc.queryForObject("SELECT ?", Integer.class, 41));
            });
            assertThat(measured.cost()).isEqualTo(new SqlReadMeasurement.Cost(1, 0));
            assertThat(measured.value()).isEqualTo(41);
        }
    }

    @Test
    void shouldReadFromAFreshContextWhenTheCallerAlreadyLoadedTheEntity() {
        var user = persistUser("sql-read-developer");
        var first = reads.measure(
                () -> userRepository.findById(user.getId()).orElseThrow().getLogin());
        assertThat(first.cost().statements()).isPositive();
        assertThat(first.cost().entities()).isPositive();
        transaction.executeWithoutResult(status -> {
            assertThat(userRepository.findById(user.getId()).orElseThrow().getLogin())
                    .isEqualTo(user.getLogin());
            var second = reads.measure(
                    () -> userRepository.findById(user.getId()).orElseThrow().getLogin());
            var third = reads.measure(
                    () -> userRepository.findById(user.getId()).orElseThrow().getLogin());
            assertThat(second).isEqualTo(first);
            assertThat(third).isEqualTo(first);
        });
    }
}
