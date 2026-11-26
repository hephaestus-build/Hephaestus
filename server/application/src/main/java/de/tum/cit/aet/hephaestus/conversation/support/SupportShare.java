package de.tum.cit.aet.hephaestus.conversation.support;

import de.tum.cit.aet.hephaestus.core.TimeOrderedIds;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

/**
 * One conversation turn that its person shares with one instance admin. The row holds ids, times and a digest, never
 * text: a read renders the turn from its current rows and shows its text only while that text matches the digest the
 * person confirmed.
 */
@Entity
@Table(
        name = "support_share",
        indexes = {
            @Index(name = "idx_support_share_account", columnList = "account_id, created_at"),
            @Index(name = "idx_support_share_recipient", columnList = "recipient_account_id, expires_at"),
            @Index(name = "idx_support_share_workspace", columnList = "workspace_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SupportShare {

    /** How long a share stays readable to its recipient. */
    public static final Duration VALIDITY = Duration.ofDays(7);

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The person who shares, and who alone can revoke the share. */
    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    /** The one instance admin who can read the share. */
    @Column(name = "recipient_account_id", nullable = false, updatable = false)
    private Long recipientAccountId;

    /** The workspace of the shared turn. It names the place; it does not make the share workspace data. */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    @Column(name = "turn_id", nullable = false, updatable = false)
    private UUID turnId;

    /**
     * The SHA-256 of the messages that the person confirmed, or {@code null} when the person shared only the turn's
     * diagnostics.
     */
    @Nullable
    @Column(name = "text_digest", length = 64, updatable = false)
    private String textDigest;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Nullable
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @ColumnDefault("0")
    @Column(name = "read_count", nullable = false)
    private int readCount;

    @Nullable
    @Column(name = "last_read_at")
    private Instant lastReadAt;

    SupportShare(
            long accountId,
            long recipientAccountId,
            long workspaceId,
            UUID turnId,
            @Nullable String textDigest,
            Instant now) {
        this.id = TimeOrderedIds.next();
        this.accountId = accountId;
        this.recipientAccountId = recipientAccountId;
        this.workspaceId = workspaceId;
        this.turnId = turnId;
        this.textDigest = textDigest;
        this.createdAt = now;
        this.expiresAt = now.plus(VALIDITY);
    }

    public boolean includesText() {
        return textDigest != null;
    }

    /** Whether the recipient may still read it at {@code now}. */
    public boolean readable(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }
}
