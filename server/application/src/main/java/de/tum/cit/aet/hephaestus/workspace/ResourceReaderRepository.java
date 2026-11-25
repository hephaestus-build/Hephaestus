package de.tum.cit.aet.hephaestus.workspace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Writes the readers of a monitored repository. Every statement binds the monitor's workspace. */
@Repository
public interface ResourceReaderRepository extends JpaRepository<ResourceReader, Long> {

    @Modifying
    @Query(
            value = "DELETE FROM resource_reader WHERE workspace_id = :workspaceId"
                    + " AND repository_to_monitor_id = :monitorId",
            nativeQuery = true)
    int deleteReaders(@Param("workspaceId") long workspaceId, @Param("monitorId") long monitorId);

    /** Deletes the monitor's readers that a complete listing no longer names, keeping the rows it names again. */
    @Modifying
    @Query(
            value = "DELETE FROM resource_reader WHERE workspace_id = :workspaceId"
                    + " AND repository_to_monitor_id = :monitorId"
                    + " AND NOT (provider_id = :providerId AND reader_native_id = ANY(CAST(:readers AS bigint[])))",
            nativeQuery = true)
    int deleteReadersNotIn(
            @Param("workspaceId") long workspaceId,
            @Param("monitorId") long monitorId,
            @Param("providerId") long providerId,
            @Param("readers") Long[] readers);

    @Modifying
    @Query(
            value =
                    "INSERT INTO resource_reader (workspace_id, repository_to_monitor_id, provider_id, reader_native_id)"
                            + " SELECT :workspaceId, :monitorId, :providerId, reader"
                            + " FROM unnest(CAST(:readers AS bigint[])) AS reader"
                            + " ON CONFLICT ON CONSTRAINT uk_resource_reader_monitor_reader DO NOTHING",
            nativeQuery = true)
    int insertReaders(
            @Param("workspaceId") long workspaceId,
            @Param("monitorId") long monitorId,
            @Param("providerId") long providerId,
            @Param("readers") Long[] readers);

    @Modifying
    @Query(
            value = "DELETE FROM resource_reader WHERE workspace_id = :workspaceId"
                    + " AND repository_to_monitor_id = :monitorId AND provider_id = :providerId"
                    + " AND reader_native_id = :readerNativeId",
            nativeQuery = true)
    int deleteReader(
            @Param("workspaceId") long workspaceId,
            @Param("monitorId") long monitorId,
            @Param("providerId") long providerId,
            @Param("readerNativeId") long readerNativeId);
}
