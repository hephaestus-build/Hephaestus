package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** The inbox items of threads. A write takes the thread's lock first, as every turn of the thread does. */
interface InboxItemRepository extends Repository<InboxItem, UUID> {

    InboxItem save(InboxItem item);

    /** Whether the thread already holds an item of this source: its producer writes it once. */
    boolean existsByConversationIdAndSourceKey(UUID conversationId, String sourceKey);

    /** An open item that is due, as the start pass reads it, without a lock. */
    record Due(UUID id, long workspaceId, UUID conversationId) {}

    /** The open items of a kind that are due, oldest first: the start pass's page. */
    @WorkspaceAgnostic("The start pass decides the due items of every workspace, each under its thread's lock")
    @Query(value = """
            SELECT i.id AS "id", i.workspace_id AS "workspaceId", i.conversation_id AS "conversationId"
            FROM inbox_item i
            WHERE i.kind = :kind AND i.state IN ('PENDING', 'CLAIMED') AND i.due_at <= :now
            ORDER BY i.due_at, i.id
            LIMIT :limit
            """, nativeQuery = true)
    List<Due> findDue(@Param("kind") String kind, @Param("now") Instant now, @Param("limit") int limit);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM InboxItem i WHERE i.id = :id AND i.workspaceId = :workspaceId")
    Optional<InboxItem> lockByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") long workspaceId);
}
