package de.tum.cit.aet.hephaestus.core.audit.spi;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Query-param filters shared by the instance-admin and workspace-scoped audit viewers, bound as a
 * flattened parameter object so each handler stays under the parameter-count budget.
 *
 * <p>{@code entityType} and {@code action} repeat ({@code ?action=CREATED&action=DELETED}) — the facet
 * checklists in the UI are multi-select.
 */
public record ConfigAuditFilterParams(
        @RequestParam(required = false) @Nullable List<ConfigAuditEntityType> entityType,
        @RequestParam(required = false) @Nullable String entityId,
        @RequestParam(required = false) @Nullable String changedKey,
        @RequestParam(required = false) @Nullable List<ConfigAuditAction> action,
        @RequestParam(required = false) @Nullable Long actorId,

        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable
        Instant from,

        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) @Nullable
        Instant to) {
    /** Hard cap so a typo'd or malicious {@code size} can't pull the whole trail in one request. */
    public static final int MAX_PAGE_SIZE = 200;

    public ConfigAuditFilter toFilter() {
        if (entityId != null && (entityType == null || entityType.isEmpty())) {
            // Entity id spaces are per-type, so an unqualified id would match across types by accident.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entityId requires entityType");
        }
        return new ConfigAuditFilter(entityType, entityId, changedKey, action, actorId, from, to);
    }

    /** Clamps rather than rejects an oversized page, matching {@code AuthAuditController}. */
    public static int pageSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }
}
