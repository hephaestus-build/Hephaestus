package de.tum.cit.aet.hephaestus.integration.submission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One thing that a review can cite in a snapshot, as the server's indexer read it. It is part of the submitted content:
 * it never changes, and the database deletes it when its version ends.
 */
@Entity
@Table(
        name = "team_element",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_team_element_id",
                    columnNames = {"snapshot_id", "element_id"}),
            @UniqueConstraint(
                    name = "uk_team_element_line",
                    columnNames = {"snapshot_id", "first_line"})
        },
        indexes = {
            @Index(name = "idx_team_element_snapshot", columnList = "snapshot_id, workspace_id"),
            @Index(name = "idx_team_element_parent", columnList = "snapshot_id, parent_element_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamElement {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "snapshot_id", nullable = false, columnDefinition = "UUID")
    private UUID snapshotId;

    /** Unique within the snapshot. It means nothing in another version. */
    @Column(name = "element_id", nullable = false, length = 128)
    private String elementId;

    @Column(name = "kind", nullable = false, length = 64)
    private String kind;

    @Column(name = "label", nullable = false, columnDefinition = "TEXT")
    private String label;

    @Column(name = "parent_element_id", length = 128)
    private @Nullable String parentElementId;

    /** The element's citable lines. */
    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    /** Where the lines stand in the snapshot's text for a review, counted from 1. It also orders the elements. */
    @Column(name = "first_line", nullable = false)
    private int firstLine;

    @Column(name = "last_line", nullable = false)
    private int lastLine;
}
