package de.tum.cit.aet.hephaestus.integration.scm.domain.label;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for label entities.
 *
 * <p>Labels are shared mirror rows of a repository. Reads key on the repository or on the label's
 * provider identity.
 */
@Repository
@WorkspaceAgnostic("SCM mirror rows are shared across workspaces; callers validate the provider or monitored scope")
public interface LabelRepository extends JpaRepository<Label, Long> {
    /** Every workspace's mirror rows. Only tests call it, to check what sync stored; production code reads by scope. */
    @Override
    @WorkspaceAgnostic("Reads every workspace's mirror rows; only tests call it, to check what sync stored")
    long count();

    @Query("""
        SELECT l
        FROM Label l
        WHERE l.repository.id = :repositoryId
        AND l.name = :name
        """)
    Optional<Label> findByRepositoryIdAndName(@Param("repositoryId") Long repositoryId, @Param("name") String name);

    Optional<Label> findByNativeIdAndProviderId(Long nativeId, Long providerId);

    List<Label> findAllByRepository_Id(Long repositoryId);

    /**
     * Atomically inserts a label if absent (race-condition safe).
     * <p>
     * Uses PostgreSQL's ON CONFLICT DO NOTHING to handle concurrent inserts
     * on the unique constraint (repository_id, name). This eliminates the race
     * condition where two threads both check for existence and try to insert.
     *
     * @return 1 if inserted, 0 if duplicate (label with same repo+name exists)
     */
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO label (native_id, provider_id, name, color, repository_id)
        VALUES (:nativeId, :providerId, :name, :color, :repositoryId)
        ON CONFLICT (repository_id, name) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(
            @Param("nativeId") Long nativeId,
            @Param("providerId") Long providerId,
            @Param("name") String name,
            @Param("color") @Nullable String color,
            @Param("repositoryId") Long repositoryId);
}
