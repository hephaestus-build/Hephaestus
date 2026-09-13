package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

@RecordApplicationEvents
class IntegrationAttentionIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private IntegrationAttentionService attention;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ApplicationEventPublisher publisher;

    @Autowired
    private ApplicationEvents events;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void shouldDeduplicateProblemsAndRecoverOnlyAnExistingIncident() {
        Connection connection = connection();
        long workspace = connection.getWorkspace().getId();
        long id = connection.getId();
        var problem = IntegrationAttentionChangedEvent.Problem.CREDENTIAL_REVOKED;
        attention.report(id, workspace, problem, true);
        attention.report(id, workspace, problem, false);
        attention.report(id, workspace, problem, false);
        var changes = events.stream(IntegrationAttentionChangedEvent.class)
                .filter(e -> e.connectionId() == id)
                .toList();
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().recovered()).isFalse();
        assertThat(attention.isCurrent(changes.getFirst())).isTrue();

        transactions.executeWithoutResult(tx ->
                publisher.publishEvent(new ConnectionLifecycleEvent.Activated(id, workspace, IntegrationKind.SLACK)));
        var recovered = events.stream(IntegrationAttentionChangedEvent.class)
                .filter(e -> e.connectionId() == id && e.recovered())
                .toList();
        assertThat(recovered).hasSize(1);
        assertThat(attention.isCurrent(changes.getFirst())).isFalse();
        assertThat(attention.isCurrent(recovered.getFirst())).isTrue();
    }

    @Test
    void shouldNotReadAnotherWorkspaceOrPersistARolledBackIncident() {
        Connection connection = connection();
        long workspace = connection.getWorkspace().getId();
        long id = connection.getId();
        assertThat(attention.isCurrent(new IntegrationAttentionChangedEvent(
                        id,
                        workspace + 1000000,
                        IntegrationKind.SLACK,
                        IntegrationAttentionChangedEvent.Problem.CREDENTIAL_REVOKED,
                        false,
                        0L,
                        java.time.Instant.now())))
                .isFalse();
        assertThat(connections
                        .findByIdAndWorkspaceId(id, workspace)
                        .orElseThrow()
                        .getAttentionRevision())
                .isZero();
        transactions.executeWithoutResult(tx -> {
            attention.report(id, workspace, IntegrationAttentionChangedEvent.Problem.CREDENTIAL_REVOKED, false);
            tx.setRollbackOnly();
        });
        assertThat(connections
                        .findByIdAndWorkspaceId(id, workspace)
                        .orElseThrow()
                        .getAttentionProblem())
                .isNull();
        var change = events.stream(IntegrationAttentionChangedEvent.class)
                .filter(e -> e.connectionId() == id)
                .findFirst()
                .orElseThrow();
        assertThat(attention.isCurrent(change)).isFalse();
    }

    private Connection connection() {
        String suffix = UUID.randomUUID().toString();
        var owner = persistUser("attention-" + suffix);
        var workspace =
                createWorkspace("attention-" + suffix, "Attention test", "attention-org", AccountType.ORG, owner);
        return connections.save(new Connection(
                workspace,
                IntegrationKind.SLACK,
                "T" + suffix,
                new ConnectionConfig.SlackConfig(
                        "T" + suffix, "Attention test", null, null, null, java.util.Set.of())));
    }
}
