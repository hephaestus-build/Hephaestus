package de.tum.cit.aet.hephaestus.integration.scm.domain.repository.collaborator;

import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
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
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "repository_collaborator")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class RepositoryCollaborator {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private Id id = new Id();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("repositoryId")
    @ToString.Exclude
    private Repository repository;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @ToString.Exclude
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission", length = 32, nullable = false)
    private Permission permission = Permission.UNKNOWN;

    public RepositoryCollaborator(Repository repository, User user, Permission permission) {
        this.repository = repository;
        this.user = user;
        this.permission = permission == null ? Permission.UNKNOWN : permission;
        this.id.setRepositoryId(repository.getId());
        this.id.setUserId(user.getId());
    }

    public void updatePermission(Permission permission) {
        if (permission != null && permission != Permission.UNKNOWN) {
            this.permission = permission;
        }
    }

    public enum Permission {
        READ,
        TRIAGE,
        WRITE,
        MAINTAIN,
        ADMIN,
        UNKNOWN;

        public static Permission fromGitHubValue(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return UNKNOWN;
            }
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "read", "pull" -> READ;
                case "triage" -> TRIAGE;
                case "write", "push" -> WRITE;
                case "maintain" -> MAINTAIN;
                case "admin" -> ADMIN;
                default -> UNKNOWN;
            };
        }
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

        @Column(name = "repository_id", nullable = false)
        private Long repositoryId;

        @Column(name = "user_id", nullable = false)
        private Long userId;
    }
}
