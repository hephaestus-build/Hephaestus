package de.tum.cit.aet.hephaestus.workspace.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "practice_review_repository_target")
@IdClass(PracticeReviewRepositoryTarget.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class PracticeReviewRepositoryTarget {

    @Id
    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Id
    @Column(name = "repository_monitor_id", nullable = false)
    private Long repositoryMonitorId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "base_branches", nullable = false, columnDefinition = "jsonb")
    private List<String> baseBranches = List.of();

    public PracticeReviewRepositoryTarget(Long workspaceId, Long repositoryMonitorId, List<String> baseBranches) {
        this.workspaceId = workspaceId;
        this.repositoryMonitorId = repositoryMonitorId;
        this.baseBranches = List.copyOf(baseBranches);
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Nullable
        private Long workspaceId;

        @Nullable
        private Long repositoryMonitorId;
    }
}
