package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Entity representing a Git commit.
 * <p>
 * Uses an auto-generated Long ID as primary key. The SHA is stored as a regular
 * column with a unique constraint on (sha, repository_id) since the same commit
 * SHA can appear in multiple repositories (e.g., forks).
 * <p>
 * This is provider-agnostic and does not extend BaseGitServiceEntity since
 * commits don't have provider-specific database IDs.
 */
@Entity
@Table(
        name = "git_commit",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_git_commit_sha_repository",
                        columnNames = {"sha", "repository_id"}),
        indexes = {
            @Index(name = "idx_git_commit_repository_id", columnList = "repository_id"),
            @Index(name = "idx_git_commit_author_id", columnList = "author_id"),
            @Index(name = "idx_git_commit_committer_id", columnList = "committer_id"),
            @Index(name = "idx_git_commit_authored_at", columnList = "authored_at"),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString
public class Commit {
    public static final int MESSAGE_LENGTH = 1024;
    public static final int HTML_URL_LENGTH = 512;
    public static final int EMAIL_LENGTH = 255;

    /**
     * Auto-generated primary key.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The full 40-character SHA hash of the commit.
     * Unique within a repository (enforced by table constraint on sha + repository_id).
     */
    @Column(length = 40, nullable = false)
    @NonNull
    private String sha;

    /**
     * The commit message (first line / subject).
     */
    @NonNull
    @Column(length = MESSAGE_LENGTH, nullable = false)
    private String message;

    /**
     * The full commit message body (excluding subject line), if available.
     */
    @Column(columnDefinition = "TEXT")
    @ToString.Exclude
    private @Nullable String messageBody;

    /**
     * The URL to view this commit on the Git provider's web interface.
     */
    @Column(length = HTML_URL_LENGTH)
    private String htmlUrl;

    /**
     * The timestamp when the commit was authored.
     * This is different from committedAt - authoredAt is when the original
     * code change was created, committedAt is when it was applied.
     */
    @NonNull
    @Column(nullable = false)
    private Instant authoredAt;

    /**
     * The timestamp when the commit was committed.
     */
    @NonNull
    @Column(nullable = false)
    private Instant committedAt;

    /**
     * Number of additions in this commit.
     */
    private int additions;

    /**
     * Number of deletions in this commit.
     */
    private int deletions;

    /**
     * Number of files changed in this commit.
     */
    private int changedFiles;

    /**
     * Whether the commit has a valid cryptographic signature (GPG/SSH).
     * Null when the commit has not been enriched yet.
     */
    @Column(name = "signature_valid")
    private Boolean signatureValid;

    /**
     * Whether the commit was authored by the same person who committed it.
     * False for cherry-picks, rebases, or patches applied by a different person.
     * Null when the commit has not been enriched yet.
     */
    @Column(name = "authored_by_committer")
    private Boolean authoredByCommitter;

    /**
     * Whether the commit was made via the GitHub web interface.
     * True for edits, file creations, and merge commits done through the web UI.
     * Null when the commit has not been enriched yet.
     */
    @Column(name = "committed_via_web")
    private Boolean committedViaWeb;

    /**
     * Number of parent commits. 0 = root commit, 1 = normal commit, 2+ = merge commit.
     * Null when the commit has not been enriched yet.
     */
    @Column(name = "parent_count")
    private Integer parentCount;

    /**
     * The signature verification state from GitHub's GitSignatureState enum.
     * Provides granular detail beyond the boolean {@link #signatureValid} flag,
     * e.g. VALID, INVALID, BAD_CERT, EXPIRED_KEY, UNSIGNED, etc.
     * Null when the commit has not been enriched yet.
     */
    @Column(name = "signature_state", length = 32)
    private String signatureState;

    /**
     * Whether the commit signature was created by GitHub itself
     * (e.g. for web UI edits, merge commits).
     * Null when the commit has not been enriched yet.
     */
    @Column(name = "signature_was_signed_by_github")
    private Boolean signatureWasSignedByGitHub;

    /**
     * The login of the user who signed the commit, if the signature has an
     * associated GitHub user.
     * Null when the commit has not been enriched yet or the signer is not a GitHub user.
     */
    @Column(name = "signature_signer_login", length = 255)
    private String signatureSignerLogin;

    /**
     * Comma-separated SHAs of parent commits (up to 3 parents fetched).
     * Enables merge commit analysis and history graph traversal without
     * additional API calls.
     * Null when the commit has not been enriched yet.
     */
    @Column(name = "parent_shas", columnDefinition = "TEXT")
    private String parentShas;

    /**
     * The aggregated CI status check rollup state for this commit.
     * One of: ERROR, EXPECTED, FAILURE, PENDING, SUCCESS.
     * Null when the commit has not been enriched yet or has no status checks.
     */
    @Column(name = "status_check_rollup_state", length = 32)
    private String statusCheckRollupState;

    /**
     * The login of the organization on whose behalf this commit was made.
     * Populated from GitHub's {@code Commit.onBehalfOf} GraphQL field.
     * Null when the commit was not made on behalf of an organization.
     */
    @Column(name = "on_behalf_of_login", length = 255)
    private String onBehalfOfLogin;

    /**
     * The time when this commit was last synced.
     */
    private Instant lastSyncAt;

    /**
     * The email address of the commit author (from git metadata).
     * Stored at ingestion time to enable negative caching for author enrichment.
     * When author_email is set but author_id is NULL, enrichment was attempted and failed.
     */
    @Column(name = "author_email", length = EMAIL_LENGTH)
    private String authorEmail;

    /**
     * The email address of the committer (from git metadata).
     * Stored at ingestion time to enable negative caching for committer enrichment.
     * When committer_email is set but committer_id is NULL, enrichment was attempted and failed.
     */
    @Column(name = "committer_email", length = EMAIL_LENGTH)
    private String committerEmail;

    /**
     * Audit timestamp: when this entity was first persisted.
     */
    @Column(name = "created_at")
    private Instant createdAt;

    /**
     * Audit timestamp: when this entity was last modified.
     */
    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Set only after complete Git details and file changes have been persisted together. */
    @Column(name = "git_details_captured_at")
    private @Nullable Instant gitDetailsCapturedAt;

    // Relationships

    /**
     * The repository this commit belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_id", nullable = false)
    @ToString.Exclude
    private Repository repository;

    /**
     * The user who authored the commit.
     * May be null if the author is not a GitHub user or cannot be resolved.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id")
    @ToString.Exclude
    private @Nullable User author;

    /**
     * The user who committed the commit (may differ from author in cherry-picks, rebases, etc.).
     * May be null if the committer is not a GitHub user or cannot be resolved.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "committer_id")
    @ToString.Exclude
    private User committer;

    /**
     * The file changes in this commit.
     * Uses a List instead of Set because new (unsaved) CommitFileChange entities
     * all have id=null, causing HashSet to treat them as equal and silently dropping
     * all but one. ArrayList preserves all file changes until they are persisted.
     */
    @OneToMany(mappedBy = "commit", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    private List<CommitFileChange> fileChanges = new ArrayList<>();

    /**
     * The contributors to this commit (primary author, co-authors, committer).
     * Populated during enrichment from GitHub's {@code Commit.authors} field
     * which automatically parses Co-authored-by trailers.
     */
    @OneToMany(mappedBy = "commit", cascade = CascadeType.ALL, orphanRemoval = true)
    @ToString.Exclude
    private Set<CommitContributor> contributors = new HashSet<>();

    /**
     * The pull requests associated with this commit.
     * Populated from GitHub's {@code Commit.associatedPullRequests} GraphQL field
     * during enrichment. A commit may be associated with multiple PRs (e.g., cherry-picks).
     */
    @ManyToMany
    @JoinTable(
            name = "commit_pull_request",
            joinColumns = @JoinColumn(name = "commit_id"),
            inverseJoinColumns = @JoinColumn(name = "pull_request_id"))
    @ToString.Exclude
    private Set<PullRequest> associatedPullRequests = new HashSet<>();

    // Helper Methods

    /**
     * Returns the short SHA (first 7 characters) for display purposes.
     */
    public String getShortSha() {
        return sha != null && sha.length() >= 7 ? sha.substring(0, 7) : sha;
    }

    /**
     * Adds a file change to this commit and maintains bidirectional consistency.
     */
    public void addFileChange(CommitFileChange fileChange) {
        if (fileChange != null) {
            this.fileChanges.add(fileChange);
            fileChange.setCommit(this);
        }
    }

    /**
     * Removes a file change from this commit and maintains bidirectional consistency.
     */
    public void removeFileChange(CommitFileChange fileChange) {
        if (fileChange != null) {
            this.fileChanges.remove(fileChange);
            fileChange.setCommit(null);
        }
    }

    /**
     * Adds a contributor to this commit and maintains bidirectional consistency.
     */
    public void addContributor(CommitContributor contributor) {
        if (contributor != null) {
            this.contributors.add(contributor);
            contributor.setCommit(this);
        }
    }

    /**
     * Removes a contributor from this commit and maintains bidirectional consistency.
     */
    public void removeContributor(CommitContributor contributor) {
        if (contributor != null) {
            this.contributors.remove(contributor);
            contributor.setCommit(null);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Commit that = (Commit) o;
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
