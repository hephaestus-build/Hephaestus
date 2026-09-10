package de.tum.cit.aet.hephaestus.workspace.directory;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Set;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "directory_policy",
        uniqueConstraints = @UniqueConstraint(name = "uq_directory_policy_workspace", columnNames = "workspace_id"))
@Getter
@Setter
@NoArgsConstructor
public class DirectoryPolicy {
    public enum Status {
        DRAFT,
        ACTIVE,
        PAUSED,
        ENDED
    }

    public enum Health {
        UNVERIFIED,
        HEALTHY,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "identity_provider_id", nullable = false)
    private Long identityProviderId;

    @Column(name = "registration_id", nullable = false, length = 64)
    private String registrationId;

    @Column(nullable = false, length = 512)
    private String issuer;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.DRAFT;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Health health = Health.UNVERIFIED;

    @Column(name = "configuration_version", nullable = false)
    private long configurationVersion = 1;

    @Version
    @Column(nullable = false)
    private long version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_group_ids", columnDefinition = "jsonb", nullable = false)
    private Set<String> draftGroupIds = Set.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approved_group_ids", columnDefinition = "jsonb", nullable = false)
    private Set<String> approvedGroupIds = Set.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "active_snapshot", columnDefinition = "jsonb")
    private @Nullable DirectorySnapshot activeSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preview_snapshot", columnDefinition = "jsonb")
    private @Nullable DirectorySnapshot previewSnapshot;

    @Column(name = "last_attempt_at")
    private @Nullable Instant lastAttemptAt;

    @Column(name = "failure_reason", length = 512)
    private @Nullable String failureReason;

    @Column(name = "approved_by_account_id")
    private @Nullable Long approvedByAccountId;

    @Column(name = "approved_at")
    private @Nullable Instant approvedAt;
}
