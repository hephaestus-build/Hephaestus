package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Entity representing a contributor to a Git commit.
 * <p>
 * Captures all authors (primary + co-authors from Co-authored-by trailers)
 * and the committer of a commit. This is essential for process mining of
 * squash-merge commits where multiple developers' work is combined.
 * <p>
 * GitHub's GraphQL {@code Commit.authors} field automatically parses
 * Co-authored-by trailers into structured {@code GitActor} objects,
 * which this entity stores.
 */
@Entity
@Table(
        name = "commit_contributor",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_commit_contributor_commit_email_role",
                        columnNames = {"commit_id", "email", "role"}),
        indexes = {
            @Index(name = "idx_commit_contributor_user_id", columnList = "user_id"),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CommitContributor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * The role of this contributor in the commit.
     */
    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private Role role;

    /**
     * The name of the contributor (from git metadata).
     */
    @Column(length = 255)
    private String name;

    /**
     * The email of the contributor (from git metadata).
     * Used as part of the unique constraint to deduplicate contributors.
     */
    @NonNull
    @Column(length = 255, nullable = false)
    private String email;

    /**
     * The ordinal position of this contributor within the commit's author list.
     * 0 = primary author, 1+ = co-authors (from Co-authored-by trailers).
     * For COMMITTER role, ordinal is always 0.
     */
    private int ordinal;

    // Relationships

    /**
     * The commit this contributor belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commit_id", nullable = false)
    @ToString.Exclude
    @Nullable
    private Commit commit;

    /**
     * The resolved user, if the contributor could be matched to a GitHub user.
     * May be null if the email cannot be resolved.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @ToString.Exclude
    private User user;

    // Enums

    /**
     * The role a contributor plays in a commit.
     */
    public enum Role {
        /** The primary author of the commit. */
        AUTHOR,
        /** A co-author (from Co-authored-by trailers in squash merges). */
        CO_AUTHOR,
        /** The committer (person who applied the commit). */
        COMMITTER,
    }
}
