package de.tum.cit.aet.hephaestus.practices.repository;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PracticeRepositorySettingRepository extends JpaRepository<PracticeRepositorySetting, Long> {
    /**
     * Stores the report of a sync. An update through the entity would raise the setting's revision and update time,
     * and a move to the repository that read its folder before the sync would then be refused as if the setting had
     * changed.
     */
    @Modifying
    @Query(value = """
                    UPDATE workspace_practice_repository
                    SET sync_commit = :commit, synced_at = :syncedAt, sync_report = CAST(:report AS jsonb)
                    WHERE workspace_id = :workspaceId
                    """, nativeQuery = true)
    int recordSync(
            @Param("workspaceId") long workspaceId,
            @Param("commit") String commit,
            @Param("syncedAt") Instant syncedAt,
            @Param("report") String report);
}
