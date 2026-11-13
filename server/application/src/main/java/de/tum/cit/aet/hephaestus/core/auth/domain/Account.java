package de.tum.cit.aet.hephaestus.core.auth.domain;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;
import org.springframework.modulith.NamedInterface;

/**
 * One human principal across workspaces. Roster identities can own an unclaimed account. A verified
 * sign-in claims its exact provider key. Email is contact data, never ownership proof.
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

    // Contact email is never an authentication lookup key.
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

    /** Changes only through the transition methods below. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @ColumnDefault("'ACTIVE'")
    @Setter(AccessLevel.NONE)
    private Status status = Status.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private @Nullable Instant updatedAt;

    /**
     * Set when the account enters {@link Status#DELETING}. {@code AccountHardDeleteSweeper} then purges the account's
     * personal and auth child rows and flips the row to {@link Status#DELETED}.
     */
    @Column(name = "deleted_at")
    @Nullable
    @Setter(AccessLevel.NONE)
    private Instant deletedAt;

    @Column(name = "passkey_user_handle", length = 64, unique = true)
    @Nullable
    private String passkeyUserHandle;

    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean passkeyProtectionEnabled;

    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean passkeyRecoveryRequired;

    /** Hidden until the person answers: an account that never chose is on no public activity page. */
    @Column(name = "public_activity_visible", nullable = false)
    @ColumnDefault("false")
    private boolean publicActivityVisible;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_choice", length = 24)
    private @Nullable AiChoice aiChoice;

    @Column(name = "ai_choice_at")
    private @Nullable Instant aiChoiceAt;

    @Column(name = "feedback_delivery_enabled", nullable = false)
    @ColumnDefault("true")
    private boolean feedbackDeliveryEnabled = true;

    /**
     * IANA time zone the developer's own week runs in, for example {@code Europe/Berlin}, as the developer chose it
     * in User settings; {@code null} means {@link
     * de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory#DEFAULT_TIME_ZONE}.
     */
    @Column(name = "time_zone", length = 64)
    @Nullable
    private String timeZone;

    @Version
    @Column(nullable = false)
    @ColumnDefault("0")
    private @Nullable Long version;

    public Account(String displayName) {
        this.displayName = displayName;
    }

    /** A person a roster names who has not signed in yet. */
    public static Account unclaimed(String displayName) {
        var account = new Account(displayName);
        account.status = Status.UNCLAIMED;
        return account;
    }

    /** The person's first verified sign-in takes the account over. */
    public void claim() {
        require(Status.UNCLAIMED);
        moveTo(Status.ACTIVE);
    }

    public void block() {
        require(Status.ACTIVE);
        moveTo(Status.BLOCKED);
    }

    public void unblock() {
        require(Status.BLOCKED);
        moveTo(Status.ACTIVE);
    }

    /** Sessions end and sign-in stops now; the purge erases the rest. */
    public void startDeletion(Instant at) {
        moveTo(Status.DELETING);
        this.deletedAt = at;
    }

    /** The purge has erased the account. A repeated purge finds it deleted already. */
    public void markDeleted() {
        if (status == Status.DELETED) {
            return;
        }
        moveTo(Status.DELETED);
    }

    private void require(Status expected) {
        if (status != expected) {
            throw new IllegalStateException("The account is " + status + ", not " + expected);
        }
    }

    private void moveTo(Status next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("An account cannot move from " + status + " to " + next);
        }
        status = next;
    }

    public enum AppRole {
        USER,
        APP_ADMIN,
    }

    /** The trigger {@code guard_account} keeps a deletion moving forward in the database too. */
    @NamedInterface("auth-types")
    public enum Status implements Lifecycle<Status> {
        UNCLAIMED,
        ACTIVE,
        /** An instance admin blocked the account: sessions are revoked and sign-in is refused until an unblock. */
        BLOCKED,
        /** Sessions are revoked and login blocked immediately; erasure follows once the deletion commits. */
        DELETING,
        DELETED;

        private static final Map<Status, Set<Status>> LEGAL = Map.of(
                UNCLAIMED, Set.of(ACTIVE, DELETING),
                ACTIVE, Set.of(BLOCKED, DELETING),
                BLOCKED, Set.of(ACTIVE, DELETING),
                DELETING, Set.of(DELETED),
                DELETED, Set.of());

        @Override
        public boolean canTransitionTo(Status next) {
            return Objects.requireNonNull(LEGAL.get(this)).contains(next);
        }
    }
}
