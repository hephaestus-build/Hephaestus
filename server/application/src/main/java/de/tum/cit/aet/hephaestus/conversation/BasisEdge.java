package de.tum.cit.aet.hephaestus.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * One source that a basis read (ADR 0128 {@code basis_edge}): a conversation entry, at the revision that the attempt
 * read. The row holds ids and a revision, never words and never readers: a check resolves the current readers itself.
 * {@link ConversationBasis} writes and reads the rows. The row goes with the entry that it names.
 */
@Entity
@Immutable
@Table(name = "basis_edge", indexes = @Index(name = "idx_basis_edge_source", columnList = "source_id, workspace_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BasisEdge {

    /** What a basis can read. */
    public enum SourceKind {
        /** A conversation entry: history, the question, or a {@code search_conversation} hit. */
        ENTRY,
    }

    @EmbeddedId
    private Key key;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /** The source's {@link ConversationEntry#getRevision} when the attempt read it. */
    @Column(name = "source_revision", nullable = false)
    private int sourceRevision;

    @Embeddable
    @Getter
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Column(name = "basis_id", nullable = false)
        private UUID basisId;

        @Enumerated(EnumType.STRING)
        @Column(name = "source_kind", nullable = false, length = 16)
        private SourceKind sourceKind;

        @Column(name = "source_id", nullable = false)
        private UUID sourceId;
    }
}
