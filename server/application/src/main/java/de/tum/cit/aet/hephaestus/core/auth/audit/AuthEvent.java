package de.tum.cit.aet.hephaestus.core.auth.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.io.Serial;
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
 * Monthly partition retention is the only deletion; authorized erasure can redact personal fields.
 * The composite key includes occurred_at because PostgreSQL requires the partition key in every unique index.
 */
@Entity
@Table(name = "auth_event")
@Getter
@NoArgsConstructor
public class AuthEvent {

    @EmbeddedId
    private Id id;

    @Column(name = "account_id")
    @Nullable
    private Long accountId;

    /** The SCM user a {@code USER_VIEW} disclosed, who may have no account. */
    @Column(name = "viewed_actor_id")
    @Nullable
    private Long viewedActorId;

    @Column(name = "viewed_account_id")
    private @Nullable Long viewedAccountId;

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

    @Column(name = "identity_id")
    @Nullable
    private Long identityId;

    // Bind the String through the INET JDBC type (see IssuedJwt.ipInet) — otherwise Hibernate emits
    // a
    // varchar and the insert fails against the `inet` column. Nullable for two reasons: the GDPR
    // Art. 17
    // sweep redacts the IP to NULL on erasure (AccountPurger#anonymizeAuditRows), and off-request
    // writes
    // store NULL (AuthEventWriter#captureIp returns null when there is no HttpServletRequest — no
    // sentinel).
    @Column(name = "ip_inet", columnDefinition = "inet")
    @JdbcTypeCode(SqlTypes.INET)
    @Nullable
    private String ipInet;

    @Column(name = "user_agent", length = 512)
    @Nullable
    private String userAgent;

    @Column(name = "details", columnDefinition = "text")
    @Nullable
    private String details;

    // False means elevation was not recorded; it does not prove membership authority.
    @ColumnDefault("false")
    @Column(name = "elevated_via_instance_admin", nullable = false)
    private boolean elevatedViaInstanceAdmin;

    public enum EventType {
        USER_VIEW,
        LOGIN,
        PASSKEY_CHANGED,
        PASSKEY_VERIFIED,
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
        PERSON_DATA_PREVIEWED,
        PERSON_DATA_EXPORTED,
        PERSON_DATA_ERASURE_REQUESTED,
        APP_ROLE_CHANGED,
        RESEARCH_CONSENT_REVOKED,
        // An instance admin reached a workspace they are not a member of. One marker per access
        // window, not per request — see WorkspaceElevationAuditAdapter.
        WORKSPACE_ELEVATION,
        INSTALLATION_EVENT_REPLAYED,
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
        PUBLIC_ACTIVITY_CHANGED,
        // A pseudonymous research dataset left the instance, or its downstream removal was attested.
        // Recorded before the bytes are sent, so it proves the release, not that a client received it.
        RESEARCH_DATASET_RELEASED,
        RESEARCH_REMOVAL_RECORDED,
    }

    public enum Result {
        SUCCESS,
        FAILURE,
    }

    /**
     * Factory used by {@link AuthEventWriter} — the only sanctioned construction path. Keeps the
     * entity {@code @Getter}-only (append-only invariant). Business fields come via the {@link
     * AuthEventData} parameter object; request-derived metadata is supplied directly.
     */
    public static AuthEvent create(
            AuthEventData data, Long id, Instant occurredAt, @Nullable String ipInet, @Nullable String userAgent) {
        AuthEvent e = new AuthEvent();
        e.id = new Id(id, occurredAt);
        e.eventType = data.type();
        e.result = data.result();
        e.accountId = data.accountId();
        e.viewedActorId = data.viewedActorId();
        e.viewedAccountId = data.viewedAccountId();
        e.actingAccountId = data.actingAccountId();
        e.failureReason = data.failureReason();
        e.providerId = data.gitProviderId();
        e.workspaceId = data.workspaceId();
        e.identityId = data.identityId();
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

        @Serial
        private static final long serialVersionUID = 1L;

        @Column(name = "id", nullable = false)
        private Long id;

        @Column(name = "occurred_at", nullable = false)
        private Instant occurredAt;
    }
}
