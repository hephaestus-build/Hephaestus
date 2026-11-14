package de.tum.cit.aet.hephaestus.integration.slack.domain;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scoped access to per-person Slack consent rows. Every read carries the {@code workspace_id} predicate the tenancy
 * {@code StatementInspector} requires; the upsert is an {@code INSERT … ON CONFLICT} (INSERTs are exempt — creating a
 * row that already names its workspace cannot leak across tenants).
 */
public interface SlackParticipantConsentRepository
        extends JpaRepository<SlackParticipantConsent, SlackParticipantConsent.Id> {
    /**
     * The single read behind {@code SlackParticipantConsentGate}: is this individual currently opted OUT of
     * ingestion in this workspace? Absent row ⇒ {@code false} (allowed) — the person firewall fails <em>open</em> on
     * no decision (a person who never opted out is ingestible), while the capability flag and channel gate fail
     * closed.
     */
    boolean existsByWorkspaceIdAndSlackUserIdAndIngestionOptedOutTrue(Long workspaceId, String slackUserId);

    /** Permanent person-erasure controls are separate from reversible workspace consent. */
    @Query(value = """
        SELECT EXISTS (
            SELECT 1 FROM identity_restriction s
            JOIN identity_provider p ON p.id = s.provider_id
                AND p.type = 'SLACK' AND p.server_url = 'https://slack.com'
            JOIN connection c ON c.workspace_id = :workspaceId
                AND c.config->>'teamId' = s.team_key
            WHERE s.provider_user_key = :slackUserId AND s.kind = 'ERASED'
        )
        """, nativeQuery = true)
    boolean isPersonProcessingSuppressed(
            @Param("workspaceId") long workspaceId, @Param("slackUserId") String slackUserId);

    /**
     * Records a channel-message ingestion choice made at {@code decidedAt}, unless a later choice is already stored.
     * Slack can deliver an action again after a later one, and an older choice must not undo it. An equal time is the
     * same choice again. Research eligibility is determined by the native-account consent ledger, not this row.
     *
     * @return 1 when the choice is now the stored one, 0 when a later choice kept it out
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO slack_participant_consent (workspace_id, slack_user_id, ingestion_opted_out, decided_at)
        VALUES (:workspaceId, :slackUserId, :optedOut, :decidedAt)
        ON CONFLICT (workspace_id, slack_user_id) DO UPDATE SET
            ingestion_opted_out = EXCLUDED.ingestion_opted_out,
            decided_at = EXCLUDED.decided_at
        WHERE slack_participant_consent.decided_at <= EXCLUDED.decided_at
        """, nativeQuery = true)
    int decide(
            @Param("workspaceId") long workspaceId,
            @Param("slackUserId") String slackUserId,
            @Param("optedOut") boolean optedOut,
            @Param("decidedAt") Instant decidedAt);

    /**
     * Count of individuals who have opted OUT of ingestion in this workspace. Person opt-out is workspace-scoped
     * (not per-channel), so this is a single workspace-wide count.
     */
    long countByWorkspaceIdAndIngestionOptedOutTrue(Long workspaceId);

    /** Workspace purge: delete every consent row for one workspace. */
    long deleteByWorkspaceId(Long workspaceId);
}
