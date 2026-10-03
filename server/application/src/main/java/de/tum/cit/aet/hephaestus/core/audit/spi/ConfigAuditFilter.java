package de.tum.cit.aet.hephaestus.core.audit.spi;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Optional constraints on an audit query; a null/empty field means "no constraint on that dimension".
 * The workspace is deliberately not here — it is the tenancy boundary on the workspace-scoped read, not
 * a filter, so it cannot be collapsed to "all" by omitting it.
 *
 * <p>{@code entityTypes} and {@code actions} are multi-valued because audit triage is union-shaped
 * ("everything destructive" = CREATED ∪ DELETED). They bind as {@code text[]} for Postgres'
 * {@code = ANY(...)}; an empty selection is the same as no selection and collapses to null, which
 * Hibernate can bind where an empty array or {@code IN ()} could not.
 */
public record ConfigAuditFilter(
        @Nullable List<ConfigAuditEntityType> entityTypes,
        @Nullable String entityId,
        @Nullable String changedKey,
        @Nullable List<ConfigAuditAction> actions,
        @Nullable Long actorId,
        @Nullable Instant from,
        @Nullable Instant to) {
    public String @Nullable [] entityTypeNames() {
        if (entityTypes == null || entityTypes.isEmpty()) {
            return null;
        }
        return entityTypes.stream()
                .flatMap(value -> switch (value) {
                    case PRACTICE_GROUP -> Stream.of("PRACTICE_GROUP", "PRACTICE_AREA");
                    case CURATED_PRACTICE_GROUP -> Stream.of("CURATED_PRACTICE_GROUP", "CURATED_PRACTICE_AREA");
                    default -> Stream.of(value.name());
                })
                .toArray(String[]::new);
    }

    public String @Nullable [] actionNames() {
        return names(actions);
    }

    private static String @Nullable [] names(@Nullable List<? extends Enum<?>> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().map(Enum::name).toArray(String[]::new);
    }
}
