package de.tum.cit.aet.hephaestus.practices.repository;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;

/**
 * The workspace's practice repository (ADR 0118): the monitored repository whose default branch holds the practices
 * that are edited there, one folder for each practice under {@link #getDirectory()}. A workspace has at most one.
 */
@Entity
@Table(name = "workspace_practice_repository")
@Getter
@Setter
public class PracticeRepositorySetting {
    public static final String DEFAULT_DIRECTORY = ".hephaestus/practices";

    @Id
    @Column(name = "workspace_id")
    private Long workspaceId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    // The setting belongs to its workspace, so a release that deletes the workspace row removes it too.
    @OnDelete(action = OnDeleteAction.CASCADE)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_practice_repository_workspace"))
    private Workspace workspace;

    /** The repository item, as {@code list_repositories} and the review scope name it. */
    @Column(name = "repository_item_id", nullable = false)
    private long repositoryItemId;

    @ColumnDefault("'.hephaestus/practices'")
    @Column(name = "directory", nullable = false, length = 255)
    private String directory = DEFAULT_DIRECTORY;

    @Column(name = "updated_at", nullable = false)
    private @Nullable Instant updatedAt;

    @Version
    @ColumnDefault("0")
    @Column(nullable = false)
    private long revision = 0;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
