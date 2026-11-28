package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.ScheduledLocks;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConnectionAuditRepositoryIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionAuditRepository audits;

    @Autowired
    private ConnectionAuditRetentionJob retention;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void shouldReadOnlyTheConnectionAuditOfTheNamedWorkspace() {
        var owner = persistUser("audit-owner");
        Workspace first = createWorkspace("audit-first", "First audit", "audit-first", AccountType.ORG, owner);
        Workspace second = createWorkspace("audit-second", "Second audit", "audit-second", AccountType.ORG, owner);
        Connection firstConnection = connection(first, "TAUDIT1");
        Connection secondConnection = connection(second, "TAUDIT2");
        ConnectionAudit firstAudit = audit(firstConnection);
        audit(secondConnection);

        assertThat(audits.findByWorkspaceIdAndConnectionIdOrderByOccurredAtDesc(first.getId(), firstConnection.getId()))
                .extracting(ConnectionAudit::getId)
                .containsExactly(firstAudit.getId());
        assertThat(audits.findByWorkspaceIdAndConnectionIdOrderByOccurredAtDesc(
                        second.getId(), firstConnection.getId()))
                .isEmpty();
        assertThat(audits.containsEvent(firstConnection.getId(), first.getId(), "INITIATE", "audit-test"))
                .isTrue();
        assertThat(audits.containsEvent(firstConnection.getId(), second.getId(), "INITIATE", "audit-test"))
                .isFalse();
    }

    /**
     * A GitHub installation binds under one key for its whole life, so the replay guard must still find a year-old
     * bind: the row stays, without the admin who acted or the reason they gave.
     */
    @Test
    void shouldRemoveTheActorOfEveryWorkspacesConnectionAuditAfterItsRetentionAndKeepTheReplayGuard() {
        var owner = persistUser("retention-owner");
        Workspace first = createWorkspace("retention-first", "First", "retention-first", AccountType.ORG, owner);
        Workspace second = createWorkspace("retention-second", "Second", "retention-second", AccountType.ORG, owner);
        Connection firstConnection = connection(first, "TRETAIN1");
        Connection secondConnection = connection(second, "TRETAIN2");
        long expired = recordedDaysAgo(
                event(firstConnection, "INSTALL_BIND", "install-bind-1", "Bound by an admin"),
                ConnectionAuditRetentionJob.RETENTION_DAYS + 1);
        long otherExpired = recordedDaysAgo(
                event(secondConnection, "SUSPEND", "suspend-2", "Paused for the audit"),
                ConnectionAuditRetentionJob.RETENTION_DAYS + 1);
        long inside = recordedDaysAgo(
                event(firstConnection, "PAT_ROTATED", "rotated-1", "Rotated by an admin"),
                ConnectionAuditRetentionJob.RETENTION_DAYS - 1);
        // An admin's own action names the admin's account.
        jdbc.update(
                "UPDATE connection_audit SET actor_account_id=? WHERE id IN (?,?)", accountId(owner), expired, inside);

        ScheduledLocks.free(jdbc, ConnectionAuditRetentionJob.class, "sweep");
        retention.sweep();

        assertThat(jdbc.queryForList(
                        "SELECT id||':'||coalesce(actor_ref,'-')||':'||coalesce(detail,'-')||':'||actor_kind"
                                + " FROM connection_audit WHERE id IN (?,?,?)",
                        String.class,
                        expired,
                        otherExpired,
                        inside))
                .containsExactlyInAnyOrder(
                        expired + ":-:-:ADMIN",
                        otherExpired + ":-:-:ADMIN",
                        inside + ":retention-owner:Rotated by an admin:ADMIN");
        assertThat(jdbc.queryForList(
                        "SELECT id FROM connection_audit WHERE actor_account_id=? AND id IN (?,?)",
                        Long.class,
                        accountId(owner),
                        expired,
                        inside))
                .containsExactly(inside);
        assertThat(audits.containsEvent(firstConnection.getId(), first.getId(), "INSTALL_BIND", "install-bind-1"))
                .as("a year-old bind still stops its replay")
                .isTrue();
        assertThat(audits.containsEvent(secondConnection.getId(), second.getId(), "SUSPEND", "suspend-2"))
                .isTrue();
    }

    private ConnectionAudit event(Connection connection, String type, String correlationId, String detail) {
        return audits.saveAndFlush(new ConnectionAudit(
                connection,
                type,
                IntegrationState.SUSPENDED,
                IntegrationState.ACTIVE,
                "ADMIN",
                "retention-owner",
                correlationId,
                detail));
    }

    private long recordedDaysAgo(ConnectionAudit audit, int days) {
        long id = Objects.requireNonNull(audit.getId());
        jdbc.update(
                "UPDATE connection_audit SET occurred_at = now() - make_interval(days => ?) WHERE id = ?", days, id);
        return id;
    }

    private Connection connection(Workspace workspace, String teamId) {
        return connections.saveAndFlush(new Connection(
                workspace,
                IntegrationKind.SLACK,
                teamId,
                new ConnectionConfig.SlackConfig(teamId, teamId, null, Set.of())));
    }

    private ConnectionAudit audit(Connection connection) {
        return audits.saveAndFlush(new ConnectionAudit(
                connection, "INITIATE", null, IntegrationState.ACTIVE, "ADMIN", "audit-owner", "audit-test", null));
    }
}
