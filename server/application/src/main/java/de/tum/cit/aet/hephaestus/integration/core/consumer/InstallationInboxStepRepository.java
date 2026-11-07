package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional(propagation = Propagation.MANDATORY)
public interface InstallationInboxStepRepository extends Repository<InstallationInboxStep, Long> {
    @WorkspaceAgnostic("Installation delivery steps have no workspace until their handler creates one")
    @Modifying
    @Query(value = """
            INSERT INTO installation_inbox_step
              (consumer_name, stream_name, stream_sequence, received_at, step_name, processed_at)
            VALUES (:#{#delivery.consumer()}, :#{#delivery.stream()}, :#{#delivery.sequence()},
                    :#{#delivery.receivedAt()}, :step, :processedAt)
            ON CONFLICT (consumer_name, stream_name, stream_sequence, received_at, step_name) DO NOTHING
            """, nativeQuery = true)
    int claim(BrokerDelivery delivery, String step, Instant processedAt);

    @WorkspaceAgnostic("Installation completion selects only instance-owned delivery steps")
    @Query(value = """
            SELECT EXISTS(SELECT 1 FROM installation_inbox_step
              WHERE consumer_name = :#{#delivery.consumer()} AND stream_name = :#{#delivery.stream()}
                AND stream_sequence = :#{#delivery.sequence()} AND received_at = :#{#delivery.receivedAt()}
                AND step_name = :step)
            """, nativeQuery = true)
    boolean contains(BrokerDelivery delivery, String step);

    @WorkspaceAgnostic("Retention prunes expired instance-owned installation delivery steps")
    @Modifying
    @Query("DELETE FROM InstallationInboxStep s WHERE s.processedAt < :before")
    int deleteExpired(Instant before);
}
