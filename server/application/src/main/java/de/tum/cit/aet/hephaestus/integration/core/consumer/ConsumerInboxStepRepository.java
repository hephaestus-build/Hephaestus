package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.MANDATORY)
public interface ConsumerInboxStepRepository extends Repository<ConsumerInboxStep, Long> {
    @Modifying
    @Query(value = """
            INSERT INTO consumer_inbox_step
              (workspace_id, consumer_name, stream_name, stream_sequence, received_at, step_name, processed_at)
            VALUES (:workspaceId, :#{#delivery.consumer()}, :#{#delivery.stream()}, :#{#delivery.sequence()},
                    :#{#delivery.receivedAt()}, :step, :processedAt)
            ON CONFLICT (workspace_id, consumer_name, stream_name, stream_sequence, received_at, step_name) DO NOTHING
            """, nativeQuery = true)
    int claim(Long workspaceId, BrokerDelivery delivery, String step, Instant processedAt);

    @Query(value = """
            SELECT EXISTS(SELECT 1 FROM consumer_inbox_step
              WHERE workspace_id = :workspaceId AND consumer_name = :#{#delivery.consumer()}
                AND stream_name = :#{#delivery.stream()} AND stream_sequence = :#{#delivery.sequence()}
                AND received_at = :#{#delivery.receivedAt()} AND step_name = :step)
            """, nativeQuery = true)
    boolean contains(Long workspaceId, BrokerDelivery delivery, String step);

    @Modifying
    @Query("DELETE FROM ConsumerInboxStep s WHERE s.workspace.id = :workspaceId")
    int deleteAllByWorkspaceId(Long workspaceId);

    @WorkspaceAgnostic("Retention prunes the expired inbox steps of every workspace")
    @Modifying
    @Query("DELETE FROM ConsumerInboxStep s WHERE s.processedAt < :before")
    int deleteExpired(Instant before);
}
