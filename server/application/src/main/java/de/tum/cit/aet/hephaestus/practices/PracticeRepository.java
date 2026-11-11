package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

@WorkspaceAgnostic("Reads carry workspaceId; primary-key locks and writes run after tenant validation")
public interface PracticeRepository extends JpaRepository<Practice, Long> {
    @EntityGraph(attributePaths = {"group", "currentVersion"})
    List<Practice> findByWorkspaceId(Long workspaceId);

    @EntityGraph(attributePaths = {"group", "currentVersion"})
    Optional<Practice> findByIdAndWorkspaceId(Long id, Long workspaceId);

    @EntityGraph(attributePaths = {"group", "currentVersion"})
    List<Practice> findByWorkspaceIdAndIdIn(Long workspaceId, Collection<Long> ids);

    @EntityGraph(attributePaths = {"group", "currentVersion"})
    List<Practice> findByWorkspaceIdAndKind(Long workspaceId, ItemKind kind);

    @EntityGraph(attributePaths = {"group", "currentVersion"})
    Optional<Practice> findByWorkspaceIdAndSlug(Long workspaceId, String slug);

    @EntityGraph(attributePaths = {"group", "currentVersion"})
    List<Practice> findByWorkspaceIdAndGroupIdOrderByPositionAsc(Long workspaceId, Long groupId);

    @EntityGraph(attributePaths = {"group", "currentVersion"})
    @Query("SELECT p FROM Practice p LEFT JOIN p.group g WHERE p.workspace.id = :workspaceId ORDER"
            + " BY g.position ASC NULLS LAST, p.position ASC, p.slug ASC")
    List<Practice> findAllForCatalog(Long workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Practice p WHERE p.id = :id")
    Optional<Practice> findByIdForUpdate(Long id);

    @Query("SELECT COALESCE(MAX(p.position), -1) FROM Practice p WHERE p.workspace.id ="
            + " :workspaceId AND ((:groupId IS NULL AND p.group IS NULL) OR p.group.id ="
            + " :groupId)")
    int findMaxPosition(Long workspaceId, @Nullable Long groupId);

    boolean existsByWorkspaceIdAndSlug(Long workspaceId, String slug);

    @Modifying
    @Query("DELETE FROM Practice p WHERE p.workspace.id = :workspaceId")
    void deleteAllByWorkspaceId(Long workspaceId);
}
