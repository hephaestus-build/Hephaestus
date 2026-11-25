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

    @Modifying
    @Query(
            value = "INSERT INTO resource_reader (workspace_id, repository_to_monitor_id, reader_native_id)"
                    + " SELECT :workspaceId, :monitorId, reader FROM unnest(CAST(:readers AS bigint[])) AS reader",
            nativeQuery = true)
    int insertReaders(
            @Param("workspaceId") long workspaceId,
            @Param("monitorId") long monitorId,
            @Param("readers") Long[] readers);
}
