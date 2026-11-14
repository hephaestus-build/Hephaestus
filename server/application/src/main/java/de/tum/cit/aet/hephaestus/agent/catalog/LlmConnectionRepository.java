package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@WorkspaceAgnostic("Instance LLM connection catalog is global (app_admin-owned), not tenant-scoped.")
public interface LlmConnectionRepository extends JpaRepository<LlmConnection, Long> {
    Optional<LlmConnection> findBySlug(String slug);

    /**
     * An update writes every column back, so it locks the row: a system key rotation that resealed the key meanwhile
     * would otherwise get the value under the earlier key back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM LlmConnection c WHERE c.id = :id")
    Optional<LlmConnection> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT new de.tum.cit.aet.hephaestus.agent.catalog.SealedProbeTarget("
            + "c.baseUrl, c.authMode, c.sealedApiKey, c.apiProtocol, c.slug) "
            + "FROM LlmConnection c WHERE c.id = :id")
    Optional<SealedProbeTarget> findProbeTargetById(@Param("id") Long id);

    /** Every row, locked, for the system key rotation ({@code SystemKeyRotation}). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT x FROM LlmConnection x ORDER BY x.id")
    List<LlmConnection> lockAllForKeyRotation();
}
