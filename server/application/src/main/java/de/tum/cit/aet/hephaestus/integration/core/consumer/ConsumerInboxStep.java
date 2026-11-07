package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(
        name = "consumer_inbox_step",
        indexes = @Index(name = "idx_consumer_inbox_step_processed_at", columnList = "processed_at"),
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_consumer_inbox_step_delivery",
                        columnNames = {
                            "workspace_id",
                            "consumer_name",
                            "stream_name",
                            "stream_sequence",
                            "received_at",
                            "step_name"
                        }))
@Getter
@Setter
public class ConsumerInboxStep extends InboxStep {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_consumer_inbox_step_workspace"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Workspace workspace;
}
