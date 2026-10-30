package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "consumer_dead_letter",
        indexes = @Index(name = "idx_consumer_dead_letter_workspace_id", columnList = "workspace_id,id"),
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_consumer_dead_letter_delivery",
                        columnNames = {"consumer_name", "stream_name", "stream_sequence"}))
@Getter
@Setter
public class ConsumerDeadLetter {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** Installation lifecycle events precede workspace creation. Only instance admins can read these rows. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", foreignKey = @ForeignKey(name = "fk_consumer_dead_letter_workspace"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private @Nullable Workspace workspace;

    @Column(name = "consumer_name", nullable = false)
    private String consumerName;

    @Column(name = "stream_name", nullable = false)
    private String streamName;

    @Column(name = "stream_sequence", nullable = false)
    private long streamSequence;

    @Column(columnDefinition = "text", nullable = false)
    private String subject;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "error_class", nullable = false)
    private String errorClass;

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt;

    @Column(name = "replayed_at")
    private @Nullable Instant replayedAt;

    @Column(name = "replayed_by")
    private @Nullable Long replayedBy;
}
