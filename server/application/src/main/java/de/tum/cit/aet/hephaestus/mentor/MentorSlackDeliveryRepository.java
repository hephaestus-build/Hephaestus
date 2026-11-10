package de.tum.cit.aet.hephaestus.mentor;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Where the Slack posts of ledger entries are, and how they ended: the message that shows an entry, the stream of a
 * reply, and the outcome of a post.
 */
public interface MentorSlackDeliveryRepository extends Repository<MentorEntry, UUID> {

    /** Whether Hephaestus already answered in Slack in this conversation, before the message at {@code seq}. */
    @Query("""
            SELECT COUNT(e) > 0 FROM MentorEntry e
            WHERE e.workspaceId = :workspaceId AND e.conversationId = :conversationId
              AND e.kind = de.tum.cit.aet.hephaestus.mentor.MentorEntry.Kind.HEPH
              AND e.slackTs IS NOT NULL
              AND EXISTS (
                  SELECT q.id FROM MentorEntry q
                  WHERE q.workspaceId = e.workspaceId AND q.turnId = e.turnId AND q.seq < :seq
                    AND q.kind = de.tum.cit.aet.hephaestus.mentor.MentorEntry.Kind.USER
                    AND q.surface = de.tum.cit.aet.hephaestus.mentor.MentorEntry.Surface.SLACK
                    AND q.slackDelivery = de.tum.cit.aet.hephaestus.mentor.MentorEntry.SlackDelivery.DELIVERED
              )
            """)
    boolean answeredInSlackBefore(
            @Param("workspaceId") Long workspaceId,
            @Param("conversationId") UUID conversationId,
            @Param("seq") long seq);

    @Modifying
    @Query("UPDATE MentorEntry e SET e.slackTs = :slackTs WHERE e.id = :id AND e.workspaceId = :workspaceId")
    int setSlackTs(@Param("workspaceId") Long workspaceId, @Param("id") UUID id, @Param("slackTs") String slackTs);

    @Modifying
    @Query("UPDATE MentorEntry e SET e.slackDelivery = :outcome WHERE e.id = :id AND e.workspaceId = :workspaceId")
    int setSlackDelivery(
            @Param("workspaceId") Long workspaceId,
            @Param("id") UUID id,
            @Param("outcome") MentorEntry.SlackDelivery outcome);

    @Modifying
    @Query("UPDATE MentorEntry e SET e.slackStreamTs = :streamTs WHERE e.id = :id AND e.workspaceId = :workspaceId")
    int setSlackStreamTs(
            @Param("workspaceId") Long workspaceId, @Param("id") UUID id, @Param("streamTs") @Nullable String streamTs);
}
