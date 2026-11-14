package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceCommands;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The workspace boundary keeps record edits local and commits only the owned command's rows. */
class WorkspacePortsIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WorkspaceQueries queries;

    @Autowired
    WorkspaceCommands commands;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void shouldPersistThePolicyOnlyThroughItsWorkspaceCommand() {
        var own = data.scenario().workspace().getId();
        var other = data.scenario().workspace().getId();
        long beforeVersion = version(own);
        long beforeRevision = revision(own);
        long otherVersion = version(other);
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            var snapshot = queries.findByIdForUpdate(own).orElseThrow();
            var changed = snapshot.reviewSettings().applyPatch(null, 27);
            assertThat(version(own)).isEqualTo(beforeVersion);
            assertThat(queries.findById(own).orElseThrow().reviewSettings()).isEqualTo(snapshot.reviewSettings());
            var saved = commands.saveReviewPolicy(own, changed, false);
            assertThat(saved.configVersion()).isEqualTo(beforeVersion + 1);
            assertThat(saved.rolloutRevision()).isEqualTo(beforeRevision);
        });
        assertThat(queries.findById(own).orElseThrow().reviewSettings().cooldownMinutes())
                .isEqualTo(27);
        assertThat(version(own)).isEqualTo(beforeVersion + 1);
        assertThat(revision(own)).isEqualTo(beforeRevision);
        assertThat(version(other)).isEqualTo(otherVersion);
    }

    @Test
    void shouldRollBackThePolicyAndBothCountersWithTheCaller() {
        var own = data.scenario().workspace().getId();
        var before = queries.findById(own).orElseThrow().reviewSettings();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var snapshot = queries.findByIdForUpdate(own).orElseThrow();
            commands.saveReviewPolicy(own, snapshot.reviewSettings().applyPatch(true, 37), true);
            status.setRollbackOnly();
        });
        assertThat(queries.findById(own).orElseThrow().reviewSettings()).isEqualTo(before);
        assertThat(version(own)).isEqualTo(before.configVersion());
        assertThat(revision(own)).isEqualTo(before.rolloutRevision());
    }

    private long version(long workspaceId) {
        return jdbc.sql("SELECT practice_config_version FROM workspace WHERE id = :workspace")
                .param("workspace", workspaceId)
                .query(Long.class)
                .single();
    }

    private long revision(long workspaceId) {
        return jdbc.sql("SELECT practice_rollout_revision FROM workspace WHERE id = :workspace")
                .param("workspace", workspaceId)
                .query(Long.class)
                .single();
    }
}
