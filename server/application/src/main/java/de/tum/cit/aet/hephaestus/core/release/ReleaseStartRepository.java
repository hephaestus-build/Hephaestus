package de.tum.cit.aet.hephaestus.core.release;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

@WorkspaceAgnostic("Release starts are instance-wide; they name no tenant")
interface ReleaseStartRepository extends JpaRepository<ReleaseStart, Long> {
    /** Held until the transaction ends, so servers that start together record one release once. */
    @Query(
            value = "SELECT pg_advisory_xact_lock(hashtext('hephaestus'), hashtext('release-start'))",
            nativeQuery = true)
    void lockHistory();

    Optional<ReleaseStart> findFirstByOrderByIdDesc();

    List<ReleaseStart> findTop10ByOrderByIdDesc();
}
