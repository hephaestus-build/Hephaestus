package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ConsumerDeadLetterRepository extends JpaRepository<ConsumerDeadLetter, Long> {
    @Modifying
    @Query(value = """
        INSERT INTO consumer_dead_letter
          (workspace_id, consumer_name, stream_name, stream_sequence, subject, received_at, error_class, failed_at)
        VALUES (:#{#entry.workspaceId()}, :#{#entry.consumer()}, :#{#entry.stream()}, :#{#entry.sequence()},
                :#{#entry.subject()}, :#{#entry.receivedAt()}, :#{#entry.error()}, :#{#entry.at()})
        ON CONFLICT (consumer_name, stream_name, stream_sequence) DO NOTHING
        """, nativeQuery = true)
    void capture(Capture entry);

    record Capture(
            @Nullable Long workspaceId,
            String consumer,
            String stream,
            long sequence,
            String subject,
            Instant receivedAt,
            String error,
            Instant at) {}

    @Query("SELECT d FROM ConsumerDeadLetter d WHERE d.workspace.id = :workspaceId AND d.id > :after ORDER BY d.id")
    List<ConsumerDeadLetter> listWorkspace(Long workspaceId, long after, Pageable page);

    @WorkspaceAgnostic("Selects only unassigned installation events with workspace IS NULL")
    @Query("SELECT d FROM ConsumerDeadLetter d WHERE d.workspace IS NULL AND d.id > :after ORDER BY d.id")
    List<ConsumerDeadLetter> listInstallation(long after, Pageable page);

    @Query("SELECT d FROM ConsumerDeadLetter d WHERE d.workspace.id = :workspaceId AND d.id = :id")
    Optional<ConsumerDeadLetter> findWorkspace(Long workspaceId, Long id);

    @WorkspaceAgnostic("Selects only unassigned installation events with workspace IS NULL")
    @Query("SELECT d FROM ConsumerDeadLetter d WHERE d.workspace IS NULL AND d.id = :id")
    Optional<ConsumerDeadLetter> findInstallation(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM ConsumerDeadLetter d WHERE d.workspace.id = :workspaceId AND d.id = :id")
    Optional<ConsumerDeadLetter> lockWorkspace(Long workspaceId, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @WorkspaceAgnostic("Selects only unassigned installation events with workspace IS NULL")
    @Query("SELECT d FROM ConsumerDeadLetter d WHERE d.workspace IS NULL AND d.id = :id")
    Optional<ConsumerDeadLetter> lockInstallation(Long id);
}
