package de.tum.cit.aet.hephaestus.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
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
 * One advice revision that a basis read (PRACTICE-ADVICE v4.1): the exact {@code practice_advice_revision} id that a
 * conversation read handed to the attempt. The row holds ids only. {@link ConversationBasis} writes it, and
 * {@code ConversationAdviceErasure} walks it back when the revision loses its text. The row goes with the revision.
 */
@Entity
@Immutable
@Table(
        name = "basis_advice_edge",
        indexes = @Index(name = "idx_basis_advice_edge_revision", columnList = "revision_id, workspace_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BasisAdviceEdge {

    @EmbeddedId
    private Key key;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

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

        @Column(name = "revision_id", nullable = false)
        private UUID revisionId;
    }
}
