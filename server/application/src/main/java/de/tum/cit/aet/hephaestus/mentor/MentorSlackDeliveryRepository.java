package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The Slack delivery state of ledger entries: the intent, effect and outcome of each Slack post
 * ({@link MentorSlackDelivery}), kept apart from the ledger's own queries.
 */
public interface MentorSlackDeliveryRepository extends Repository<MentorEntry, UUID> {

    /** See {@link MentorSlackDelivery#claim}. */
    @Modifying
    @Query("""
            UPDATE MentorEntry e SET e.slackDelivery = de.tum.cit.aet.hephaestus.mentor.MentorEntry.SlackDelivery.PENDING
            WHERE e.id = :id AND e.workspaceId = :workspaceId AND e.slackDelivery IS NULL
            """)
    int claimSlackDelivery(@Param("workspaceId") Long workspaceId, @Param("id") UUID id);

    /** Marks a post that is not settled as uncertain: one taken whose outcome is unknown, or one nobody took. */
    @Modifying
    @Query("""
            UPDATE MentorEntry e
               SET e.slackDelivery = de.tum.cit.aet.hephaestus.mentor.MentorEntry.SlackDelivery.UNCERTAIN
            WHERE e.id = :id AND e.workspaceId = :workspaceId
              AND (e.slackDelivery IS NULL
                   OR e.slackDelivery IN (de.tum.cit.aet.hephaestus.mentor.MentorEntry.SlackDelivery.PENDING,
                                          de.tum.cit.aet.hephaestus.mentor.MentorEntry.SlackDelivery.UNCERTAIN))
            """)
    int markSlackDeliveryUncertain(@Param("workspaceId") Long workspaceId, @Param("id") UUID id);

    @Modifying
    @Query("""
            UPDATE MentorEntry e SET e.slackDelivery = :outcome
            WHERE e.id = :id AND e.workspaceId = :workspaceId
            """)
    int settleSlackDelivery(
            @Param("workspaceId") Long workspaceId,
            @Param("id") UUID id,
            @Param("outcome") MentorEntry.SlackDelivery outcome);

    @Modifying
    @Query("UPDATE MentorEntry e SET e.slackTs = :slackTs WHERE e.id = :id AND e.workspaceId = :workspaceId")
    int setSlackTs(@Param("workspaceId") Long workspaceId, @Param("id") UUID id, @Param("slackTs") String slackTs);

    @Modifying
    @Query("UPDATE MentorEntry e SET e.slackStreamTs = :streamTs WHERE e.id = :id AND e.workspaceId = :workspaceId")
    int setSlackStreamTs(
            @Param("workspaceId") Long workspaceId, @Param("id") UUID id, @Param("streamTs") @Nullable String streamTs);

    /** Takes an unsettled Slack post for one more reconciliation: it becomes uncertain and counts the attempt. */
    @Modifying
    @Query("""
            UPDATE MentorEntry e
               SET e.slackDelivery = de.tum.cit.aet.hephaestus.mentor.MentorEntry.SlackDelivery.UNCERTAIN,
                   e.slackAttempts = e.slackAttempts + 1
            WHERE e.id = :id AND e.workspaceId = :workspaceId
              AND (e.slackDelivery IS NULL
                   OR e.slackDelivery IN (de.tum.cit.aet.hephaestus.mentor.MentorEntry.SlackDelivery.PENDING,
                                          de.tum.cit.aet.hephaestus.mentor.MentorEntry.SlackDelivery.UNCERTAIN))
            """)
    int retrySlackDelivery(@Param("workspaceId") Long workspaceId, @Param("id") UUID id);

    /** Settles as failed every unsettled Slack post that the reconciler took {@code maxAttempts} times. */
    @WorkspaceAgnostic("The Slack reconciler sweeps every workspace; each row carries its workspace")
    @Modifying
    @Query(value = """
            UPDATE mentor_entry SET slack_delivery = 'FAILED'
            WHERE ((kind = 'USER' AND surface = 'SLACK' AND slack_delivery IN ('PENDING', 'UNCERTAIN'))
                   OR (kind = 'DIGEST' AND slack_delivery IN ('PENDING', 'UNCERTAIN')))
              AND slack_attempts >= :maxAttempts
            """, nativeQuery = true)
    int failExhaustedSlackDeliveries(@Param("maxAttempts") int maxAttempts);

    /** An unsettled Slack post: a Slack question's reply, with its turn, or a weekly digest's note, without one. */
    interface UnsettledSlack {
        Long getWorkspaceId();

        UUID getEntryId();

        @Nullable
        UUID getTurnId();
    }

    /**
     * Slack posts that are not settled and are due: the reply to a Slack question whose turn ended at or after
     * {@code floor}, and the note of a weekly digest written at or after {@code floor} that was taken. The first try is
     * due {@code delay} after the turn ended or the digest was written; each try doubles the wait. A workspace gets at
     * most {@code perWorkspace} of the {@code limit} rows, longest due first, so one workspace cannot hold up the
     * others.
     */
    @WorkspaceAgnostic("The Slack reconciler sweeps every workspace; each row carries its workspace")
    @Query(value = """
            WITH unsettled AS (
                SELECT e.workspace_id, e.id AS entry_id, e.turn_id, t.finished_at AS since, e.slack_attempts
                FROM mentor_entry e
                JOIN mentor_turn t ON t.id = e.turn_id AND t.workspace_id = e.workspace_id
                WHERE e.kind = 'USER' AND e.surface = 'SLACK'
                  AND (e.slack_delivery IS NULL OR e.slack_delivery IN ('PENDING', 'UNCERTAIN'))
                  AND t.status IN ('DONE', 'FAILED', 'STOPPED') AND t.leased_until IS NULL
                UNION ALL
                SELECT e.workspace_id, e.id, NULL, e.created_at, e.slack_attempts
                FROM mentor_entry e
                WHERE e.kind = 'DIGEST' AND e.redacted_at IS NULL
                  AND e.slack_delivery IN ('PENDING', 'UNCERTAIN')
            ), due AS (
                SELECT workspace_id, entry_id, turn_id,
                       since + make_interval(secs => :delaySeconds * power(2, slack_attempts)) AS due_at
                FROM unsettled
                WHERE since >= :floor
            ), ranked AS (
                SELECT workspace_id, entry_id, turn_id, due_at,
                       row_number() OVER (PARTITION BY workspace_id ORDER BY due_at) AS place
                FROM due
                WHERE due_at <= :now
            )
            SELECT workspace_id AS "workspaceId", entry_id AS "entryId", turn_id AS "turnId"
            FROM ranked
            WHERE place <= :perWorkspace
            ORDER BY due_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UnsettledSlack> findDueSlackDeliveries(
            @Param("floor") Instant floor,
            @Param("now") Instant now,
            @Param("delaySeconds") double delaySeconds,
            @Param("perWorkspace") int perWorkspace,
            @Param("limit") int limit);
}
