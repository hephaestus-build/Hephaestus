package de.tum.cit.aet.hephaestus.core.audit.spi;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One audit row, flattened for a viewer. Crosses the module boundary, so it carries no entity.
 *
 * @param actor        resolved identity of {@code actorAccountId}; null for SYSTEM rows or once the
 *                     account is gone. Read together with {@code actorKind} — that is what keeps
 *                     "a system did this" distinct from "we no longer know who did this".
 * @param actingActor  resolved impersonator, present only for {@link ConfigAuditActorKind#IMPERSONATED}
 * @param changedKeys  dot-paths that differ between {@code oldValue} and {@code newValue}
 * @param elevatedViaInstanceAdmin whether the actor reached this workspace by instance-admin elevation
 */
public record ConfigAuditEntryViewDTO(
        Long id,
        Instant occurredAt,
        @Nullable Long workspaceId,
        ConfigAuditEntityType entityType,
        String entityId,
        ConfigAuditAction action,
        ConfigAuditActorKind actorKind,
        @Schema(requiredMode = RequiredMode.REQUIRED) boolean elevatedViaInstanceAdmin,
        @Nullable Long actorAccountId,
        @Nullable Long actingAccountId,
        @Nullable ConfigAuditActorRefDTO actor,
        @Nullable ConfigAuditActorRefDTO actingActor,
        List<String> changedKeys,
        @Nullable String oldValue,
        @Nullable String newValue) {}
