package de.tum.cit.aet.hephaestus.productfeedback;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "product_feedback",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_product_feedback_rate_limit",
                        columnNames = {"account_id", "submission_minute"}))
@Getter
@NoArgsConstructor
public class ProductFeedback {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "workspace_id")
    private @Nullable Long workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    @Column(nullable = false, length = 5000)
    private String message;

    @Column(name = "page_path", length = 500)
    private @Nullable String pagePath;

    @Column(name = "user_agent", length = 500)
    private @Nullable String userAgent;

    /** The Hephaestus release that received the submission, recorded so a report outlives the upgrade that fixes it. */
    @Column(name = "app_version", nullable = false, length = 64)
    private String appVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @Column(name = "submission_minute", nullable = false, updatable = false)
    private Instant submissionMinute = Instant.now().truncatedTo(ChronoUnit.MINUTES);

    @Column(name = "resolved_at")
    private @Nullable Instant resolvedAt;

    @Column(name = "resolved_by_account_id")
    private @Nullable Long resolvedByAccountId;

    public ProductFeedback(
            Long accountId,
            @Nullable Long workspaceId,
            Kind kind,
            String message,
            @Nullable String pagePath,
            @Nullable String userAgent,
            String appVersion) {
        this.accountId = accountId;
        this.workspaceId = workspaceId;
        this.kind = kind;
        this.message = message;
        this.pagePath = pagePath;
        this.userAgent = userAgent;
        this.appVersion = appVersion;
    }

    public void resolve(Long accountId, Instant now) {
        this.resolvedAt = now;
        this.resolvedByAccountId = accountId;
    }

    public void reopen() {
        this.resolvedAt = null;
        this.resolvedByAccountId = null;
    }

    public boolean isResolved() {
        return resolvedAt != null;
    }

    public enum Kind {
        FEEDBACK,
        BUG
    }
}
