package de.tum.cit.aet.hephaestus.practices.advice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * The one decision that a review made about an advice entry. A retry of the same decision finds it and writes nothing;
 * another decision from the same review is refused. It holds a digest of the decision, never its words.
 */
@Entity
@Immutable
@IdClass(PracticeAdviceReceipt.Key.class)
@Table(
        name = "practice_advice_receipt",
        indexes = @Index(name = "idx_practice_advice_receipt_advice", columnList = "advice_id, workspace_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeAdviceReceipt {
    @Id
    @Column(name = "advice_id", nullable = false, columnDefinition = "UUID")
    private UUID adviceId;

    @Id
    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Id
    @Column(name = "review_id", nullable = false, columnDefinition = "UUID")
    private UUID reviewId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    /** SHA-256 of the decision, so a retry with the same content matches it. */
    @Column(name = "content_digest", nullable = false)
    private byte[] contentDigest;

    /** The entry's version after this decision. */
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "written_at", nullable = false)
    private Instant writtenAt;

    public enum Kind {
        REVISE,
        RETIRE
    }

    /** The composite key; its field names mirror the {@code @Id} fields. */
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private @Nullable UUID adviceId;
        private @Nullable Long workspaceId;
        private @Nullable UUID reviewId;
    }
}
