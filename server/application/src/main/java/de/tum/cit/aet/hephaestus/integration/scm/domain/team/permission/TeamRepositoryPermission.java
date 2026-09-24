package de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission;

import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * Represents a team's permission level on a repository.
 * <p>
 * Uses a composite key (teamId, repositoryId) via {@link EmbeddedId}.
 * <p>
 * IMPORTANT: This entity implements {@link Persistable} to correctly handle
 * the new vs. existing entity detection for JPA's save operation. Without this,
 * JPA sees the pre-set composite ID and assumes the entity exists, triggering
 * a merge operation that fails with EntityNotFoundException.
 */
@Entity
@Table(name = "team_repository_permission")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class TeamRepositoryPermission implements Persistable<TeamRepositoryPermission.Id> {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private Id id = new Id();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("teamId")
    @Nullable
    private Team team;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("repositoryId")
    private Repository repository;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PermissionLevel permission;

    /**
     * Tracks whether this entity is new (not yet persisted).
     * Used by {@link #isNew()} to help JPA decide between persist vs merge.
     */
    @Transient
    private boolean isNew = true;

    public TeamRepositoryPermission(Team team, Repository repository, PermissionLevel permission) {
        this.team = team;
        this.repository = repository;
        this.permission = permission;
        this.id.setTeamId(team.getId());
        this.id.setRepositoryId(repository.getId());
        this.isNew = true;
    }

    /**
     * Returns whether this entity is new (not yet persisted).
     * <p>
     * This is CRITICAL for entities with assigned/composite IDs. Without this,
     * Spring Data JPA's save() method calls merge() for entities with non-null IDs,
     * which fails with EntityNotFoundException if the entity doesn't exist yet.
     * <p>
     * The isNew flag is set to true on construction and reset to false after
     * the entity is loaded from the database (via @PostLoad or by JPA).
     *
     * @return true if this is a new entity that needs to be persisted
     */
    @Override
    public boolean isNew() {
        return isNew;
    }

    /**
     * Marks this entity as persisted (not new).
     * Called after the entity is loaded from the database.
     */
    @jakarta.persistence.PostLoad
    @jakarta.persistence.PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public enum PermissionLevel {
        READ,
        TRIAGE,
        WRITE,
        MAINTAIN,
        ADMIN,
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Nullable
        private Long teamId;

        @Nullable
        private Long repositoryId;
    }
}
