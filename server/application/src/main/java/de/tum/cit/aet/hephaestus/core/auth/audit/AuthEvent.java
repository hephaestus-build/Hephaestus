package de.tum.cit.aet.hephaestus.core.auth.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * Append-only auth-event log. Monthly RANGE-partitioned on {@code occurred_at} and self-managed
 * by pg_partman (create-ahead + 12-month retention, oldest dropped). Records the
 * {@code (account_id, acting_account_id)} pair for every impersonation so every action attributable
 * to an impersonator is reconstructible.
 *
 * <h2>Append-only</h2>
 * Storage-layer append-only is enforced in prod by the {@code trg_auth_event_block_mutation} BEFORE
 * UPDATE OR DELETE trigger (RAISES EXCEPTION) plus a {@code REVOKE UPDATE, DELETE, TRUNCATE} guardrail —
 * the only permitted mutation is the GDPR Art. 17 redaction that NULLs ip_inet/user_agent/details. A
 * superuser bypasses the trigger, but the app never connects as superuser. {@link AuthEventWriter} only
 * INSERTs; partition retention is the only deletion. Backups + WAL archive provide forensic recovery.
 *
 * <h2>IP retention</h2>
 * {@code ip_inet} is stored verbatim for the audit-partition window (12 months) as a security
 * measure (legitimate interest) and dropped when its monthly partition is retired. There is no
 * separate truncation job today.
 *
 * <p>Composite PK {@code (id, occurred_at)} because partitioned tables in Postgres require
 * the partition key in every unique index.
 */
@Entity
@Table(name = "auth_event")
@Getter
@NoArgsConstructor
public class AuthEvent {

    @EmbeddedId
    private Id id;

    /** Account that the event is about (the target of impersonation, the deleted user, …). */
    @Column(name = "account_id")
    @Nullable
    private Long accountId;

    /** Impersonator account id (the {@code act} claim), if the event was performed under impersonation. */
    @Column(name = "acting_account_id")
    @Nullable
    private Long actingAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 48)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 16)
    private Result result;

    @Column(name = "failure_reason", length = 64)
    @Nullable
    private String failureReason;

    @Column(name = "provider_id")
    @Nullable
    private Long providerId;

    @Column(name = "workspace_id")
    @Nullable
    private Long workspaceId;

    @Column(name = "identity_link_id")
    @Nullable
    private Long identityLinkId;

    // Bind the String through the INET JDBC type (see IssuedJwt.ipInet) — otherwise Hibernate emits a
    // varchar and the insert fails against the `inet` column. Nullable for two reasons: the GDPR Art. 17
    // sweep redacts the IP to NULL on erasure (AccountPurger#anonymizeAuditRows), and off-request writes
    // store NULL (AuthEventWriter#captureIp returns null when there is no HttpServletRequest — no sentinel).
    @Column(name = "ip_inet", columnDefinition = "inet")
    @JdbcTypeCode(SqlTypes.INET)
    @Nullable
    private String ipInet;

    @Column(name = "user_agent", length = 512)
    @Nullable
    private String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    @Nullable
    private String details;

    /**
     * Whether the request that produced this event reached its workspace on instance-admin authority
     * rather than membership. Derived by {@link AuthEventLogger} from
     * {@code WorkspaceElevationContext}, never supplied by the producer. {@code false} on every row
     * written before elevation was recorded, so a false here is "not known to be elevated", not
     * "known to be a member".
     */
    @ColumnDefault("false")
    @Column(name = "elevated_via_instance_admin", nullable = false)
    private boolean elevatedViaInstanceAdmin;

    public enum EventType {
        LOGIN,
        LOGIN_FAILED,
        LOGOUT,
        TOKEN_REFRESH,
        JWT_REVOKED,
        IDENTITY_LINKED,
        IDENTITY_UNLINKED,
        IMPERSONATION_BEGIN,
        IMPERSONATION_END,
        ACCOUNT_DELETED,
        EXPORT_REQUESTED,
        APP_ROLE_CHANGED,
        RESEARCH_CONSENT_REVOKED,
        // An instance admin reached a workspace they are not a member of. One marker per access
        // window, not per request — see WorkspaceElevationAuditAdapter.
        WORKSPACE_ELEVATION,
        // Instance LLM catalog: GLOBAL, so the workspace-scoped config trail cannot carry it.
        LLM_CONNECTION_CREATED,
        LLM_CONNECTION_UPDATED,
        LLM_CONNECTION_DELETED,
        LLM_MODEL_CREATED,
        LLM_MODEL_UPDATED,
        LLM_MODEL_DELETED,
        LLM_MODEL_PRICE_CHANGED,
        LLM_MODEL_SHARING_CHANGED,
        LLM_SETTINGS_CHANGED,
        // Instance login providers: GLOBAL, and they change how everyone signs in to this instance.
        LOGIN_PROVIDER_CREATED,
        LOGIN_PROVIDER_UPDATED,
        LOGIN_PROVIDER_DELETED,
        // Instance emergency brake: GLOBAL, so the workspace-scoped config trail cannot carry it.
        SILENT_MODE_CHANGED,
    }

    public enum Result {
        SUCCESS,
        FAILURE,
    }

    /**
     * Factory used by {@link AuthEventWriter} — the only sanctioned construction path.
     * Keeps the entity {@code @Getter}-only (append-only invariant). Business fields come
     * via the {@link AuthEventData} parameter object; request-derived metadata is supplied
     * directly.
     */
    public static AuthEvent create(
            AuthEventData data, Long id, Instant occurredAt, @Nullable String ipInet, @Nullable String userAgent) {
        AuthEvent e = new AuthEvent();
        e.id = new Id(id, occurredAt);
        e.eventType = data.type();
        e.result = data.result();
        e.accountId = data.accountId();
        e.actingAccountId = data.actingAccountId();
        e.failureReason = data.failureReason();
        e.providerId = data.gitProviderId();
        e.workspaceId = data.workspaceId();
        e.identityLinkId = data.identityLinkId();
        e.ipInet = ipInet;
        e.userAgent = userAgent;
        e.details = data.details();
        e.elevatedViaInstanceAdmin = data.elevatedViaInstanceAdmin();
        return e;
    }

    @Embeddable
    @Getter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {

        @Column(name = "id", nullable = false)
        private Long id;

        @Column(name = "occurred_at", nullable = false)
        private Instant occurredAt;
    }
}
