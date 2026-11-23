package de.tum.cit.aet.hephaestus.core.auth.export;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * An asynchronous GDPR Art. 20 ("right to data portability") self-service data export for one
 * {@code Account}. One row per "download my data" request.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   PENDING ──(picked up async)──▶ PROCESSING ──(bundle assembled)──▶ READY ──(48h)──▶ EXPIRED
 *                                       └────────(assembly failed)──▶ FAILED
 * </pre>
 *
 * <h2>Ownership &amp; enumeration defense</h2>
 * Every row belongs to exactly one {@code account_id}. The service only ever loads a row by
 * {@code (id, account_id)} — a caller asking for someone else's export id gets an empty result,
 * surfaced as 404 (never 403) so an attacker cannot probe which ids exist.
 *
 * <h2>Payload &amp; retention</h2>
 * The generated JSON bundle is stored inline as {@code payload} (BYTEA) — there is no object
 * store. {@code expires_at} sets a 48h retention window; a scheduled sweep flips expired READY
 * rows to EXPIRED and nulls the payload so PII isn't retained beyond the download window. The
 * payload NEVER contains tokens, credential blobs, signing keys, or other users' data.
 *
 * <p>Account-scoped, not workspace-scoped: an export spans everything a principal owns across
 * workspaces. Listed in {@code WorkspaceScopedTables.GLOBAL_TABLES} and the arch-test
 * {@code GLOBAL_ENTITIES} allowlist, alongside the other {@code core.auth} identity tables.
 */
@Entity
@PersonOwned
@Table(name = "account_export")
@Getter
@Setter
@NoArgsConstructor
public class AccountExport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    /** Owner. Not a JPA association — kept as a bare FK id to avoid an entity edge into the
     * account aggregate from the export aggregate; the export only ever needs the id. */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @ColumnDefault("'PENDING'")
    private Status status = Status.PENDING;

    @CreationTimestamp
    @Column(name = "requested_at", nullable = false, updatable = false)
    private @Nullable Instant requestedAt;

    @Column(name = "completed_at")
    @Nullable
    private Instant completedAt;

    @Column(name = "expires_at")
    @Nullable
    private Instant expiresAt;

    /** Short, machine-readable reason when {@link Status#FAILED}. Never a stack trace. */
    @Column(name = "failure_reason", length = 128)
    @Nullable
    private String failureReason;

    /** The generated JSON bundle. Null until READY; nulled again on EXPIRED. */
    @Column(name = "payload")
    private byte @Nullable [] payload;

    public AccountExport(Long accountId) {
        this.accountId = accountId;
        this.status = Status.PENDING;
    }

    public enum Status {
        PENDING,
        PROCESSING,
        READY,
        FAILED,
        EXPIRED,
    }
}
