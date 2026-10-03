package de.tum.cit.aet.hephaestus.integration.slack.events;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

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

    public SlackWorkspaceResolver(ConnectionRepository connectionRepository) {
        this.connectionRepository = connectionRepository;
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
}
