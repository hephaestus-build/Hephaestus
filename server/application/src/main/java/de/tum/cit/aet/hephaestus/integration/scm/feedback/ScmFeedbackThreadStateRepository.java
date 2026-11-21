package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import java.util.List;
import java.util.Optional;
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
    }

    @WorkspaceAgnostic("A shared provider thread event discovers indexed workspaces; it returns identifiers only")
    @Query(value = """
        SELECT i.workspace_id AS workspaceId,i.id AS itemId,t.node_id AS threadRef,u.native_id AS resolvedBy FROM item i
        JOIN pull_request_review_thread t ON t.pull_request_id=i.source_id AND t.id=:threadId
        LEFT JOIN "user" u ON u.id=t.resolved_by_id
        WHERE i.kind='scm.pull_request' AND t.node_id IS NOT NULL
        """, nativeQuery = true)
    List<ThreadRow> find(@Param("threadId") long threadId);

    /** The provider id of a local user: the sender of a provider event. */
    @WorkspaceAgnostic("A provider account is shared by every workspace that indexes its provider")
    @Query(value = "SELECT native_id FROM \"user\" WHERE id=:userId", nativeQuery = true)
    Optional<Long> nativeId(@Param("userId") long userId);
}
