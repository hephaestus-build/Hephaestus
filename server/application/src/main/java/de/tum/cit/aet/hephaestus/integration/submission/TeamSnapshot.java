package de.tum.cit.aet.hephaestus.integration.submission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One member of a submitted version, exactly as the sender previewed it. It is the evidence: it never changes, no link
 * in it is fetched, and the database deletes it when its version ends.
 */
@Entity
@Table(
        name = "team_snapshot",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_team_snapshot_key",
                    columnNames = {"version_id", "snapshot_key"}),
            @UniqueConstraint(
                    name = "uk_team_snapshot_workspace",
                    columnNames = {"id", "workspace_id"})
        },
        indexes = @Index(name = "idx_team_snapshot_version", columnList = "version_id, workspace_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TeamSnapshot {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    /** The member's stable name in the item, which also names its text for a review. */
    @Column(name = "snapshot_key", nullable = false, length = 64)
    private String snapshotKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private TeamSnapshotRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_route", nullable = false, length = 16)
    private TeamEntryRoute entryRoute;

    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "media_type", nullable = false, length = 100)
    private String mediaType;

    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    /** The normalized document that the server made. Absent where the content is that document, or has none. */
    @Column(name = "normalized")
    private byte @Nullable [] normalized;

    @Column(name = "normalized_sha256", length = 64)
    private @Nullable String normalizedSha256;

    @Column(name = "normalizer_id", length = 64)
    private @Nullable String normalizerId;

    @Column(name = "normalizer_version")
    private @Nullable Integer normalizerVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "normalized_by", length = 8)
    private @Nullable TeamNormalizedBy normalizedBy;

    /** An image for people only. No review reads it. */
    @Column(name = "preview")
    private byte @Nullable [] preview;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_how", nullable = false, length = 16)
    private TeamSourceHow sourceHow;

    /** Which revision of a source the server read; recorded only when it read with the member's access. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_revision", columnDefinition = "jsonb")
    private @Nullable JsonNode sourceRevision;

    /** Where a person can open the source today. Never fetched for a review and never evidence. */
    @Column(name = "live_link", columnDefinition = "TEXT")
    private @Nullable String liveLink;

    /** What the capture did not hold, as {@code [{"code": …, "count": …}]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "limitations", nullable = false, columnDefinition = "jsonb")
    private JsonNode limitations;
}
