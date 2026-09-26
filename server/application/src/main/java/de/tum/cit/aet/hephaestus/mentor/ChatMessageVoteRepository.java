package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vote storage keyed by {@code message_id}. There is no workspace column on the vote table —
 * tenant isolation is enforced one layer up, where the caller's ownership of the thread in its
 * workspace is verified BEFORE any repository call: by {@link ChatMessageVoteService}'s callers
 * for writes, and by {@link ChatThreadService} for the thread read. The annotation
 * here documents the intentional design choice and exempts the repo from the
 * {@code MultiTenancyArchitectureTest} workspace-scoping rules.
 */
@Repository
@WorkspaceAgnostic("Vote table has no workspace column; thread ownership is enforced upstream")
public interface ChatMessageVoteRepository extends JpaRepository<ChatMessageVote, UUID> {
    /** Only ever called for a thread the caller has already been proven to own. */
    List<ChatMessageVote> findByMessage_Thread_Id(UUID threadId);

    /**
     * Atomic upsert via Postgres {@code INSERT ... ON CONFLICT DO UPDATE}. Replaces the prior
     * read-modify-write pattern which 500'd on concurrent POSTs to the same {@code message_id}
     * (two callers both observed {@code Optional.empty()}, both attempted {@code INSERT}, the
     * second hit a primary-key violation that surfaced as a generic 500). The DB-level
     * coordination here is the only correct pattern — even Spring {@code @Transactional} at
     * READ_COMMITTED can't help because the conflicting row isn't visible until the other
     * transaction commits.
     *
     * <p>{@code created_at} / {@code updated_at} are managed by Hibernate's
     * {@code @CreationTimestamp} / {@code @UpdateTimestamp} on a managed entity, but those don't
     * fire inside a native {@code @Modifying} query — we emit the timestamps directly via
     * {@code now()} so the row passes the {@code @NotNull} schema constraint.
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO chat_message_vote (message_id, is_upvoted, created_at, updated_at)
        VALUES (:messageId, :isUpvoted, now(), now())
        ON CONFLICT (message_id) DO UPDATE
          SET is_upvoted = EXCLUDED.is_upvoted,
              updated_at = now()
        """, nativeQuery = true)
    int upsert(@Param("messageId") UUID messageId, @Param("isUpvoted") boolean isUpvoted);
}
