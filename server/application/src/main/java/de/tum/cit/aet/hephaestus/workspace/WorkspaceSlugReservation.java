package de.tum.cit.aet.hephaestus.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Instance-wide DNS namespace tombstones. Database triggers reserve slugs, including writes outside the service. */
@Entity
@Table(name = "workspace_slug_reservation")
@Getter
@NoArgsConstructor
public class WorkspaceSlugReservation {
    @Id
    @Column(length = 64, nullable = false)
    private String slug;
}
