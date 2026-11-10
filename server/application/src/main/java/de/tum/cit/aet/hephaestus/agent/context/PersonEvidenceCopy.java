package de.tum.cit.aet.hephaestus.agent.context;

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
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * The receipt of one attempt's copy of people's data in its folder. Erasure marks it {@code ERASED} in PostgreSQL, and
 * every server then removes its own folders of the attempt within one reconcile pass. These stable coordinates
 * deliberately have no cascading foreign keys: retention deletes a receipt only once it is erased. Content and exact
 * identities are cleared when the erasure completes.
 */
@Entity
@Table(
        name = "person_evidence_copy",
        indexes = @Index(name = "idx_person_evidence_copy_job", columnList = "job_id"),
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_person_evidence_copy_workspace",
                        columnNames = {"id", "workspace_id"}))
@Getter
@Setter
public class PersonEvidenceCopy {
    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @ColumnDefault("now()")
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private State state = State.CAPTURING;

    public enum State {
        CAPTURING,
        READY,
        ERASED
    }

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;
}
