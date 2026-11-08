package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ScmFeedbackThreadStateRepository extends Repository<PullRequestReviewThread, Long> {
    interface ThreadRow {
        long getWorkspaceId();

        long getItemId();

        String getThreadRef();
    }

    @WorkspaceAgnostic("A shared provider thread event discovers indexed workspaces; it returns identifiers only")
    @Query(value = """
        SELECT i.workspace_id AS workspaceId,i.id AS itemId,t.node_id AS threadRef FROM item i
        JOIN pull_request_review_thread t ON t.pull_request_id=i.source_id AND t.id=:threadId
        WHERE i.kind='scm.pull_request' AND t.node_id IS NOT NULL
        """, nativeQuery = true)
    List<ThreadRow> find(@Param("threadId") long threadId);
}
