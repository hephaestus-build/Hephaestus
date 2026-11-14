package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ConnectionLifecycleIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionAuditRepository audits;

    @Autowired
    private ConnectionService service;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private CredentialBundleConverter credentials;

    @Test
    void shouldRejectAnUnverifiedRevivalWithoutChangingTheConnectionOrItsAudit() {
        var original = connection();
        var stopped = service.transition(
                original,
                request(
                        IntegrationState.UNINSTALLED,
                        "UNINSTALL",
                        UUID.randomUUID().toString(),
                        "Disconnected"));
        long generation = stopped.getLifecycleGeneration();
        long credentialGeneration = stopped.getCredentialsGeneration();
        assertThatThrownBy(() -> service.transition(
                        stopped,
                        request(
                                IntegrationState.ACTIVE,
                                "REVIVE",
                                UUID.randomUUID().toString(),
                                "Invalid")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot change from");
        var current = current(stopped);
        assertThat(current.getState()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(current.getLifecycleGeneration()).isEqualTo(generation);
        assertThat(current.getCredentialsGeneration()).isEqualTo(credentialGeneration);
        assertThat(current.getCredentialsEncrypted()).isNull();
        assertThat(current.getStateReason()).isEqualTo("Disconnected");
        assertThat(audits.findByWorkspaceIdAndConnectionIdOrderByOccurredAtDesc(
                        current.getWorkspace().getId(), current.getId()))
                .hasSize(1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "delivery-1")
    void shouldNotEraseTheReconnectedGenerationWhenADisconnectIsReplayed(@Nullable String correlation) {
        var original = connection();
        var oldMonitor = monitor(original, "before");
        var calls = new AtomicInteger();
        var disconnect = request(IntegrationState.UNINSTALLED, "DISCONNECT", correlation, "Disconnected");
        var stopped = service.disconnectForErasure(original, disconnect, () -> {
            calls.incrementAndGet();
            monitors.deleteById(oldMonitor.getId());
        });
        assertThat(monitors.existsById(oldMonitor.getId())).isFalse();
        var active = Objects.requireNonNull(new TransactionTemplate(transactions).execute(status -> {
            var row = current(stopped);
            row.setCredentials(new BearerToken("new-generation", null), credentials);
            return service.transition(
                    row,
                    request(
                            IntegrationState.ACTIVE,
                            "INITIATE",
                            UUID.randomUUID().toString(),
                            "Connected again"));
        }));
        assertThat(active.getId()).isEqualTo(original.getId());
        assertThat(active.getLifecycleGeneration()).isEqualTo(original.getLifecycleGeneration() + 2);
        var replacementMonitor = monitor(active, "after");
        long generation = active.getLifecycleGeneration();
        long credentialGeneration = active.getCredentialsGeneration();
        var replay = service.disconnectForErasure(active, disconnect, () -> {
            calls.incrementAndGet();
            monitors.deleteById(replacementMonitor.getId());
        });
        assertThat(replay.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(current(active).getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(current(active).getLifecycleGeneration()).isEqualTo(generation);
        assertThat(current(active).getCredentialsGeneration()).isEqualTo(credentialGeneration);
        assertThat(current(active).getStateReason()).isEqualTo("Connected again");
        assertThat(service.findActiveBearerToken(active.getWorkspace().getId(), IntegrationKind.GITHUB)
                        .orElseThrow()
                        .token())
                .isEqualTo("new-generation");
        assertThat(monitors.existsById(replacementMonitor.getId())).isTrue();
        assertThat(calls).hasValue(1);
        assertThat(audits.findByWorkspaceIdAndConnectionIdOrderByOccurredAtDesc(
                        active.getWorkspace().getId(), active.getId()))
                .hasSize(2);
    }

    @Test
    void shouldKeepTheReasonGenerationAndAuditWhenTheSameStateIsRequestedAgain() {
        var original = connection();
        var stopped = service.transition(original, request(IntegrationState.SUSPENDED, "SUSPEND", "first", "Paused"));
        var repeated =
                service.transition(stopped, request(IntegrationState.SUSPENDED, "SUSPEND", "different", "Ignored"));
        var current = current(repeated);
        assertThat(current.getStateReason()).isEqualTo("Paused");
        assertThat(current.getLifecycleGeneration()).isEqualTo(stopped.getLifecycleGeneration());
        assertThat(audits.findByWorkspaceIdAndConnectionIdOrderByOccurredAtDesc(
                        current.getWorkspace().getId(), current.getId()))
                .hasSize(1);
    }

    private Connection connection() {
        var workspace =
                workspaces.saveAndFlush(WorkspaceTestFixtures.activeWorkspace("lifecycle-" + UUID.randomUUID()));
        var connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "pat",
                new ConnectionConfig.GitHubPatConfig("course", null, Set.of()));
        connection.setCredentials(new BearerToken("old-generation", null), credentials);
        ConnectionFixtures.reachState(connection, IntegrationState.ACTIVE);
        return connections.saveAndFlush(connection);
    }

    private Connection current(Connection connection) {
        return connections
                .findByIdAndWorkspaceId(
                        connection.getId(), connection.getWorkspace().getId())
                .orElseThrow();
    }

    private RepositoryToMonitor monitor(Connection connection, String suffix) {
        var monitor = new RepositoryToMonitor();
        monitor.setWorkspace(connection.getWorkspace());
        monitor.setNameWithOwner("course/" + suffix);
        monitor.setNativeId(suffix.equals("before") ? 1L : 2L);
        return monitors.saveAndFlush(monitor);
    }

    private static TransitionRequest request(
            IntegrationState next, String event, @Nullable String correlation, String reason) {
        return new TransitionRequest(next, event, "SYSTEM", null, correlation, reason);
    }
}
