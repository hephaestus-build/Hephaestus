package de.tum.cit.aet.hephaestus.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A team grant (ADR 0166): what a paired editor host holds to submit and read one team project's own reviews for one
 * account. It is its own credential, never an account session. The row exists only while the account is a member of
 * the team: leaving the team or the workspace, the team's removal and the workspace's purge delete it.
 */
@Entity
@Table(
        name = "host_grant",
        uniqueConstraints = @UniqueConstraint(name = "uk_host_grant_token", columnNames = "token_hash"),
        indexes = {
            @Index(name = "idx_host_grant_account", columnList = "account_id"),
            @Index(name = "idx_host_grant_member", columnList = "team_id, account_id"),
            @Index(name = "idx_host_grant_team", columnList = "team_id, workspace_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HostGrant {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "host_kind", nullable = false, length = 16)
    private HostKind hostKind;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private @Nullable Instant revokedAt;
}
