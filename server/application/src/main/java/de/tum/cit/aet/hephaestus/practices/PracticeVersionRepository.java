package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.practices.model.PracticeVersion;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

@WorkspaceAgnostic("Versions are read through a tenant-validated owning practice")
public interface PracticeVersionRepository extends JpaRepository<PracticeVersion, Long> {
    Optional<PracticeVersion> findFirstByPracticeIdOrderByNumberDesc(Long practiceId);

    Optional<PracticeVersion> findByIdAndWorkspaceId(Long id, Long workspaceId);

    List<PracticeVersion> findByWorkspaceIdAndIdIn(long workspaceId, Collection<Long> ids);

    @Query("""
        SELECT v FROM PracticeVersion v
        WHERE v.workspaceId = :workspaceId AND v.practice.id IN :practiceIds AND v.createdAt <= :at
        AND NOT EXISTS (SELECT newer.id FROM PracticeVersion newer
            WHERE newer.workspaceId = :workspaceId AND newer.practice.id = v.practice.id
            AND newer.createdAt <= :at AND newer.number > v.number)
        """)
    List<PracticeVersion> findAt(long workspaceId, Collection<Long> practiceIds, Instant at);

    List<PracticeVersion> findByWorkspaceIdAndPracticeIdOrderByNumberAsc(Long workspaceId, Long practiceId);

    List<PracticeVersion> findByWorkspaceIdAndPracticeIdInOrderByNumberAsc(
            long workspaceId, Collection<Long> practiceIds);

    Optional<PracticeVersion> findFirstByPracticeIdAndNumberLessThanOrderByNumberDesc(Long practiceId, int number);

    @Modifying
    @Query(value = "DELETE FROM practice_version WHERE workspace_id = :workspaceId", nativeQuery = true)
    void deleteAllByWorkspaceId(Long workspaceId);
}
