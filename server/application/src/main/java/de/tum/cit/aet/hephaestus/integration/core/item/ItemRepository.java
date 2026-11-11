package de.tum.cit.aet.hephaestus.integration.core.item;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ItemRepository extends JpaRepository<Item, Long> {
    Optional<Item> findByIdAndWorkspaceId(long itemId, long workspaceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Item i WHERE i.workspaceId = :workspaceId AND i.id = :itemId")
    Optional<Item> lockByIdAndWorkspaceId(@Param("itemId") long itemId, @Param("workspaceId") long workspaceId);

    List<Item> findByWorkspaceIdAndIdIn(long workspaceId, List<Long> ids);

    @Query(
            value = "SELECT * FROM item WHERE workspace_id = :workspaceId AND kind = :kind AND source_id = :sourceId",
            nativeQuery = true)
    Optional<Item> findSource(
            @Param("workspaceId") long workspaceId, @Param("kind") String kind, @Param("sourceId") long sourceId);

    Optional<Item> findByWorkspaceIdAndParentIdAndPath(long workspaceId, long parentId, String path);

    @Modifying
    @Query(
            value =
                    "INSERT INTO item(workspace_id, kind, source_id, parent_id, path, created_at) VALUES (:workspaceId, :kind, :sourceId, :parentId, :path, :at) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(
            @Param("workspaceId") long workspaceId,
            @Param("kind") String kind,
            @Param("sourceId") @Nullable Long sourceId,
            @Param("parentId") @Nullable Long parentId,
            @Param("path") @Nullable String path,
            @Param("at") Instant at);

    /** The active children of one item, for a walk that removes a whole tree. */
    List<Item> findByWorkspaceIdAndParentIdAndRemovedAtIsNull(long workspaceId, long parentId);

    @Modifying
    @Query(
            "UPDATE Item i SET i.removedAt = :at WHERE i.workspaceId = :workspaceId AND i.id = :itemId AND i.removedAt IS NULL")
    int remove(@Param("workspaceId") long workspaceId, @Param("itemId") long itemId, @Param("at") Instant at);

    @Modifying
    @Query(
            "UPDATE Item i SET i.removedAt = NULL WHERE i.workspaceId = :workspaceId AND i.id = :itemId AND i.removedAt IS NOT NULL")
    int restore(@Param("workspaceId") long workspaceId, @Param("itemId") long itemId);
}
