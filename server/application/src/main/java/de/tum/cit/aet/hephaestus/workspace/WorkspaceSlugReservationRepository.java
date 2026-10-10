package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import org.springframework.data.repository.Repository;

/** Reservations have no workspace data and survive workspace deletion. */
@WorkspaceAgnostic("Instance-wide DNS namespace reservations")
public interface WorkspaceSlugReservationRepository extends Repository<WorkspaceSlugReservation, String> {
    boolean existsById(String slug);
}
