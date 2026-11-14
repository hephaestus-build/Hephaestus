package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ConnectionAuditRepositoryIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionAuditRepository audits;

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
