package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

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
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;

/**
 * One live sandbox of one subject, and the owner of its {@code SandboxClaim}. Only {@link AgentSandboxes} and the
 * reaper write it, through conditional statements on the database clock; this entity only maps the table.
 *
 * <p>{@code last_used_at} is the time of the sandbox's latest lease of a run, or of its first call to the gateway, or of
 * its request before that. The reaper reads it for the idle limits of the capacity home.
 */
@Entity
@Table(
        name = "agent_sandbox",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_agent_sandbox_workspace",
                    columnNames = {"id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_agent_sandbox_claim",
                    columnNames = {"claim_name"})
        },
        indexes = @Index(name = "idx_agent_sandbox_state", columnList = "state, last_used_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class AgentSandbox {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    @Column(name = "subject_key", nullable = false, updatable = false, length = 96)
    private String subjectKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 16)
    private AgentSandboxKind kind;

    @Column(name = "claim_name", nullable = false, updatable = false, length = 63)
    private String claimName;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'REQUESTED'")
    @Column(name = "state", nullable = false, length = 12)
    private AgentSandboxState state;

    @ColumnDefault("now()")
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ColumnDefault("now()")
    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "shutdown_at", nullable = false, updatable = false)
    private Instant shutdownAt;
}
