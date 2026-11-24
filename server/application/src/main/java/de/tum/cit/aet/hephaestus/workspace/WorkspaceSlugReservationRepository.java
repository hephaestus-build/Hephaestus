package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Reservations have no workspace data and survive workspace deletion. */
@WorkspaceAgnostic("Instance-wide DNS namespace reservations")
public interface WorkspaceSlugReservationRepository extends Repository<WorkspaceSlugReservation, String> {
    boolean existsById(String slug);

    /** Removes a reservation's plaintext; the caller reserves its {@link WorkspaceNamespaceReservation} token. */
    @Modifying
    @Query(value = "DELETE FROM workspace_slug_reservation WHERE slug = :slug", nativeQuery = true)
    int release(@Param("slug") String slug);

    @Modifying
    @Query(
            value = "INSERT INTO workspace_slug_reservation (slug) VALUES (:slug) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int reserve(@Param("slug") String slug);
}
