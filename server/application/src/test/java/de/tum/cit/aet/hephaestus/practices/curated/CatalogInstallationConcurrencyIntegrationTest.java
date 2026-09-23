package de.tum.cit.aet.hephaestus.practices.curated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.EntityTagPrecondition;
import de.tum.cit.aet.hephaestus.core.event.WorkspacesInitializedEvent;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

@Tag("integration")
class CatalogInstallationConcurrencyIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String PRACTICE = "describe-what-and-why";

    @Autowired
    private CuratedCatalogService catalogService;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private TransactionOperations transactionOperations;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void installationWaitsForACommittedCatalogWrite() throws Exception {
        User owner = persistUser("catalog-lock-owner");
        Workspace workspace = createWorkspace("catalog-lock", "Catalog lock", "catalog-lock", AccountType.ORG, owner);
        CatalogEntry<PracticeDefinition> current = catalogService.practice(PRACTICE);
        PracticeDefinition definition = current.effective();
        PracticeDefinition updated = new PracticeDefinition(
                definition.name(),
                definition.bindings(),
                "Committed catalog criteria",
                definition.precomputeScript(),
                definition.automatedReviewPolicy(),
                definition.whyItMatters(),
                definition.whatGoodLooksLike(),
                definition.groupSlug());
        CountDownLatch writeReady = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch installerStarted = new CountDownLatch(1);

        CompletableFuture<Void> writer =
                CompletableFuture.runAsync(() -> transactionOperations.executeWithoutResult(ignored -> {
                    catalogService.writePractice(
                            PRACTICE, EntityTagPrecondition.parse('"' + current.etag() + '"'), updated, null);
                    writeReady.countDown();
                    await(allowCommit);
                }));
        assertThat(writeReady.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> installer = CompletableFuture.runAsync(() -> {
            installerStarted.countDown();
            events.publishEvent(new WorkspacesInitializedEvent(1));
        });
        assertThat(installerStarted.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> installer.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
        } finally {
            allowCommit.countDown();
        }
        writer.get(10, TimeUnit.SECONDS);
        installer.get(10, TimeUnit.SECONDS);

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT criteria FROM practice WHERE workspace_id = ? AND slug = ?",
                        String.class,
                        workspace.getId(),
                        PRACTICE))
                .isEqualTo("Committed catalog criteria");
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM practice_catalog_installation WHERE workspace_id = ?",
                        Long.class,
                        workspace.getId()))
                .isOne();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to commit the catalog write");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to commit the catalog write", exception);
        }
    }
}
