package de.tum.cit.aet.hephaestus.workspace.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "practice_review_person_target")
@IdClass(PracticeReviewPersonTarget.Key.class)
@Getter
@Setter
@NoArgsConstructor
public class PracticeReviewPersonTarget {

    @Id
    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    public PracticeReviewPersonTarget(Long workspaceId, Long userId) {
        this.workspaceId = workspaceId;
        this.userId = userId;
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
        private Long userId;
    }
}
