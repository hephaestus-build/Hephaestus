package de.tum.cit.aet.hephaestus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A value is kept only in a read-only REPEATABLE READ transaction, where every statement reads one snapshot. The
 * transaction state is set as Spring's transaction manager sets it for a new transaction.
 */
class SnapshotMemoTest extends BaseUnitTest {
    private final SnapshotMemo<String, Integer> memo = new SnapshotMemo<>(Integer.class);
    private final AtomicInteger computed = new AtomicInteger();

    @AfterEach
    void endTransaction() {
        TransactionSynchronizationManager.clear();
    }

    private Integer compute() {
        return computed.incrementAndGet();
    }

    private static void begin(boolean readOnly, @Nullable Integer isolation) {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
    }

    @Test
    void shouldComputeEveryTimeWithoutATransaction() {
        memo.get("work", this::compute);
        memo.get("work", this::compute);

        assertThat(computed).hasValue(2);
    }

    @Test
    void shouldComputeEveryTimeInAReadCommittedTransaction() {
        begin(true, null);

        memo.get("work", this::compute);
        memo.get("work", this::compute);

        assertThat(computed).hasValue(2);
    }

    @Test
    void shouldComputeEveryTimeInARepeatableReadTransactionThatMayWrite() {
        begin(false, Connection.TRANSACTION_REPEATABLE_READ);

        memo.get("work", this::compute);
        memo.get("work", this::compute);

        assertThat(computed).hasValue(2);
    }

    @Test
    void shouldKeepAValuePerKeyInAReadOnlySnapshot() {
        begin(true, Connection.TRANSACTION_REPEATABLE_READ);

        assertThat(memo.get("work", this::compute)).isEqualTo(1);
        assertThat(memo.get("work", this::compute)).isEqualTo(1);
        assertThat(memo.get("other work", this::compute)).isEqualTo(2);
        assertThat(computed).hasValue(2);
    }

    @Test
    void shouldNotKeepAComputationThatThrows() {
        begin(true, Connection.TRANSACTION_REPEATABLE_READ);

        assertThatThrownBy(() -> memo.get("work", () -> {
                    throw new IllegalStateException("refused");
                }))
                .hasMessage("refused");

        assertThat(memo.get("work", this::compute)).isEqualTo(1);
    }

    /** As Spring suspends the outer transaction's synchronizations when one starts with REQUIRES_NEW. */
    @Test
    void shouldKeepATransactionsValuesFromATransactionStartedInsideIt() {
        begin(true, Connection.TRANSACTION_REPEATABLE_READ);
        memo.get("work", this::compute);
        List<TransactionSynchronization> outer = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        begin(true, Connection.TRANSACTION_REPEATABLE_READ);

        assertThat(memo.get("work", this::compute))
                .as("the inner snapshot reads again")
                .isEqualTo(2);

        TransactionSynchronizationManager.clearSynchronization();
        begin(true, Connection.TRANSACTION_REPEATABLE_READ);
        outer.forEach(TransactionSynchronizationManager::registerSynchronization);
        assertThat(memo.get("work", this::compute))
                .as("the outer snapshot's value")
                .isEqualTo(1);
    }
}
