package de.tum.cit.aet.hephaestus.core.auth.audit;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Insert-mostly repository for {@link AuthEvent}. The table is append-only at the SQL-grant
 * level in non-test environments; this repository never exposes update / delete beyond what
 * {@link JpaRepository} provides (those methods are simply never called on the auth path).
 */
@Repository
@WorkspaceAgnostic(
        "Auth audit events are account/system-scoped; workspace is an optional reference, not a tenant scope")
public interface AuthEventRepository extends JpaRepository<AuthEvent, AuthEvent.Id> {
    /**
     * Auth events about the given account at or after {@code since}, newest first. Backs the GDPR
     * data-export's 12-month auth-event window. Uses the
     * {@code ix_auth_event_account_occurred (account_id, occurred_at DESC)} partial index.
     */
    @Query("""
            SELECT e FROM AuthEvent e
            WHERE e.accountId = :accountId AND e.id.occurredAt >= :since
            ORDER BY e.id.occurredAt DESC, e.id.id DESC
        """)
    List<AuthEvent> findByAccountSince(@Param("accountId") Long accountId, @Param("since") Instant since);

    /**
     * Admin audit viewer: auth events newest-first, optionally narrowed by subject account and/or
     * event type (both null = unfiltered). Backs the read-only {@code GET /admin/audit} viewer.
     *
     * <p>Reads by keyset on {@code occurred_at DESC, id DESC}, backed by the {@code ix_auth_event_occurred}
     * index, so a deep page costs the same as the first. A null {@code cursorAt} reads the first page.
     */
    @Query("""
            SELECT e FROM AuthEvent e
            WHERE (:accountId IS NULL OR e.accountId = :accountId)
              AND (:actingAccountId IS NULL OR e.actingAccountId = :actingAccountId)
              AND (:eventTypes IS NULL OR e.eventType IN :eventTypes)
              AND (:results IS NULL OR e.result IN :results)
              AND (CAST(:from AS Instant) IS NULL OR e.id.occurredAt >= :from)
              AND (CAST(:to AS Instant) IS NULL OR e.id.occurredAt < :to)
              AND (CAST(:cursorAt AS Instant) IS NULL OR e.id.occurredAt < :cursorAt
                   OR (e.id.occurredAt = :cursorAt AND e.id.id < :cursorId))
            ORDER BY e.id.occurredAt DESC, e.id.id DESC
        """)
    List<AuthEvent> findForAdmin(
            @Param("accountId") @Nullable Long accountId,
            @Param("actingAccountId") @Nullable Long actingAccountId,
            @Param("eventTypes") @Nullable List<AuthEvent.EventType> eventTypes,
            @Param("results") @Nullable List<AuthEvent.Result> results,
            @Param("from") @Nullable Instant from,
            @Param("to") @Nullable Instant to,
            @Param("cursorAt") @Nullable Instant cursorAt,
            @Param("cursorId") @Nullable Long cursorId,
            Pageable pageable);

    /** How many auth events the admin audit viewer's filter matches. */
    @Query("""
            SELECT count(e) FROM AuthEvent e
            WHERE (:accountId IS NULL OR e.accountId = :accountId)
              AND (:actingAccountId IS NULL OR e.actingAccountId = :actingAccountId)
              AND (:eventTypes IS NULL OR e.eventType IN :eventTypes)
              AND (:results IS NULL OR e.result IN :results)
              AND (CAST(:from AS Instant) IS NULL OR e.id.occurredAt >= :from)
              AND (CAST(:to AS Instant) IS NULL OR e.id.occurredAt < :to)
        """)
    long countForAdmin(
            @Param("accountId") @Nullable Long accountId,
            @Param("actingAccountId") @Nullable Long actingAccountId,
            @Param("eventTypes") @Nullable List<AuthEvent.EventType> eventTypes,
            @Param("results") @Nullable List<AuthEvent.Result> results,
            @Param("from") @Nullable Instant from,
            @Param("to") @Nullable Instant to);
}
