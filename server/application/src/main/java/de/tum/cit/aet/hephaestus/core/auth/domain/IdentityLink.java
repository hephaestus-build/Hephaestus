package de.tum.cit.aet.hephaestus.core.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * A federated-login association between an {@link Account} and an upstream IdP subject.
 * The {@code team_id} column supports multi-instance IdPs (Slack workspaces, Microsoft
 * tenants) where the subject alone is ambiguous.
 *
 * <h2>Lookup discipline</h2>
 * Account lookup at login is <em>always</em> {@code (git_provider, subject, team_id)} via the
 * partial-unique constraint {@code uq_identity_link_provider_subject_team}.
 * Email is recorded ({@link #emailAtSignup}) but never indexed for lookup — defense against
 * the nOAuth (Descope 2023) class of attacks where a mutable provider email claim can be
 * abused to take over an existing account.
 *
 * <h2>Per-account uniqueness</h2>
 * One <em>active</em> identity per (account, provider, team) — enforced by the partial-unique
 * {@code uq_identity_link_active_per_provider} which only counts rows with {@code disabled_at IS NULL}.
 * Multiple identities of the same provider type for the same account are supported (e.g.
 * personal GitLab + work GitLab) but each must carry a distinct {@link #providerId} (the
 * scalar FK to the integration-owned {@code identity_provider} row — see that field's doc for why it
 * is not a JPA {@code @ManyToOne} association).
 */
@Entity
@Table(name = "identity_link")
@Getter
@Setter
@NoArgsConstructor
public class IdentityLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /**
     * FK (by id) to the {@code identity_provider} row owned by the {@code integration} module.
     * Stored as a plain scalar rather than a JPA {@code @ManyToOne IdentityProvider} association so
     * that {@code core.auth} does not import the integration entity — that import would invert
     * the bounded-context dependency direction ({@code integration → core}). Resolution of a
     * {@code registrationId} to this id happens through the
     * {@code de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry} SPI, implemented in
     * {@code integration}.
     */
    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    /** IdP-stable user id (GitHub numeric id, GitLab numeric id, Slack {@code U...}). */
    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    /** Slack workspace id ({@code T...}), Microsoft tenant id; NULL for single-tenant IdPs. */
    @Column(name = "team_id", length = 255)
    @Nullable
    private String teamId;

    /** Cached actor mirror, if known. It is not proof of ownership or authorization. */
    @Column(name = "external_actor_id")
    @Nullable
    private Long externalActorId;

    @Column(name = "username_at_signup", length = 255)
    @Nullable
    private String usernameAtSignup;

    /**
     * Forensic only — captured from the IdP claim at link time. Never indexed for
     * authentication lookup (see class doc).
     */
    @Column(name = "email_at_signup", length = 320)
    @Nullable
    private String emailAtSignup;

    @Column(name = "display_name", length = 255)
    @Nullable
    private String displayName;

    @Column(name = "avatar_url", columnDefinition = "text")
    @Nullable
    private String avatarUrl;

    @Column(name = "profile_url", columnDefinition = "text")
    @Nullable
    private String profileUrl;

    @CreationTimestamp
    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "linked_via", nullable = false, length = 16)
    @ColumnDefault("'OAUTH_LOGIN'")
    private LinkedVia linkedVia = LinkedVia.OAUTH_LOGIN;

    @Column(name = "last_login_at")
    @Nullable
    private Instant lastLoginAt;

    /**
     * Set when refresh fails with {@code invalid_grant}, the user revokes access on the IdP
     * side, or an admin disables the link. Not a hard delete — we keep the row for audit /
     * activity-graph integrity.
     */
    @Column(name = "disabled_at")
    @Nullable
    private Instant disabledAt;

    public enum LinkedVia {
        /** Signed in with this provider. */
        OAUTH_LOGIN,
        /** User added this provider in settings while already authenticated (re-login flow). */
        MANUAL_LINK,
    }
}
