package de.tum.cit.aet.hephaestus.core.provenance;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("Provenance signing keys belong to the instance, not to a workspace")
interface ProvenanceSigningKeyRepository extends JpaRepository<ProvenanceSigningKey, String> {

    /** The key that signs: the newest, with the kid as a tiebreaker so every replica picks the same one. */
    Optional<ProvenanceSigningKey> findFirstByOrderByCreatedAtDescKidDesc();

    /**
     * Serializes the first key's creation across replicas until the transaction ends. Class 50 is this namespace in
     * the two-integer advisory key space; the JWT bootstrap uses class 17.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(50, 1)", nativeQuery = true)
    void acquireBootstrapLock();

    /** Every row, locked, for the system key rotation. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT k FROM ProvenanceSigningKey k ORDER BY k.kid")
    List<ProvenanceSigningKey> lockAllForKeyRotation();
}
