package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface FeedbackThreadStateRepository extends Repository<Feedback, UUID> {
    @Modifying(flushAutomatically = true)
    @Query(value = """
        UPDATE feedback f SET composition=jsonb_set(f.composition,'{humanControlled}','true'::jsonb)
        WHERE f.workspace_id=:workspaceId AND EXISTS(SELECT 1 FROM feedback_copy c
          WHERE c.workspace_id=f.workspace_id AND c.feedback_id=f.id AND f.item_id=:itemId AND c.thread_ref=:threadRef)
        """, nativeQuery = true)
    int protect(
            @Param("workspaceId") long workspaceId, @Param("itemId") long itemId, @Param("threadRef") String threadRef);
}
