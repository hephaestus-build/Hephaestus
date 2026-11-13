package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProviderInstanceRegistered;
import de.tum.cit.aet.hephaestus.integration.core.spi.IdentityProviderLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@RecordApplicationEvents
@Transactional
class ConnectionConsumerCommandsIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionCommands service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private SourceProviderNamespaces namespaces;

    @Autowired
    private IdentityProviderLookup providers;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ApplicationEvents events;

    @Test
    void shouldRefuseAnotherWorkspacesAnswerAndRefreshTheLockedConnection() {
        var mine = activeConnection();
        var foreign = activeConnection();
        long workspaceId = mine.getWorkspace().getId();
        assertThat(service.holdAnsweringConnection(workspaceId, IntegrationKind.SLACK, foreign.getId()))
                .isEmpty();

        long version = mine.getVersion();
        jdbc.update(
                "UPDATE connection SET display_name = ?, version = version + 1 WHERE id = ? AND workspace_id = ?",
                "Updated while cached",
                mine.getId(),
                workspaceId);
        assertThat(mine.getDisplayName()).isEqualTo("Before the lock");

        var held = service.holdAnsweringConnection(workspaceId, IntegrationKind.SLACK, mine.getId())
                .orElseThrow();
        assertThat(held.getId()).isEqualTo(mine.getId());
        assertThat(held.getDisplayName()).isEqualTo("Updated while cached");
        assertThat(held.getVersion()).isEqualTo(version + 1);
        assertThat(foreign.getDisplayName()).isEqualTo("Before the lock");
    }

    @Test
    void shouldRefuseToLockAnInactiveConnectionDespiteCachedActiveState() {
        var mine = activeConnection();
        long workspaceId = mine.getWorkspace().getId();
        jdbc.update(
                "UPDATE connection SET state = 'SUSPENDED', version = version + 1 WHERE id = ? AND workspace_id = ?",
                mine.getId(),
                workspaceId);
        assertThat(mine.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(service.holdActiveConnection(workspaceId, IntegrationKind.SLACK))
                .isEmpty();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldKeepAdministrativeAuditFieldsAndRollBackTheAuditWithTheCaller() {
        var connection = activeConnection();
        long workspaceId = connection.getWorkspace().getId();
        long actor = Objects.requireNonNull(accounts.saveAndFlush(new Account("command-" + UUID.randomUUID()))
                .getId());
        String correlation = UUID.randomUUID().toString();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            var held = service.holdActiveConnection(workspaceId, IntegrationKind.SLACK)
                    .orElseThrow();
            service.recordAdministrativeChange(held, "PROJECT_SELECTION_CHANGED", actor, correlation, "ALL → SELECTED");
            var audit = jdbc.queryForMap(
                    "SELECT connection_id, from_state, to_state, actor_kind, actor_account_id, correlation_id, detail"
                            + " FROM connection_audit WHERE workspace_id = ? AND connection_id = ? AND correlation_id = ?",
                    workspaceId,
                    connection.getId(),
                    correlation);
            assertThat(audit)
                    .containsEntry("connection_id", connection.getId())
                    .containsEntry("from_state", "ACTIVE")
                    .containsEntry("to_state", "ACTIVE")
                    .containsEntry("actor_kind", "ADMIN")
                    .containsEntry("actor_account_id", actor)
                    .containsEntry("correlation_id", correlation)
                    .containsEntry("detail", "ALL → SELECTED");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM connection_audit WHERE workspace_id = ? AND correlation_id = ?",
                        Integer.class,
                        workspaceId,
                        correlation))
                .isZero();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void shouldRefuseConsumerCommandsWithoutTheCallersTransaction() {
        var connection = activeConnection();
        long workspaceId = connection.getWorkspace().getId();
        assertThatThrownBy(() -> service.holdActiveConnection(workspaceId, IntegrationKind.SLACK))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(
                        () -> service.holdAnsweringConnection(workspaceId, IntegrationKind.SLACK, connection.getId()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> service.recordAdministrativeChange(
                        connection,
                        "PROJECT_SELECTION_CHANGED",
                        0L,
                        UUID.randomUUID().toString(),
                        "ALL → SELECTED"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void shouldRetainTheMirrorProviderSpellingWithoutRegisteringASourceCapture() {
        String spelling = "HTTPS://mirror-" + UUID.randomUUID() + ".example.test:443/";
        long id = namespaces.createIdentityProvider(IdentityProviderType.GITLAB, spelling);
        assertThat(providers.findId(IdentityProviderType.GITLAB, spelling)).contains(id);
        assertThat(providers.findById(id))
                .contains(new IdentityProviderLookup.Provider(id, IdentityProviderType.GITLAB, spelling));
        assertThat(events.stream(PersonProviderInstanceRegistered.class).filter(event -> event.providerId() == id))
                .isEmpty();
    }

    private Connection activeConnection() {
        String suffix = UUID.randomUUID().toString();
        var workspace = workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("hold-" + suffix));
        var connection = new Connection(
                workspace,
                IntegrationKind.SLACK,
                "T-" + suffix,
                new ConnectionConfig.SlackConfig("T-" + suffix, "Engineering", null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        connection.setDisplayName("Before the lock");
        return connections.saveAndFlush(connection);
    }
}
