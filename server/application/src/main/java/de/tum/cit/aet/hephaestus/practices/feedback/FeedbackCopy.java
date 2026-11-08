package de.tum.cit.aet.hephaestus.practices.feedback;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyKind;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "feedback_copy",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_feedback_copy_workspace_id",
                    columnNames = {"id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_feedback_copy_position",
                    columnNames = {"feedback_id", "position"}),
            @UniqueConstraint(
                    name = "uk_feedback_copy_external",
                    columnNames = {"feedback_id", "external_ref"})
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackCopy {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "feedback_id", nullable = false, columnDefinition = "UUID")
    private UUID feedbackId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private FeedbackCopyKind kind;

    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "body", nullable = true, columnDefinition = "TEXT")
    private @Nullable String body;

    @Column(name = "path", nullable = true, columnDefinition = "TEXT")
    private @Nullable String path;

    @Column(name = "start_line", nullable = true)
    private @Nullable Integer startLine;

    @Column(name = "end_line", nullable = true)
    private @Nullable Integer endLine;

    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = true, length = 3)
    private @Nullable CitationSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "stop_reason", nullable = true, length = 48)
    private @Nullable FeedbackStopReason stopReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "readback", nullable = true, length = 16)
    private @Nullable FeedbackCopyReadback readback;

    @Column(name = "last_error", nullable = true, length = 512)
    private @Nullable String lastError;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'PLANNED'")
    @Column(name = "state", nullable = false, length = 16)
    private FeedbackCopyState state;

    @Column(name = "write_started_at", nullable = true)
    private @Nullable Instant writeStartedAt;

    @Column(name = "write_key", nullable = true, columnDefinition = "TEXT")
    private @Nullable String writeKey;

    @Column(name = "write_marker", nullable = true, columnDefinition = "TEXT")
    private @Nullable String writeMarker;

    @Column(name = "external_ref", nullable = true, columnDefinition = "TEXT")
    private @Nullable String externalRef;

    @Column(name = "thread_ref", nullable = true, columnDefinition = "TEXT")
    private @Nullable String threadRef;

    @Column(name = "external_url", nullable = true, columnDefinition = "TEXT")
    private @Nullable String externalUrl;

    /** The Heph turn whose attempt claimed this conversation copy ({@code show_feedback}). */
    @Column(name = "mentor_turn_id", nullable = true, columnDefinition = "UUID")
    private @Nullable UUID mentorTurnId;

    /** The Heph answer that showed this conversation copy, recorded at settle. */
    @Column(name = "mentor_entry_id", nullable = true, columnDefinition = "UUID")
    private @Nullable UUID mentorEntryId;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "posted_at", nullable = true)
    private @Nullable Instant postedAt;
}
