package de.tum.cit.aet.hephaestus.core.audit;

import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditAction;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditActorKind;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditEntityType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One admin configuration change: who changed which control, when, from what to what.
 *
 * <p>Append-only, enforced by a {@code prod}-context trigger that blocks UPDATE, DELETE and TRUNCATE at
 * the storage layer, with two carve-outs: erasure may set the actor references and the snapshots to
 * NULL (per column, so an FK's {@code ON DELETE SET NULL} agrees with the trigger rather than
 * deadlocking against it), and retention may DELETE past the window. It holds against application bugs,
 * not against an operator with database access.
 *
 * <p>{@code workspace_id} is null only for instance-scoped events; a CHECK constraint ties which
 * entity types may leave it null, so a workspace resource cannot lose its workspace.
 */
@Entity
@Table(name = "config_audit_event")
@Getter
@NoArgsConstructor
public class ConfigAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "workspace_id")
    @Nullable
    private Long workspaceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_kind", nullable = false, length = 16)
    private ConfigAuditActorKind actorKind;

    /** Signed-in account, or the impersonation subject. Null for {@code SYSTEM}, or after erasure. */
    @Column(name = "actor_account_id")
    @Nullable
    private Long actorAccountId;

    /** Impersonator ({@code act} claim) when {@code actorKind == IMPERSONATED}. */
    @Column(name = "acting_account_id")
    @Nullable
    private Long actingAccountId;

    /**
     * Whether the actor reached this row's workspace on instance-admin authority rather than
     * membership. Resolved by {@link ConfigAuditActor} from the same request-local decision the
     * {@code auth_event} trail reads, so the two viewers cannot disagree about one action.
     * {@code false} on every row written before elevation was recorded, so a false here is "not known
     * to be elevated", not "known to be a member".
     */
    @ColumnDefault("false")
    @Column(name = "elevated_via_instance_admin", nullable = false)
    private boolean elevatedViaInstanceAdmin;

    @Convert(converter = ConfigAuditEntityTypeConverter.class)
    @Column(name = "entity_type", nullable = false, length = 48)
    private ConfigAuditEntityType entityType;

    @Column(name = "entity_id", nullable = false, length = 64)
    private String entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private ConfigAuditAction action;

    /**
     * Dot-paths whose value differs between the snapshots (see {@code ConfigAuditDiff}). Persisted
     * rather than derived because Postgres has no built-in jsonb diff, and because per-control history
     * must filter server-side: several controls live in one entity, so a whole page may contain zero
     * rows matching the requested control, and a client filtering after paging cannot know whether to
     * fetch more.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "changed_keys", nullable = false, columnDefinition = "text[]")
    private String[] changedKeys;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb")
    @Nullable
    private String oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb")
    @Nullable
    private String newValue;

    /** Only sanctioned construction path; keeps the entity {@code @Getter}-only (append-only invariant). */
    static ConfigAuditEvent create(
            Instant occurredAt,
            @Nullable Long workspaceId,
            ConfigAuditActor actor,
            ConfigAuditEntityType entityType,
            String entityId,
            ConfigAuditAction action,
            List<String> changedKeys,
            @Nullable String oldValue,
            @Nullable String newValue) {
        ConfigAuditEvent e = new ConfigAuditEvent();
        e.occurredAt = occurredAt;
        e.workspaceId = workspaceId;
        e.actorKind = actor.kind();
        e.actorAccountId = actor.accountId();
        e.actingAccountId = actor.actingAccountId();
        e.elevatedViaInstanceAdmin = actor.elevatedViaInstanceAdmin();
        e.entityType = entityType;
        e.entityId = entityId;
        e.action = action;
        e.changedKeys = changedKeys.toArray(String[]::new);
        e.oldValue = oldValue;
        e.newValue = newValue;
        return e;
    }

    public List<String> changedKeyList() {
        return changedKeys == null ? List.of() : List.of(changedKeys);
    }
}
