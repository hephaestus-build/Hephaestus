package de.tum.cit.aet.hephaestus.agent.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/** Per-workspace grant of a {@code GRANTED}-visibility instance {@link LlmModel}. */
@Entity
@Table(name = "llm_model_workspace_grant")
@Getter
@Setter
@NoArgsConstructor
public class LlmModelWorkspaceGrant {

    @EmbeddedId
    private Id id;

    @Column(name = "granted_at", nullable = false)
    private @Nullable Instant grantedAt;

    @Nullable
    @Column(name = "granted_by", length = 255)
    private String grantedBy;

    public LlmModelWorkspaceGrant(Long modelId, Long workspaceId) {
        this.id = new Id(modelId, workspaceId);
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Column(name = "model_id", nullable = false)
        private Long modelId;

        @Column(name = "workspace_id", nullable = false)
        private Long workspaceId;
    }
}
