package de.tum.cit.aet.hephaestus.productfeedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One choice a person made about public issues, appended and never changed. The newest event for the current notice
 * version is the person's current choice; there is no mutable flag.
 */
@Entity
@Table(
        name = "product_feedback_consent",
        indexes = @Index(name = "idx_product_feedback_consent_account", columnList = "account_id, created_at"))
@Getter
@NoArgsConstructor
public class ProductFeedbackConsent {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Column(name = "allow_public", nullable = false)
    private boolean allowPublic;

    @Column(nullable = false)
    private boolean mention;

    @Column(name = "notice_version", nullable = false)
    private int noticeVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    ProductFeedbackConsent(long accountId, boolean allowPublic, boolean mention, int noticeVersion, Instant createdAt) {
        this.accountId = accountId;
        this.allowPublic = allowPublic;
        this.mention = mention;
        this.noticeVersion = noticeVersion;
        this.createdAt = createdAt;
    }
}
