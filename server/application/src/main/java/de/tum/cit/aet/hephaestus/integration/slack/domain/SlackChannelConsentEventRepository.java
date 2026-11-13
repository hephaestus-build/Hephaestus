package de.tum.cit.aet.hephaestus.integration.slack.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Insert-and-read-only access to the Slack channel consent-transition audit trail. The interface deliberately
 * extends the narrow {@link Repository} base (not {@code JpaRepository}) and exposes ONLY {@link #save} (append a
 * transition) and a workspace-scoped chronological read — there is no update or delete method, so the audit trail
 * is immutable through this surface, matching the {@code @Immutable} entity.
 *
 * <p>Every read carries the {@code workspace_id} predicate the tenancy {@code StatementInspector} requires;
 * inserts are exempt (a row that already names its workspace cannot leak across tenants).
 */
public interface SlackChannelConsentEventRepository extends Repository<SlackChannelConsentEvent, Long> {
    @Query(
            "SELECT e FROM SlackChannelConsentEvent e WHERE e.workspaceId=:workspaceId AND e.id=:requestId AND e.toState=de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState.ACTIVATING")
    Optional<SlackChannelConsentEvent> findActivationRequest(
            @Param("workspaceId") long workspaceId, @Param("requestId") long requestId);

    @Query(
            "SELECT MAX(e.id) FROM SlackChannelConsentEvent e WHERE e.workspaceId=:workspaceId AND e.slackChannelId=:channelId AND e.toState=de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState.ACTIVATING")
    @Nullable
    Long findLatestActivationRevision(@Param("workspaceId") long workspaceId, @Param("channelId") String channelId);

    @Query(
            "SELECT MIN(e.createdAt) FROM SlackChannelConsentEvent e WHERE e.workspaceId=:workspaceId AND e.slackChannelId=:channelId AND e.id>:setupRevision AND e.toState=de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState.ACTIVATING")
    @Nullable
    Instant findFirstActivationTime(
            @Param("workspaceId") long workspaceId,
            @Param("channelId") String channelId,
            @Param("setupRevision") long setupRevision);

    @Query(
            "SELECT MAX(e.id) FROM SlackChannelConsentEvent e WHERE e.workspaceId=:workspaceId AND e.slackChannelId=:channelId AND e.toState IN (de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState.PENDING, de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMonitoredChannel.ConsentState.REVOKED)")
    @Nullable
    Long findLatestSetupRevision(@Param("workspaceId") long workspaceId, @Param("channelId") String channelId);

    /** Append one consent transition. Insert-only: the entity is {@code @Immutable}, so this never updates. */
    SlackChannelConsentEvent save(SlackChannelConsentEvent event);

    /** The full transition history of one channel, oldest first — the admin audit listing. */
    List<SlackChannelConsentEvent> findByWorkspaceIdAndSlackChannelIdOrderByCreatedAtAscIdAsc(
            Long workspaceId, String slackChannelId);
}
