package de.tum.cit.aet.hephaestus.core.security;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
@WorkspaceAgnostic("System secrets belong to the instance, not to a workspace")
public interface StoredSystemSecretRepository extends JpaRepository<StoredSystemSecret, String> {

    /** Stores {@code sealedValue} under {@code name} unless a value exists, so concurrent servers keep one value. */
    @Modifying
    @Query(value = """
        INSERT INTO system_secret (name, sealed_value, created_at) VALUES (:name, :sealedValue, now())
        ON CONFLICT (name) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(String name, byte[] sealedValue);

    /** Every row, locked, for the system key rotation ({@code SystemKeyRotation}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT x FROM StoredSystemSecret x ORDER BY x.name")
    List<StoredSystemSecret> lockAllForKeyRotation();
}
