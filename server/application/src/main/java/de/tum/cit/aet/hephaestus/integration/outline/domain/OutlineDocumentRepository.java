package de.tum.cit.aet.hephaestus.integration.outline.domain;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository for the mirrored {@link OutlineDocument} rows — the only place the agent read path resolves
 * Outline content from; nothing here reaches the Outline API. Every finder carries the {@code workspace_id}
 * predicate the tenancy {@code StatementInspector} requires.
 */
public interface OutlineDocumentRepository extends JpaRepository<OutlineDocument, Long> {
    /** AI readers exclude controlled native creator, editor and collaborator identities. Mirror writes do not. */
    String PERSON_PROJECTION_GUARD = """
            AND NOT EXISTS (SELECT 1 FROM PersonSuppression s, IdentityProvider p, Connection c
              WHERE s.providerId=p.id AND c.id=d.connectionId AND c.workspace.id=:workspaceId
                AND p.type=de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType.OUTLINE
                AND p.serverUrl=FUNCTION('jsonb_extract_path_text',c.config,'serverUrl') AND s.teamKey=''
                AND (s.subject=d.createdBySubject OR s.subject=d.updatedBySubject
                  OR FUNCTION('jsonb_exists',d.collaboratorSubjects,s.subject)=TRUE))
            """;

    String PERSON_PROJECTION_SQL_GUARD = """
            AND NOT EXISTS (SELECT 1 FROM person_suppression s
              JOIN identity_provider p ON p.id=s.provider_id
              JOIN connection c ON c.id=d.connection_id AND c.workspace_id=:workspaceId
              WHERE p.type='OUTLINE' AND p.server_url=c.config->>'serverUrl' AND s.team_key=''
                AND (s.subject=d.created_by_subject OR s.subject=d.updated_by_subject
                  OR jsonb_exists(d.collaborator_subjects,s.subject)))
            """;

    long deleteByWorkspaceId(Long workspaceId);

    /** Live (non-tombstoned) mirrored documents in the workspace — the admin status figure. */
    long countByWorkspaceIdAndDeletedAtIsNull(Long workspaceId);

    /** One collection's live (non-tombstoned) document count — the single-row admin DTO figure. */
    long countByWorkspaceIdAndConnectionIdAndCollectionIdAndDeletedAtIsNull(
            Long workspaceId, Long connectionId, String collectionId);

    /** Live document counts by collection — one query for the whole admin list. Each element is {@code [collectionId, count]}. */
    @Query("SELECT d.collectionId, COUNT(d) FROM OutlineDocument d WHERE d.workspaceId = :workspaceId "
            + "AND d.connectionId = :connectionId AND d.deletedAt IS NULL GROUP BY d.collectionId")
    List<Object[]> countLiveByCollection(
            @Param("workspaceId") long workspaceId, @Param("connectionId") long connectionId);

    /** Hard-deletes one collection's mirrored rows: removing a collection erases the bodies, it does not tombstone them. */
    long deleteByWorkspaceIdAndConnectionIdAndCollectionId(Long workspaceId, Long connectionId, String collectionId);

    /** One mirrored document by its Outline id — the row a write transaction re-reads before mutating it. */
    Optional<OutlineDocument> findByWorkspaceIdAndConnectionIdAndDocumentId(
            Long workspaceId, Long connectionId, String documentId);

    /**
     * The body-free diff set the reconcile runs against. {@code LENGTH(body_markdown)} is evaluated in Postgres,
     * so no Markdown body crosses the wire or lands on the heap.
     */
    @Query("SELECT new de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentSnapshot("
            + "d.id, d.documentId, d.collectionId, d.collectionSlug, d.parentDocumentId, d.title, d.slug, "
            + "d.archivedAt, d.outlineUpdatedAt, d.contentHash, d.deletedAt, LENGTH(d.bodyMarkdown), d.version) "
            + "FROM OutlineDocument d "
            + "WHERE d.workspaceId = :workspaceId AND d.connectionId = :connectionId")
    List<OutlineDocumentSnapshot> findSnapshotsByWorkspaceIdAndConnectionId(
            @Param("workspaceId") long workspaceId, @Param("connectionId") long connectionId);

    /** One collection's mirrored rows, body-free — the scope of a collection-delete tombstone sweep. */
    @Query("SELECT new de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentSnapshot("
            + "d.id, d.documentId, d.collectionId, d.collectionSlug, d.parentDocumentId, d.title, d.slug, "
            + "d.archivedAt, d.outlineUpdatedAt, d.contentHash, d.deletedAt, LENGTH(d.bodyMarkdown), d.version) "
            + "FROM OutlineDocument d "
            + "WHERE d.workspaceId = :workspaceId AND d.connectionId = :connectionId "
            + "AND d.collectionId = :collectionId")
    List<OutlineDocumentSnapshot> findSnapshotsByCollectionId(
            @Param("workspaceId") long workspaceId,
            @Param("connectionId") long connectionId,
            @Param("collectionId") String collectionId);

    /** One mirrored document's snapshot — the webhook targeted-refresh routing lookup (no body loaded). */
    @Query("SELECT new de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentSnapshot("
            + "d.id, d.documentId, d.collectionId, d.collectionSlug, d.parentDocumentId, d.title, d.slug, "
            + "d.archivedAt, d.outlineUpdatedAt, d.contentHash, d.deletedAt, LENGTH(d.bodyMarkdown), d.version) "
            + "FROM OutlineDocument d "
            + "WHERE d.workspaceId = :workspaceId AND d.connectionId = :connectionId "
            + "AND d.documentId = :documentId")
    Optional<OutlineDocumentSnapshot> findSnapshotByDocumentId(
            @Param("workspaceId") long workspaceId,
            @Param("connectionId") long connectionId,
            @Param("documentId") String documentId);

    /** A bounded page of the workspace's mirrored documents: live rows first, most-recently-updated first within each group. */
    @Query("SELECT d FROM OutlineDocument d WHERE d.workspaceId = :workspaceId "
            + PERSON_PROJECTION_GUARD
            + "ORDER BY d.deletedAt ASC NULLS FIRST, d.outlineUpdatedAt DESC NULLS LAST, d.id ASC")
    List<OutlineDocument> findForProjection(@Param("workspaceId") long workspaceId, Pageable pageable);

    /**
     * The linked-document lookup: matches reference tokens against either the document id or the slug, so a raw id
     * or a URL's trailing segment both resolve.
     */
    @Query("SELECT d FROM OutlineDocument d WHERE d.workspaceId = :workspaceId "
            + "AND (d.documentId IN :refs OR d.slug IN :refs) " + PERSON_PROJECTION_GUARD)
    List<OutlineDocument> findByWorkspaceIdAndReferenceIn(
            @Param("workspaceId") long workspaceId, @Param("refs") Collection<String> refs);

    @Query("SELECT d FROM OutlineDocument d WHERE d.workspaceId=:workspaceId AND d.id=:documentId "
            + PERSON_PROJECTION_GUARD)
    Optional<OutlineDocument> findByWorkspaceIdAndIdForProjection(
            @Param("workspaceId") long workspaceId, @Param("documentId") long documentId);

    /**
     * The character bound the searchable tsvector is built over. Postgres refuses a tsvector over 1 MB and
     * {@code body_markdown} is unbounded, so without this truncation one oversized document makes the query ERROR
     * and permanently breaks retrieval for the whole workspace.
     *
     * <p>Documentation only — the value is inlined in {@link #searchByRelevance}'s SQL, which must stay
     * byte-for-byte equivalent to the {@code ix_outline_document_fts} index expression or the index is silently
     * dropped.
     */
    int FTS_BODY_CHAR_LIMIT = 900_000;

    /**
     * Live documents ranked by full-text relevance to {@code query} (websearch syntax) — the retrieval path
     * behind {@code OutlineDocumentSelector}. The {@code simple} FTS config keeps matching language-neutral
     * (no stemming, no stopword list) for mixed-language wikis. Tombstoned and body-evicted rows are excluded:
     * there is no body to rank against.
     *
     * <p>The {@code to_tsvector(...)} expression is repeated verbatim in the WHERE clause, in {@code ts_rank},
     * and in the {@code ix_outline_document_fts} GIN index — all three must match exactly or Postgres falls
     * back to rebuilding a tsvector per row per call. See {@link #FTS_BODY_CHAR_LIMIT} for why the
     * {@code left(...)} bound is a correctness guard and not an optimisation.
     */
    @Query(
            value = "SELECT d.* FROM outline_document d WHERE d.workspace_id = :workspaceId "
                    + PERSON_PROJECTION_SQL_GUARD
                    + "AND deleted_at IS NULL AND body_markdown IS NOT NULL "
                    + "AND to_tsvector('simple', coalesce(title, '') || ' ' || left(coalesce(body_markdown, ''), 900000)) "
                    + "@@ websearch_to_tsquery('simple', :query) "
                    + "ORDER BY ts_rank(to_tsvector('simple', coalesce(title, '') || ' ' || "
                    + "left(coalesce(body_markdown, ''), 900000)), "
                    + "websearch_to_tsquery('simple', :query)) DESC, outline_updated_at DESC NULLS LAST, id ASC "
                    + "LIMIT :limit",
            nativeQuery = true)
    List<OutlineDocument> searchByRelevance(
            @Param("workspaceId") long workspaceId, @Param("query") String query, @Param("limit") int limit);

    /**
     * Staleness drop: delete every tombstoned row whose {@code deleted_at} is older than {@code cutoff} for
     * one workspace — the hard ceiling on how long a vanished document lingers as a marker. Idempotent.
     */
    long deleteByWorkspaceIdAndDeletedAtBefore(Long workspaceId, Instant cutoff);

    /**
     * Total size (in bytes) of the workspace's mirrored Markdown bodies — the figure the size cap is
     * enforced against. {@code octet_length} counts UTF-8 bytes, not characters, so the cap holds exactly
     * for multibyte content instead of under-counting it. Native for the {@code SUM(octet_length(...))}
     * aggregate; coalesces an all-null/empty workspace to 0.
     */
    @Query(
            value = "SELECT COALESCE(SUM(octet_length(body_markdown)), 0) FROM outline_document "
                    + "WHERE workspace_id = :workspaceId",
            nativeQuery = true)
    long sumBodySizeByWorkspaceId(@Param("workspaceId") long workspaceId);

    /**
     * Eviction candidates: {@code [id, body byte size]} for the {@code limit} rows that still hold a body and
     * were exported longest ago (a never-exported row evicts before a recently-refreshed one). The byte size
     * ({@code octet_length}) matches the unit {@link #sumBodySizeByWorkspaceId} enforces the cap in, so the
     * caller's running subtraction stays byte-accurate. The service walks this page, nulling bodies until the
     * workspace is back under the cap, and re-queries for the next page if it is not — an evicted row drops
     * out of this result set, so the pages advance on their own.
     *
     * <p>The {@code LIMIT} is a correctness bound, not a page size: {@link #evictBodies} binds one parameter
     * per id, and Postgres caps a statement at 65 535 bind parameters (the planner degrades long before that),
     * so an unbounded candidate list over a large over-cap mirror would exceed it. Native for the
     * {@code octet_length(...)} projection.
     */
    @Query(
            value = "SELECT id, octet_length(body_markdown) FROM outline_document "
                    + "WHERE workspace_id = :workspaceId AND body_markdown IS NOT NULL "
                    + "ORDER BY last_materialized_at ASC NULLS FIRST, id ASC LIMIT :limit",
            nativeQuery = true)
    List<Object[]> findEvictionCandidates(@Param("workspaceId") long workspaceId, @Param("limit") int limit);

    /**
     * Frees mirrored bodies for the size cap: null {@code body_markdown} and stamp
     * {@code body_evicted_at} for the given rows. {@code content_hash} is deliberately KEPT so the
     * unchanged-check still holds — an evicted-but-unmodified document is not re-exported (and
     * re-evicted) on every pass; the body comes back only when upstream changes or a targeted refresh
     * asks for it. Returns rows affected.
     *
     * <p>Callers must chunk {@code ids} (the retention service does, at
     * {@code OutlineMirrorRetentionService#EVICTION_BATCH_SIZE}): every id is one bind parameter in the
     * {@code IN} list.
     */
    @Modifying
    @Transactional
    @Query(
            value = "UPDATE outline_document SET body_markdown = NULL, body_evicted_at = now() "
                    + "WHERE workspace_id = :workspaceId AND id IN (:ids)",
            nativeQuery = true)
    int evictBodies(@Param("workspaceId") long workspaceId, @Param("ids") List<Long> ids);

    /**
     * The few columns it takes to name a mirrored document on a read surface that has only its id. The
     * collection is named as Outline shows it, never by its url id, which is a random slug nobody reads;
     * a collection whose name was never captured leaves it null.
     */
    @Query("SELECT d.id AS id, d.title AS title, c.name AS collectionName FROM OutlineDocument d "
            + "LEFT JOIN OutlineCollection c ON c.workspaceId = d.workspaceId AND c.connectionId = d.connectionId "
            + "AND c.collectionId = d.collectionId "
            + "WHERE d.workspaceId = :workspaceId AND d.id IN (:ids)")
    List<DocumentLabel> findLabels(@Param("workspaceId") long workspaceId, @Param("ids") Collection<Long> ids);

    interface DocumentLabel {
        Long getId();

        @Nullable
        String getTitle();

        @Nullable
        String getCollectionName();
    }

    interface PersonSourceIdentityRow {
        long getProviderId();

        String getSubject();
    }

    @WorkspaceAgnostic("Instance-admin person selection pins an exact provider and native identity across workspaces")
    @Query(value = """
        SELECT DISTINCT d.id
                FROM outline_document d JOIN connection c ON c.id=d.connection_id AND c.workspace_id=d.workspace_id
                JOIN identity_provider p ON p.type='OUTLINE' AND p.server_url=c.config->>'serverUrl'
                CROSS JOIN LATERAL (
                    SELECT d.created_by_subject AS subject UNION SELECT d.updated_by_subject
                    UNION SELECT jsonb_array_elements_text(COALESCE(d.collaborator_subjects,'[]'::jsonb))
                ) authors
                 WHERE p.id=:providerId AND authors.subject=:subject
        """, nativeQuery = true)
    List<Long> findPersonSourceIds(@Param("providerId") long providerId, @Param("subject") String subject);

    @Query(value = """
        SELECT DISTINCT p.id AS providerId,authors.subject AS subject
                FROM outline_document d JOIN connection c ON c.id=d.connection_id AND c.workspace_id=d.workspace_id
                JOIN identity_provider p ON p.type='OUTLINE' AND p.server_url=c.config->>'serverUrl'
                CROSS JOIN LATERAL (
                    SELECT d.created_by_subject AS subject UNION SELECT d.updated_by_subject
                    UNION SELECT jsonb_array_elements_text(COALESCE(d.collaborator_subjects,'[]'::jsonb))
                ) authors
                 WHERE d.workspace_id=:workspaceId AND d.id=:artifactId AND authors.subject IS NOT NULL AND authors.subject<>''
        """, nativeQuery = true)
    List<PersonSourceIdentityRow> findPersonSourceIdentities(
            @Param("workspaceId") long workspaceId, @Param("artifactId") long artifactId);
}
