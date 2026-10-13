package de.tum.cit.aet.hephaestus.integration.slack.events;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Resolves the workspace that owns the ACTIVE Slack {@code Connection} for a given team.
 *
 * <p>This is a <em>tenant resolution</em>, not a tenant-scoped read: the caller has only the Slack {@code team_id}
 * and must discover which workspace it maps to.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackWorkspaceResolver {

    private static final Logger log = LoggerFactory.getLogger(SlackWorkspaceResolver.class);

    /** Two rows are enough to tell one owner from an ambiguous team. */
    private static final Limit AMBIGUITY_PROBE = Limit.of(2);

    private final ConnectionRepository connectionRepository;
    private final TransactionTemplate transactions;

    public SlackWorkspaceResolver(ConnectionRepository connectionRepository, TransactionTemplate transactions) {
        this.connectionRepository = connectionRepository;
        this.transactions = transactions;
    }

    /**
     * The workspace id of the ACTIVE Slack connection for {@code teamId}, if exactly one exists. More than one
     * means the one-active-per-team index was bypassed; picking either would route events to an arbitrary tenant.
     */
    public Optional<Long> resolveWorkspaceId(String teamId) {
        List<Long> workspaceIds = connectionRepository.findWorkspaceIdsByKindAndInstanceKeyAndState(
                IntegrationKind.SLACK, teamId, IntegrationState.ACTIVE, AMBIGUITY_PROBE);
        if (workspaceIds.size() > 1) {
            log.error(
                    "Slack team={} has more than one ACTIVE connection; refusing to resolve a workspace",
                    sanitizeForLog(teamId));
            return Optional.empty();
        }
        return workspaceIds.stream().findFirst();
    }

    /**
     * Runs {@code work} in one transaction that holds the lifecycle lock of the workspace's Slack connection, if the
     * connection is still ACTIVE for {@code teamId} once the lock is held. A disconnect takes the same lock before it
     * erases the workspace's Slack content. So either the disconnect committed first and {@code work} does not run, or
     * the disconnect waits and then erases what {@code work} wrote.
     *
     * @return what {@code work} returned; empty when the connection ended
     */
    public <T> Optional<T> whileConnected(long workspaceId, String teamId, Supplier<T> work) {
        return Optional.ofNullable(transactions.execute(status -> lockActive(workspaceId, teamId) ? work.get() : null));
    }

    private boolean lockActive(long workspaceId, String teamId) {
        Optional<Long> connectionId = connectionRepository
                .findActive(workspaceId, IntegrationKind.SLACK)
                .map(Connection::getId);
        if (connectionId.isEmpty()) {
            return false;
        }
        connectionRepository.acquireLifecycleLock(connectionId.get(), workspaceId);
        return connectionRepository
                .findWorkspaceIdsByKindAndInstanceKeyAndState(
                        IntegrationKind.SLACK, teamId, IntegrationState.ACTIVE, AMBIGUITY_PROBE)
                .equals(List.of(workspaceId));
    }
}
