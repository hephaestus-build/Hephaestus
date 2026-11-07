package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.Commit;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitContributor;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
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
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;

/**
 * One email on one repository whose commit links wait for the provider's answer.
 *
 * <p>The row points at a representative commit and holds no email, so it adds no copy of personal data: erasure of the
 * commit's email leaves the row with no question to ask.
 */
@Entity
@Table(
        name = "commit_identity_resolution",
        indexes = {
            @Index(name = "idx_commit_identity_resolution_queue", columnList = "state, attempts, id"),
            @Index(name = "idx_commit_identity_resolution_repository", columnList = "repository_id"),
            @Index(name = "idx_commit_identity_resolution_commit", columnList = "commit_id"),
            @Index(name = "idx_commit_identity_resolution_contributor", columnList = "contributor_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class CommitIdentityResolution {

    public enum State {
        PENDING,
        RESOLVED,
        NO_ACCOUNT,
        UNANSWERED
    }

    /** Where the representative commit holds the email. */
    public enum Source {
        AUTHOR,
        COMMITTER,
        CONTRIBUTOR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Repository repository;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "commit_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Commit commit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contributor_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private @Nullable CommitContributor contributor;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state = State.PENDING;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "finished_at")
    private @Nullable Instant finishedAt;
}
