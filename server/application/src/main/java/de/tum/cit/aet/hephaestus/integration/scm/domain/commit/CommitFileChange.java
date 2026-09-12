package de.tum.cit.aet.hephaestus.integration.scm.domain.commit;

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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Entity representing a file change within a Git commit.
 * <p>
 * Tracks individual file modifications including the type of change
 * (added, modified, deleted, renamed), line statistics, and file paths.
 */
@Entity
@Table(
        name = "commit_file_change",
        indexes = {
            @Index(name = "idx_commit_file_change_commit_id", columnList = "commit_id"),
            @Index(name = "idx_commit_file_change_filename", columnList = "filename"),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class CommitFileChange {
    public static final int FILENAME_LENGTH = 1024;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * The filename/path of the changed file.
     */
    @NonNull
    @Column(length = FILENAME_LENGTH, nullable = false)
    private String filename;

    /**
     * The type of change made to the file.
     */
    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private ChangeType changeType;

    /**
     * Number of lines added in this file.
     */
    private int additions;

    /**
     * Number of lines deleted in this file.
     */
    private int deletions;

    /**
     * Total number of changes (additions + deletions).
     */
    private int changes;

    /**
     * The previous filename, if the file was renamed.
     */
    @Column(length = FILENAME_LENGTH)
    private @Nullable String previousFilename;

    // Relationships

    /**
     * The commit this file change belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "commit_id", nullable = false)
    @ToString.Exclude
    @Nullable
    private Commit commit;

    // Enums

    /**
     * The type of change made to a file.
     * Maps to GitHub's file status values.
     */
    public enum ChangeType {
        /** File was added. */
        ADDED,
        /** File was modified. */
        MODIFIED,
        /** File was deleted. */
        REMOVED,
        /** File was renamed. */
        RENAMED,
        /** File was copied. */
        COPIED,
        /** File mode was changed. */
        CHANGED,
        /** Unknown change type. */
        UNKNOWN,
    }

    /**
     * Parse a GitHub file status string to ChangeType.
     */
    public static ChangeType parseChangeType(String status) {
        if (status == null) {
            return ChangeType.UNKNOWN;
        }
        return switch (status.toLowerCase()) {
            case "added" -> ChangeType.ADDED;
            case "modified" -> ChangeType.MODIFIED;
            case "removed", "deleted" -> ChangeType.REMOVED;
            case "renamed" -> ChangeType.RENAMED;
            case "copied" -> ChangeType.COPIED;
            case "changed" -> ChangeType.CHANGED;
            default -> ChangeType.UNKNOWN;
        };
    }
}
