package de.tum.cit.aet.hephaestus.integration.slack.domain;

import org.jspecify.annotations.Nullable;
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

    /**
     * Idempotently records channel-message ingestion opt-out while preserving the historical research column.
     * Research eligibility is determined by the native-account consent ledger, not this row.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO slack_participant_consent (workspace_id, slack_user_id, ingestion_opted_out, research_opted_out, source, decided_at)
        VALUES (:workspaceId, :slackUserId, true, false, :source, now())
        ON CONFLICT (workspace_id, slack_user_id) DO UPDATE SET
            ingestion_opted_out = true,
            source = EXCLUDED.source,
            decided_at = now()
        """, nativeQuery = true)
    void optOutOfIngestion(
            @Param("workspaceId") long workspaceId,
            @Param("slackUserId") String slackUserId,
            @Param("source") @Nullable String source);

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO slack_participant_consent (workspace_id, slack_user_id, ingestion_opted_out, research_opted_out, source, decided_at)
        VALUES (:workspaceId, :slackUserId, false, false, :source, now())
        ON CONFLICT (workspace_id, slack_user_id) DO UPDATE SET
            ingestion_opted_out = false,
            source = EXCLUDED.source,
            decided_at = now()
        """, nativeQuery = true)
    void optInToIngestion(
            @Param("workspaceId") long workspaceId,
            @Param("slackUserId") String slackUserId,
            @Param("source") @Nullable String source);

    /**
     * Count of individuals who have opted OUT of ingestion in this workspace. Person opt-out is workspace-scoped
     * (not per-channel), so this is a single workspace-wide count.
     */
    long countByWorkspaceIdAndIngestionOptedOutTrue(Long workspaceId);

    /** Workspace purge: delete every consent row for one workspace. */
    long deleteByWorkspaceId(Long workspaceId);
}
