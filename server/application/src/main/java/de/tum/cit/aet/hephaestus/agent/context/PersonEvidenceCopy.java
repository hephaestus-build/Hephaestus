package de.tum.cit.aet.hephaestus.agent.context;

import jakarta.persistence.*;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * A mounted copy outlives its source job or workspace until its owner acknowledges removal. These
 * stable coordinates deliberately have no cascading foreign keys: a deleted parent is not proof
 * that its worker volume was erased. Content and exact identities are cleared after acknowledgement.
 */
@Entity
@Table(
        name = "person_evidence_copy",
        indexes = {
            @Index(name = "idx_person_evidence_copy_store", columnList = "store_id,state"),
            @Index(name = "idx_person_evidence_copy_job", columnList = "job_id")
        })
@Getter
@Setter
public class PersonEvidenceCopy {
    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private State state = State.CAPTURING;

    public enum State {
        CAPTURING,
        READY,
        ERASE_REQUESTED,
        ERASED
    }

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;
}
