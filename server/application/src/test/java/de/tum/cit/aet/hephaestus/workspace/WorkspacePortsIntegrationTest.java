package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.spi.RepositoryMonitorCommands;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceCommands;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceMemberships;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceQueries;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRepositoryMonitors;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceRoots;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
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
    WorkspaceRoots roots;

    @Autowired
    WorkspaceRepositoryMonitors monitors;

    @Autowired
    RepositoryMonitorCommands monitorCommands;

    @Autowired
    RepositoryToMonitorRepository monitorRows;

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

    @Test
    void shouldKeepBothBudgetCapsScopedAndRollThemBackWithTheCaller() {
        var own = data.scenario().workspace();
        var other = data.scenario().workspace();
        var before = queries.llmBudgets(own.getId()).orElseThrow();
        var otherBefore = queries.llmBudgets(other.getId()).orElseThrow();
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            var instance = commands.updateInstanceLlmBudget(own.getWorkspaceSlug(), new BigDecimal("12.00"));
            var provider = commands.updateOwnProviderLlmBudget(own.getId(), new BigDecimal("3.50"));
            assertThat(instance.workspaceId()).isEqualTo(own.getId());
            assertThat(instance.previousLimit()).isEqualTo(before.instanceBudgetUsd());
            assertThat(provider.workspaceId()).isEqualTo(own.getId());
            assertThat(provider.previousLimit()).isEqualTo(before.ownProviderBudgetUsd());
        });
        var stored = queries.llmBudgets(own.getId()).orElseThrow();
        assertThat(stored.instanceBudgetUsd()).isEqualByComparingTo("12.00");
        assertThat(stored.ownProviderBudgetUsd()).isEqualByComparingTo("3.50");
        assertThat(jdbc.sql("SELECT monthly_llm_budget_usd FROM workspace WHERE id = :workspace")
                        .param("workspace", own.getId())
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("12.00");
        assertThat(jdbc.sql("SELECT monthly_byo_llm_budget_usd FROM workspace WHERE id = :workspace")
                        .param("workspace", own.getId())
                        .query(BigDecimal.class)
                        .single())
                .isEqualByComparingTo("3.50");
        transaction.executeWithoutResult(status -> {
            commands.updateInstanceLlmBudget(own.getWorkspaceSlug(), null);
            commands.updateOwnProviderLlmBudget(own.getId(), null);
            status.setRollbackOnly();
        });
        assertThat(queries.llmBudgets(own.getId()).orElseThrow()).isEqualTo(stored);
        assertThat(queries.llmBudgets(other.getId()).orElseThrow()).isEqualTo(otherBefore);
    }

    @Autowired
    WorkspaceMemberships members;

    @Autowired
    MembershipRepository memberships;

    @Test
    void shouldKeepActiveAndRetainedContributorChoicesScopedAndTransactional() {
        var own = data.scenario();
        var other = data.scenario();
        long ownAccount = Objects.requireNonNull(own.account().getId());
        long otherAccount = Objects.requireNonNull(other.account().getId());
        var transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(status -> {
            members.setContributorHidden(own.workspace().getId(), ownAccount, true);
            status.setRollbackOnly();
        });
        assertThat(memberships.findByWorkspace_IdAndId_AccountId(own.workspace().getId(), ownAccount))
                .hasValueSatisfying(member -> {
                    assertThat(member.isHidden()).isFalse();
                    assertThat(member.getLeftAt()).isNull();
                    assertThat(member.getRole()).isEqualTo(Membership.WorkspaceRole.OWNER);
                });
        transaction.executeWithoutResult(
                status -> members.setContributorHidden(own.workspace().getId(), otherAccount, true));
        var retained = memberships
                .findByWorkspace_IdAndId_AccountId(own.workspace().getId(), otherAccount)
                .orElseThrow();
        assertThat(retained.isHidden()).isTrue();
        assertThat(retained.getLeftAt()).isNotNull();
        assertThat(retained.getRole()).isEqualTo(Membership.WorkspaceRole.MEMBER);
        assertThat(members.activeRole(own.workspace().getId(), otherAccount)).isEmpty();
        var leftAt = retained.getLeftAt();
        transaction.executeWithoutResult(
                status -> members.setContributorHidden(own.workspace().getId(), otherAccount, false));
        assertThat(memberships.findByWorkspace_IdAndId_AccountId(own.workspace().getId(), otherAccount))
                .hasValueSatisfying(member -> {
                    assertThat(member.isHidden()).isFalse();
                    assertThat(member.getLeftAt()).isEqualTo(leftAt);
                });
        assertThat(memberships.findByWorkspace_IdAndId_AccountId(
                        other.workspace().getId(), otherAccount))
                .hasValueSatisfying(member -> {
                    assertThat(member.isHidden()).isFalse();
                    assertThat(member.getLeftAt()).isNull();
                    assertThat(member.getRole()).isEqualTo(Membership.WorkspaceRole.OWNER);
                });
    }

    @Test
    void shouldChangeTheTenantRootOnlyInsideTheCallersTransaction() {
        var own = data.scenario().workspace().getId();
        var other = data.scenario().workspace().getId();
        assertThatThrownBy(() -> roots.suspend(own)).isInstanceOf(IllegalTransactionStateException.class);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(roots.suspend(own)).isTrue();
            assertThat(roots.suspend(own)).isFalse();
            roots.renameAccount(own, "renamed-account");
            roots.selectRepositories(own, RepositorySelection.ALL);
        });
        assertThat(column(own, "status")).isEqualTo("SUSPENDED");
        assertThat(column(own, "account_login")).isEqualTo("renamed-account");
        assertThat(column(own, "repository_selection")).isEqualTo("ALL");
        assertThat(column(other, "status")).isEqualTo("ACTIVE");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThat(roots.resume(own)).isTrue();
            status.setRollbackOnly();
        });
        assertThat(column(own, "status")).isEqualTo("SUSPENDED");
    }

    @Test
    void shouldRenameOnlyAMonitorOfTheNamedWorkspace() {
        var own = data.scenario().workspace();
        var other = data.scenario().workspace().getId();
        var monitor = new RepositoryToMonitor();
        monitor.setNameWithOwner("acme/" + own.getId());
        monitor.setNativeId(own.getId() + 900_000L);
        monitor.setWorkspace(own);
        long monitorId = monitorRows.save(monitor).getId();
        var transaction = new TransactionTemplate(transactions);

        assertThatThrownBy(() -> transaction.executeWithoutResult(
                        status -> monitorCommands.rename(other, monitorId, "acme/stolen")))
                .isInstanceOf(NoSuchElementException.class);
        transaction.executeWithoutResult(status -> monitorCommands.rename(own.getId(), monitorId, "acme/renamed"));

        assertThat(monitors.monitors(own.getId()))
                .extracting(RepositoryToMonitor::getNameWithOwner)
                .containsExactly("acme/renamed");
        assertThat(monitors.monitors(other)).isEmpty();
    }

    private String column(long workspaceId, String column) {
        return jdbc.sql("SELECT " + column + " FROM workspace WHERE id = :workspace")
                .param("workspace", workspaceId)
                .query(String.class)
                .single();
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
