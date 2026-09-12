package de.tum.cit.aet.hephaestus.core.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * The Hephaestus-native principal. One row per human (and, post-MVP, per service principal)
 * that has authenticated through any {@link IdentityLink}.
 *
 * <p>This is the entity that {@code AccountFeature.account_id},
 * preferences, sessions, exports, and audit rows reference. The git-provider mirror
 * ({@code User} in {@code integration.scm.domain.user}) is a separate, read-only entity
 * that records activity authorship — bots and organizations live there, not here.
 *
 * <h2>Email — contact only, never auth</h2>
 * {@code primary_email} is for outbound contact (account-delete confirmation when we add
 * email, security alerts, GDPR notifications). It is <em>never</em> used to look up an
 * account during login. Authentication lookup is always {@code (provider, subject)} via
 * {@link IdentityLink} — defense against the nOAuth (Descope 2023) class of attacks.
 */
@Entity
@Table(name = "account")
@Getter
@Setter
@NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    /**
     * Contact email (CITEXT). NEVER used for authentication lookup — see class doc.
     * Unique among non-deleted active accounts.
     */
    @Column(name = "primary_email", columnDefinition = "citext")
    @Nullable
    private String primaryEmail;

    @Column(name = "primary_email_verified_at")
    @Nullable
    private Instant primaryEmailVerifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "app_role", nullable = false, length = 16)
    @ColumnDefault("'USER'")
    private AppRole appRole = AppRole.USER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @ColumnDefault("'ACTIVE'")
    private Status status = Status.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private @Nullable Instant updatedAt;

    /**
     * Set when the account enters {@link Status#DELETING} (start of the GDPR-cooldown window,
     * {@code hephaestus.auth.delete-cooldown}, default 48h). Once it is older than the cooldown,
     * {@code AccountHardDeleteSweeper} purges the account's personal/auth child rows and flips the
     * row to {@link Status#DELETED}.
     */
    @Column(name = "deleted_at")
    @Nullable
    private Instant deletedAt;

    @Version
    @Column(nullable = false)
    @ColumnDefault("0")
    private Long version = 0L;

    public Account(String displayName) {
        this.displayName = displayName;
    }

    public enum AppRole {
        USER,
        APP_ADMIN,
    }

    public enum Status {
        ACTIVE,
        SUSPENDED,
        /**
         * Soft-deleted: sessions revoked and login blocked immediately; the row is purged after the
         * 48h cooldown ({@code AccountHardDeleteSweeper}). There is no self-service recovery path today.
         */
        DELETING,
        DELETED,
    }
}
