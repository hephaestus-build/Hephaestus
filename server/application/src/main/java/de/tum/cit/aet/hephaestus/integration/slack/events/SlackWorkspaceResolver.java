package de.tum.cit.aet.hephaestus.integration.slack.events;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves the workspace that owns the ACTIVE Slack {@code Connection} for a given team.
 *
 * <p>This is a <em>tenant resolution</em>, not a tenant-scoped read: the caller has only the Slack {@code team_id}
 * and must discover which workspace it maps to. The {@code connection} table is itself workspace-scoped, so a JPA
 * query keyed on {@code instance_key} alone would be rejected by the tenancy {@code StatementInspector} (there is no
 * {@code workspace_id} to predicate on — that is exactly what we are resolving). A narrow raw {@code JdbcTemplate}
 * read is the deliberate, isolated exception.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.slack.enabled", havingValue = "true")
public class SlackWorkspaceResolver {

    private static final Logger log = LoggerFactory.getLogger(SlackWorkspaceResolver.class);

    private final JdbcTemplate jdbc;

    public SlackWorkspaceResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The workspace id of the ACTIVE Slack connection for {@code teamId}, if exactly one exists. More than one
     * means the one-active-per-team index was bypassed; picking either would route events to an arbitrary tenant.
     */
    public Optional<Long> resolveWorkspaceId(String teamId) {
        List<Long> workspaceIds = jdbc.query(
                "SELECT workspace_id FROM connection WHERE kind = 'SLACK' AND instance_key = ? AND state = 'ACTIVE' LIMIT 2",
                (rs, row) -> rs.getLong(1),
                teamId);
        if (workspaceIds.size() > 1) {
            log.error(
                    "Slack team={} has more than one ACTIVE connection; refusing to resolve a workspace",
                    sanitizeForLog(teamId));
            return Optional.empty();
        }
        return workspaceIds.stream().findFirst();
    }
}
