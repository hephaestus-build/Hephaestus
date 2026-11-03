package de.tum.cit.aet.hephaestus.core.audit;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditFilter;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Queries are native for two reasons: the {@code changedKey} filter needs Postgres' array containment
 * operator, which JPQL cannot express and which the GIN index can serve, and the {@code workspace_id}
 * predicate must be literal in the emitted SQL for {@code WorkspaceStatementInspector} to see it.
 *
 * <p>Filter dimensions bind through {@link ConfigAuditFilter} via SpEL rather than one parameter each:
 * bound individually they would blow the method-parameter budget {@code CodeQualityTest} enforces.
 */
@Repository
public interface ConfigAuditEventRepository extends JpaRepository<ConfigAuditEvent, Long> {
    String FILTER_PREDICATES = """
          AND (CAST(:#{#f.entityTypeNames()} AS text[]) IS NULL OR e.entity_type = ANY(CAST(:#{#f.entityTypeNames()} AS text[])))
          AND (CAST(:#{#f.entityId()} AS text) IS NULL OR e.entity_id = CAST(:#{#f.entityId()} AS text))
          AND (CAST(:#{#f.changedKey()} AS text) IS NULL OR e.changed_keys @> ARRAY[CAST(:#{#f.changedKey()} AS text)])
          AND (CAST(:#{#f.actionNames()} AS text[]) IS NULL OR e.action = ANY(CAST(:#{#f.actionNames()} AS text[])))
          AND (CAST(:#{#f.actorId()} AS bigint) IS NULL OR e.actor_account_id = CAST(:#{#f.actorId()} AS bigint))
          AND (CAST(:#{#f.from()} AS timestamptz) IS NULL OR e.occurred_at >= CAST(:#{#f.from()} AS timestamptz))
          AND (CAST(:#{#f.to()} AS timestamptz) IS NULL OR e.occurred_at < CAST(:#{#f.to()} AS timestamptz))
        """;

    /** Reads by keyset, so a deep page costs the same as the first. A null {@code cursorAt} reads the first page. */
    String AFTER_CURSOR = """
          AND (CAST(:cursorAt AS timestamptz) IS NULL OR e.occurred_at < CAST(:cursorAt AS timestamptz)
               OR (e.occurred_at = CAST(:cursorAt AS timestamptz) AND e.id < CAST(:cursorId AS bigint)))
        """;

    String ORDER = " ORDER BY e.occurred_at DESC, e.id DESC";

    /**
     * Workspace-admin history. {@code workspaceId} is a required argument, never part of the filter —
     * it is the tenancy boundary, so it must not be collapsible to "all" by omitting it.
     */
    @Query(
            value = "SELECT * FROM config_audit_event e WHERE e.workspace_id = :workspaceId" + FILTER_PREDICATES
                    + AFTER_CURSOR
                    + ORDER,
            nativeQuery = true)
    List<ConfigAuditEvent> findForWorkspace(
            @Param("workspaceId") Long workspaceId,
            @Param("f") ConfigAuditFilter filter,
            @Param("cursorAt") @Nullable Instant cursorAt,
            @Param("cursorId") @Nullable Long cursorId,
            Pageable pageable);

    @Query(
            value = "SELECT count(*) FROM config_audit_event e WHERE e.workspace_id = :workspaceId" + FILTER_PREDICATES,
            nativeQuery = true)
    long countForWorkspace(@Param("workspaceId") Long workspaceId, @Param("f") ConfigAuditFilter filter);

    /** Instance-admin view; here {@code workspaceId} is an optional filter rather than a boundary. */
    @WorkspaceAgnostic(
            "Instance-admin config audit spans workspaces; gated by hasAuthority('app_admin') on AdminConfigAuditController")
    @Query(
            value = "SELECT * FROM config_audit_event e WHERE (CAST(:workspaceId AS bigint) IS NULL OR"
                    + " e.workspace_id = CAST(:workspaceId AS bigint))"
                    + FILTER_PREDICATES
                    + AFTER_CURSOR
                    + ORDER,
            nativeQuery = true)
    List<ConfigAuditEvent> findForAdmin(
            @Param("workspaceId") @Nullable Long workspaceId,
            @Param("f") ConfigAuditFilter filter,
            @Param("cursorAt") @Nullable Instant cursorAt,
            @Param("cursorId") @Nullable Long cursorId,
            Pageable pageable);

    @WorkspaceAgnostic(
            "Instance-admin config audit spans workspaces; gated by hasAuthority('app_admin') on AdminConfigAuditController")
    @Query(
            value = "SELECT count(*) FROM config_audit_event e WHERE (CAST(:workspaceId AS bigint) IS NULL OR"
                    + " e.workspace_id = CAST(:workspaceId AS bigint))"
                    + FILTER_PREDICATES,
            nativeQuery = true)
    long countForAdmin(@Param("workspaceId") @Nullable Long workspaceId, @Param("f") ConfigAuditFilter filter);

    /**
     * Retention sweep.
     *
     * <p>The cutoff is computed by Postgres, not Java, so that it is the <em>same expression on the
     * same clock</em> as the immutability trigger's DELETE carve-out — {@code now()} is
     * {@code transaction_timestamp()}, so both read one instant. Passing a JVM-computed
     * {@code Instant} instead would make the two disagree under clock skew: with the app ahead of the
     * DB by any amount, the sweep selects rows the trigger still considers inside the window, the
     * RAISE aborts the whole statement, and <em>no</em> rows age out that night — retention failing
     * silently, in the direction that over-retains personal data.
     */
    @WorkspaceAgnostic("Retention ages out rows across every workspace; there is no single tenant to scope it to")
    @Modifying
    @Query(
            value = "DELETE FROM config_audit_event WHERE occurred_at < now() - make_interval(days => :days)",
            nativeQuery = true)
    int deleteOlderThan(@Param("days") int days);
}
