package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntry;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditPort;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.connection.BearerTokenReplacement;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.workspace.audit.WorkspaceAuditSnapshots;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspaceFeaturesRequestDTO;
import de.tum.cit.aet.hephaestus.workspace.events.WorkspaceScheduleChangedEvent;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class WorkspaceSettingsService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSettingsService.class);
    private static final Pattern SLACK_CHANNEL_ID_PATTERN = Pattern.compile("^[CG][A-Z0-9]{8,}$");

    private final WorkspaceRepository workspaceRepository;
    private final ConfigAuditPort configAudit;
    private final ConnectionService connectionService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * Update the leaderboard schedule for a workspace.
     *
     * @param workspaceId the workspace ID
     * @param day the day of week (1=Monday, 7=Sunday)
     * @param time the time in HH:mm format
     * @return the updated workspace
     */
    @Transactional
    public Workspace updateSchedule(Long workspaceId, Integer day, String time) {
        return updateScheduleInTransaction(workspaceId, day, time);
    }

    private Workspace updateScheduleInTransaction(Long workspaceId, Integer day, String time) {
        Workspace workspace = requireWorkspace(workspaceId);

        if (day != null) {
            if (day < 1 || day > 7) {
                throw new IllegalArgumentException("Day must be between 1 (Monday) and 7 (Sunday)");
            }
            workspace.setLeaderboardScheduleDay(day);
        }

        if (time != null) {
            validateTimeFormat(time);
            workspace.setLeaderboardScheduleTime(time);
        }

        Workspace saved = workspaceRepository.save(workspace);
        log.info("Updated workspace schedule: workspaceId={}, day={}, time={}", workspaceId, day, time);
        // Re-register the per-workspace leaderboard cron at the new cadence without a restart.
        eventPublisher.publishEvent(new WorkspaceScheduleChangedEvent(workspaceId));
        return saved;
    }

    /**
     * Update notification settings for a workspace.
     *
     * <p>{@code enabled} stays on {@link Workspace#leaderboardNotificationEnabled} (a UI
     * toggle, not a Slack-side configuration). {@code team} and {@code channelId} are now
     * persisted on the Slack {@link ConnectionConfig.SlackConfig} via
     * {@link ConnectionService#updateConfig} — caller must have already provisioned a
     * Slack Connection (typically via the Slack OAuth callback). PATCH semantics: null
     * fields are not touched.
     *
     * @param workspaceId the workspace ID
     * @param enabled whether notifications are enabled
     * @param team the team identifier to notify (treated as the human-readable team label)
     * @param channelId the Slack channel ID
     * @return the updated workspace
     */
    @Transactional
    public Workspace updateNotifications(
            Long workspaceId, @Nullable Boolean enabled, @Nullable String team, @Nullable String channelId) {
        return updateNotificationsInTransaction(workspaceId, enabled, team, channelId);
    }

    private Workspace updateNotificationsInTransaction(
            Long workspaceId, @Nullable Boolean enabled, @Nullable String team, @Nullable String channelId) {
        Workspace workspace = requireWorkspace(workspaceId);

        if (enabled != null) {
            workspace.setLeaderboardNotificationEnabled(enabled);
            workspace = workspaceRepository.save(workspace);
        }

        if (channelId != null) {
            validateSlackChannelId(channelId);
        }

        if (team != null || channelId != null) {
            Optional<?> updated = connectionService.updateConfig(workspaceId, IntegrationKind.SLACK, cfg -> {
                ConnectionConfig.SlackConfig slack = (ConnectionConfig.SlackConfig) cfg;
                return new ConnectionConfig.SlackConfig(
                        slack.teamId(),
                        slack.teamName(),
                        channelId != null ? channelId : slack.notificationChannelId(),
                        team != null ? team : slack.teamLabel(),
                        slack.retentionDays(),
                        slack.enabledStreams());
            });
            if (updated.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "No active Slack Connection — reconnect via the admin panel before changing channel/team.");
            }
        }

        log.info("Updated workspace notifications: workspaceId={}, enabled={}", workspaceId, enabled);
        return workspace;
    }

    /** Updates the weekly digest schedule and notification settings atomically. */
    @Transactional
    public Workspace updateLeaderboardDigest(
            Long workspaceId,
            Integer day,
            String time,
            Boolean enabled,
            @Nullable String team,
            @Nullable String channelId) {
        updateScheduleInTransaction(workspaceId, day, time);
        return updateNotificationsInTransaction(workspaceId, enabled, team, channelId);
    }

    /**
     * Update the personal access token for a workspace. Rotates the bearer credential on
     * whichever SCM Connection (GitHub PAT or GitLab) is currently active — the caller
     * controls which workspace this hits via {@code workspaceId}, the kind is resolved
     * from the active Connection.
     */
    @Transactional
    public Workspace updateToken(Long workspaceId, String token) {
        Workspace workspace = requireWorkspace(workspaceId);
        IntegrationKind kind = connectionService
                .findActiveProviderKind(workspaceId)
                .filter(k -> k == IntegrationKind.GITHUB || k == IntegrationKind.GITLAB)
                .orElseThrow(() -> new IllegalStateException("Cannot rotate PAT for workspace " + workspaceId
                        + ": no active GitHub or GitLab Connection. Bind a provider first."));
        // The write says whether a token was there to replace; it never reads the old one, since this
        // is the way out for a token the server can no longer read, and it refuses a row whose mode
        // takes no token.
        BearerTokenReplacement rotation = connectionService
                .rotateBearerToken(workspaceId, kind, new BearerToken(token, null))
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot rotate PAT for workspace " + workspaceId + ": no active " + kind + " Connection."));
        boolean hadToken = rotation.replacedExisting();
        // rotatedAt is what makes this row exist at all: rotating an already-set token leaves every
        // other component identical, and ConfigAuditRecorder drops an UPDATE whose diff is empty — so
        // a presence-only snapshot would silently record nothing for the most sensitive change here.
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_TOKEN,
                workspaceId,
                workspaceId,
                new WorkspaceAuditSnapshots.TokenSnapshot(hadToken, kind.name(), null),
                new WorkspaceAuditSnapshots.TokenSnapshot(true, kind.name(), clock.instant())));
        log.info("Updated workspace PAT: workspaceId={}, kind={}", workspaceId, kind);
        return workspace;
    }

    /**
     * Update public visibility for a workspace.
     *
     * @param workspaceId the workspace ID
     * @param isPubliclyViewable whether the workspace is publicly viewable
     * @return the updated workspace
     */
    @Transactional
    public Workspace updatePublicVisibility(Long workspaceId, Boolean isPubliclyViewable) {
        Workspace workspace = requireWorkspace(workspaceId);
        var beforeVis = new WorkspaceAuditSnapshots.VisibilitySnapshot(workspace.getIsPubliclyViewable());
        workspace.setIsPubliclyViewable(isPubliclyViewable);
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_VISIBILITY,
                workspaceId,
                workspaceId,
                beforeVis,
                new WorkspaceAuditSnapshots.VisibilitySnapshot(isPubliclyViewable)));
        log.info("Updated workspace visibility: workspaceId={}, isPublic={}", workspaceId, isPubliclyViewable);
        return workspaceRepository.save(workspace);
    }

    /**
     * Update workspace feature flags.
     * Null fields in the request DTO are ignored (PATCH semantics).
     *
     * @param workspaceId the workspace ID
     * @param request the feature flags to update (null fields are left unchanged)
     * @return the updated workspace
     */
    @Transactional
    public Workspace updateFeatures(Long workspaceId, UpdateWorkspaceFeaturesRequestDTO request) {
        Workspace workspace = requireWorkspace(workspaceId);
        var before = WorkspaceAuditSnapshots.FeaturesSnapshot.of(workspace.getFeatures());
        workspace.getFeatures().applyPatch(request);
        configAudit.record(ConfigAuditEntry.updated(
                ConfigAuditEntityType.WORKSPACE_FEATURES,
                workspaceId,
                workspaceId,
                before,
                WorkspaceAuditSnapshots.FeaturesSnapshot.of(workspace.getFeatures())));

        log.info(
                "Updated workspace features: workspaceId={}, practices={}, leaderboard={}, progression={}, leagues={}",
                workspaceId,
                request.practicesEnabled(),
                request.leaderboardEnabled(),
                request.progressionEnabled(),
                request.leaguesEnabled());
        return workspaceRepository.save(workspace);
    }

    private Workspace requireWorkspace(Long workspaceId) {
        return workspaceRepository
                .findById(workspaceId)
                .orElseThrow(() -> new EntityNotFoundException("Workspace", workspaceId.toString()));
    }

    private void validateTimeFormat(String time) {
        try {
            // Called for the exception, not the value: DateTimeFormatter offers no isValid() predicate.
            LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm")); // NOPMD
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid time format. Expected HH:mm", e);
        }
    }

    private void validateSlackChannelId(String channelId) {
        if (!SLACK_CHANNEL_ID_PATTERN.matcher(channelId).matches()) {
            throw new IllegalArgumentException("Invalid Slack channel ID format");
        }
    }
}
