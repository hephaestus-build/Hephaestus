package de.tum.cit.aet.hephaestus.agent.mentor.sandbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

/**
 * One conversation tools Pod of the installation. A row without a turn is pooled: any server may bind it. A bound row
 * names the one turn attempt that the Pod serves for its whole life. The row is written before its Job is created, so
 * every Job of the kind has a row or is removed.
 */
@Entity
@Table(
        name = "conversation_tools_pod",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_conversation_tools_pod_attempt",
                        columnNames = {"turn_id", "attempt"}),
        indexes = @Index(name = "idx_conversation_tools_pod_workspace", columnList = "workspace_id"))
@Getter
@NoArgsConstructor
public class ConversationToolsPod {

    /** The Job's name, {@code hephaestus-tools-<id>}. */
    @Id
    @Column(name = "name", length = 63)
    private String name;

    /** What the Pod's {@code hello} returns. */
    @Column(name = "nonce", nullable = false)
    private UUID nonce;

    /** The Pod's address once its tools program runs. */
    @Column(name = "address", length = 45)
    private String address;

    @Column(name = "created_at", nullable = false)
    @ColumnDefault("now()")
    private Instant createdAt;

    /** The workspace of the bound turn: {@code ContextChanged} revokes by it. */
    @Column(name = "workspace_id")
    private Long workspaceId;

    @Column(name = "turn_id")
    private UUID turnId;

    @Column(name = "attempt")
    private Integer attempt;

    /** The workspace's data changed: the attempt's next bind answers {@code CONTEXT_CHANGED} once. */
    @Column(name = "revoked", nullable = false)
    @ColumnDefault("false")
    private boolean revoked;
}
