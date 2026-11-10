package de.tum.cit.aet.hephaestus.productfeedback;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("The instance's one product feedback destination setting")
interface ProductFeedbackDestinationRepository extends JpaRepository<ProductFeedbackDestination, Short> {

    /** An admin change reads the row it rewrites under a lock, so two changes at once both count. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM ProductFeedbackDestination d WHERE d.id = :id")
    Optional<ProductFeedbackDestination> findForUpdate(@Param("id") short id);

    /**
     * Writes only the snapshot, and only while {@code publicRepository} is still the public destination: a refresh
     * must not write back an admin's concurrent change to the other columns.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE ProductFeedbackDestination d SET d.snapshot = :snapshot, d.snapshotAt = :snapshotAt
             WHERE d.id = :id AND LOWER(d.publicRepository) = LOWER(:publicRepository)
            """)
    int writeSnapshot(
            @Param("id") short id,
            @Param("publicRepository") String publicRepository,
            @Param("snapshot") @Nullable String snapshot,
            @Param("snapshotAt") @Nullable Instant snapshotAt);
}
