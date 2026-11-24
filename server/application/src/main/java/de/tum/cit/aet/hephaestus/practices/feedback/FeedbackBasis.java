package de.tum.cit.aet.hephaestus.practices.feedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

@Entity
@Immutable
@Table(
        name = "feedback_basis",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_feedback_basis_workspace_id",
                    columnNames = {"id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_feedback_basis_ref",
                    columnNames = {"feedback_id", "result_id", "problem_id"})
        },
        indexes = {
            @Index(name = "idx_feedback_basis_result", columnList = "result_id, workspace_id"),
            @Index(name = "idx_feedback_basis_order", columnList = "feedback_id, ordinal, id")
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedbackBasis {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "feedback_id", nullable = false, columnDefinition = "UUID")
    private UUID feedbackId;

    @Column(name = "result_id", nullable = false, columnDefinition = "UUID")
    private UUID resultId;

    @Column(name = "problem_id", nullable = true, columnDefinition = "UUID")
    private @Nullable UUID problemId;

    @Column(name = "person_account_id", nullable = false)
    private long developerId;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;
}
