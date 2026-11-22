package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ScmFeedbackThreadStateRepository extends Repository<PullRequestReviewThread, Long> {
    interface ThreadRow {
        long getWorkspaceId();

        long getItemId();

        String getThreadRef();

        /** The provider id of the account that resolved the thread, as the thread records it. */
        @Nullable
        Long getResolvedBy();

        /** The provider id of the account that sent the event, when the event names one. */
        @Nullable
        Long getSender();
    }

    /**
     * The workspaces that index the thread's work. {@code actorUserId} is the local user who sent the event; the row
     * names that user's provider id, or the thread's resolver.
     */
    @WorkspaceAgnostic("A shared provider thread event discovers indexed workspaces; it returns identifiers only")
    @Query(value = """
        SELECT i.workspace_id AS workspaceId,i.id AS itemId,t.node_id AS threadRef,r.native_id AS resolvedBy,
          (SELECT s.native_id FROM "user" s WHERE s.id=CAST(:actorUserId AS BIGINT)) AS sender FROM item i
        JOIN pull_request_review_thread t ON t.pull_request_id=i.source_id AND t.id=:threadId
        LEFT JOIN "user" r ON r.id=t.resolved_by_id
        WHERE i.kind='scm.pull_request' AND t.node_id IS NOT NULL
        """, nativeQuery = true)
    List<ThreadRow> find(@Param("threadId") long threadId, @Param("actorUserId") @Nullable Long actorUserId);
}
