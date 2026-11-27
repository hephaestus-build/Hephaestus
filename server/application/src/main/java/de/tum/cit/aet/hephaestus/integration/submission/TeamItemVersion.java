package de.tum.cit.aet.hephaestus.integration.submission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One immutable submitted version of a team item. The row stays as a tombstone after it ends, so that its sequence
 * number is never used again.
 */
@Entity
@Table(
        name = "team_item_version",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_team_item_version_sequence",
                    columnNames = {"item_id", "sequence_number"}),
            @UniqueConstraint(
                    name = "uk_team_item_version_request",
                    columnNames = {"item_id", "request_key"}),
            @UniqueConstraint(
                    name = "uk_team_item_version_workspace",
                    columnNames = {"id", "workspace_id"})
        },
        indexes = {
            @Index(name = "idx_team_item_version_item", columnList = "item_id, workspace_id"),
            @Index(name = "idx_team_item_version_submitter", columnList = "submitted_by")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamItemVersion {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "item_id", nullable = false)
    private long itemId;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    /** The account that submitted it. Null only after that account was erased. */
    @Column(name = "submitted_by")
    private @Nullable Long submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "request_key", nullable = false, columnDefinition = "UUID")
    private UUID requestKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private TeamVersionState state;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** The database sets it. Content of a version is accepted only in the transaction that created the version. */
    @Column(name = "created_in_transaction", nullable = false, insertable = false, updatable = false)
    private long createdInTransaction;

    /** What the submitter called the item. Submitted content: absent once the version has ended. */
    @Column(name = "title", length = 200)
    private @Nullable String title;
}
