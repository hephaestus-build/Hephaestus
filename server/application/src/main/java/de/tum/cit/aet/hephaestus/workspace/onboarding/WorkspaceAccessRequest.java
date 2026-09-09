package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/** Submitted evidence never changes; an amended submission is a new request with its own acknowledgements. */
@Entity
@Table(
        name = "workspace_access_request",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_workspace_access_request_workspace",
                    columnNames = {"id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uq_workspace_access_request_account",
                    columnNames = {"id", "workspace_id", "account_id"})
        },
        indexes = {
            @Index(
                    name = "idx_workspace_access_request_account",
                    columnList = "workspace_id, account_id, submitted_at"),
            @Index(name = "idx_workspace_access_request_status", columnList = "workspace_id, status")
        })
@Getter
@Setter
@NoArgsConstructor
class WorkspaceAccessRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_access_request_workspace"))
    private Workspace workspace;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Version
    @Column(nullable = false)
    private long version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.SUBMITTED;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;

    @Column(name = "policy_version", nullable = false, updatable = false)
    private long policyVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "policy_snapshot", nullable = false, updatable = false, columnDefinition = "jsonb")
    private WorkspaceAccessPolicySettings policySnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "submission", nullable = false, updatable = false, columnDefinition = "jsonb")
    private Submission submission;

    @Nullable
    @Column(name = "supersedes_request_id", updatable = false)
    private Long supersedesRequestId;

    @Nullable
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approved_details", columnDefinition = "jsonb")
    private WorkspaceAccessDetails approvedDetails;

    @Nullable
    @Column(name = "decided_by_account_id")
    private Long decidedByAccountId;

    @Nullable
    @Column(name = "decided_at")
    private Instant decidedAt;

    @Nullable
    @Column(name = "decision_comment", length = 4000)
    private String decisionComment;

    enum Status {
        SUBMITTED,
        CHANGES_REQUESTED,
        APPROVED,
        REJECTED,
        CANCELLED,
        SUPERSEDED
    }

    record Submission(
            WorkspaceAccessDetails details,
            List<Long> identityLinkIds,
            List<String> acknowledgedNoticeKeys,
            @Nullable String comments) {}
}
