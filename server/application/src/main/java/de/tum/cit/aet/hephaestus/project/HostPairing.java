package de.tum.cit.aet.hephaestus.project;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
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
 * A host's pairing request (ADR 0166). It precedes any authority: it holds no workspace, team or content, only the
 * hash of the typed code, the host's S256 challenge and, once approved, the approving account. It is deleted at
 * redemption, refusal or expiry. Only a {@link HostGrant} authorizes anything.
 */
@Entity
@Table(
        name = "host_pairing",
        uniqueConstraints = @UniqueConstraint(name = "uk_host_pairing_code", columnNames = "code_hash"),
        indexes = @Index(name = "idx_host_pairing_approved_account", columnList = "approved_account_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HostPairing {

    /** Refusal, expiry and redemption delete the row, so approval is the one transition it records. */
    public enum Status implements Lifecycle<Status> {
        PENDING,
        APPROVED;

        @Override
        public boolean canTransitionTo(Status next) {
            return this == PENDING && next == APPROVED;
        }
    }

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "code_challenge", nullable = false, length = 128)
    private String codeChallenge;

    @Enumerated(EnumType.STRING)
    @Column(name = "host_kind", nullable = false, length = 16)
    private HostKind hostKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status;

    @Column(name = "approved_account_id")
    private @Nullable Long approvedAccountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
