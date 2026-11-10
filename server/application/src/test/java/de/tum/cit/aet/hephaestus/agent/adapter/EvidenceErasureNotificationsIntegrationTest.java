package de.tum.cit.aet.hephaestus.agent.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A purge or an erasure wakes every server's reconcile when it commits, and never before. */
class EvidenceErasureNotificationsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DataSourceProperties dataSource;

    @Autowired
    private EvidenceFolderPersonDataCatalog copies;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void shouldWakeTheReconcileOnlyAfterThePurgeCommits() throws Exception {
        var woken = new CountDownLatch(1);
        var notifications = new EvidenceErasureNotifications(dataSource);
        try {
            notifications.subscribe(woken::countDown);
            // The listener connects on its own thread; a wake-up before it listens would be lost.
            Thread.sleep(500);
            var rolledBack = new TransactionTemplate(transactions);
            rolledBack.executeWithoutResult(status -> {
                copies.deleteWorkspaceData(-1L);
                status.setRollbackOnly();
            });
            assertThat(woken.await(1, TimeUnit.SECONDS))
                    .as("a rolled-back purge wakes nobody")
                    .isFalse();

            new TransactionTemplate(transactions).executeWithoutResult(status -> copies.deleteWorkspaceData(-1L));

            assertThat(woken.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            notifications.destroy();
        }
    }
}
