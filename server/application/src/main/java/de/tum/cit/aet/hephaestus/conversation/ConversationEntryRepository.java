package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ConversationEntryRepository extends JpaRepository<ConversationEntry, UUID> {

    /** One entry without its payload: what the context path orders, selects and marks. */
    interface Head {
        UUID getId();

        UUID getConversationId();

        Long getSeq();

        String getKind();

        @Nullable
        UUID getTurnId();

        /** The stored Pi entry's id, or {@code null} when Pi never wrote the entry. */
        @Nullable
        String getPiId();

        @Nullable
        String getFirstKept();

        /** Redacted without a Pi skeleton ({@link ConversationRedaction}). */
        Boolean getOmitted();
    }

    interface EntryRef {
        UUID getId();

        Long getWorkspaceId();

        UUID getConversationId();
    }

    /** How many messages a conversation holds after a sequence number, and when the newest was written. */
    interface MessageSpan {
        long getCount();

        @Nullable
        Instant getNewest();
    }

    /**
     * The entries the app can show, as a predicate on {@code e}: the developer's messages and weekly digests, deleted
     * ones included, and Hephaestus's messages that have text or call a link tool ({@code link_result}, {@code link_problem},
     * {@code show_feedback}). Tool results, summaries, system entries and erased Hephaestus messages stay out. A Hephaestus message
     * without text whose calls show nothing matches, but the app does not show it ({@code ConversationTimeline.shows}).
     */
    String SHOWN = """
             e.kind IN ('USER', 'ANSWER', 'DIGEST')
             AND NOT EXISTS (SELECT 1 FROM conversation_turn t
                 WHERE (t.id,t.workspace_id)=(e.turn_id,e.workspace_id)
                   AND t.surface='SYSTEM' AND t.status<>'DONE')
             AND (e.kind <> 'ANSWER' OR (e.redacted_at IS NULL AND (e.text <> '' OR EXISTS (
                 SELECT 1 FROM jsonb_array_elements(CASE WHEN jsonb_typeof(e.payload -> 'message' -> 'content') = 'array'
                     THEN e.payload -> 'message' -> 'content' ELSE '[]'::jsonb END) AS block(value)
                 WHERE block.value ->> 'type' = 'toolCall'
                   AND block.value ->> 'name' IN ('link_result', 'link_problem', 'show_feedback')))))
            """;

    /** A thread on a message: how many messages the app shows in it, and the newest one. */
    interface ThreadSummary {
        UUID getConversationId();

        UUID getParentEntryId();

        long getReplies();

        @Nullable
        Instant getLastReplyAt();

        /** {@code 0} for a thread that shows no message yet. */
        long getLatestSeq();
    }

    Optional<ConversationEntry> findByIdAndWorkspaceId(UUID id, Long workspaceId);

    /** The same visibility predicate for a live entry as for a page or search. */
    @Query(
            value =
                    "SELECT EXISTS(SELECT 1 FROM conversation_entry e WHERE e.workspace_id=:workspaceId AND e.id=:id AND "
                            + SHOWN + ")",
            nativeQuery = true)
    boolean isShown(@Param("workspaceId") long workspaceId, @Param("id") UUID id);

    /** The conversation an entry belongs to, read without loading the entry. */
    @Query("SELECT e.conversationId FROM ConversationEntry e WHERE e.id = :id AND e.workspaceId = :workspaceId")
    Optional<UUID> findConversationIdByIdAndWorkspaceId(@Param("id") UUID id, @Param("workspaceId") Long workspaceId);

    /** A page of the entries the app shows, newest first, before a sequence number. */
    @Query(value = """
            SELECT e.* FROM conversation_entry e
            WHERE e.workspace_id = :workspaceId AND e.conversation_id = :conversationId AND e.seq < :beforeSeq AND
            """ + SHOWN + """
            ORDER BY e.seq DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ConversationEntry> findShownBefore(
            @Param("workspaceId") Long workspaceId,
            @Param("conversationId") UUID conversationId,
            @Param("beforeSeq") long beforeSeq,
            @Param("limit") int limit);

    /** The sequence number of the newest entry the app shows, {@code 0} when it shows none. */
    @Query(value = """
            SELECT COALESCE(MAX(e.seq), 0) FROM conversation_entry e
            WHERE e.workspace_id = :workspaceId AND e.conversation_id = :conversationId AND
            """ + SHOWN, nativeQuery = true)
    long findLatestShownSeq(@Param("workspaceId") Long workspaceId, @Param("conversationId") UUID conversationId);

    /**
     * When Hephaestus's newest shown answer in the main conversation or any of its threads was written, or {@code null}: the
     * watermark a client compares with what it has seen. A redacted answer is not shown, so it does not count.
     */
    @Query(value = """
            SELECT MAX(e.created_at) FROM conversation_entry e
            WHERE e.workspace_id = :workspaceId
              AND e.conversation_id IN (
                  SELECT c.id FROM conversation c
                  WHERE c.workspace_id = :workspaceId
                    AND (c.id = :mainId OR c.parent_conversation_id = :mainId))
              AND e.kind = 'ANSWER'
              AND
            """ + SHOWN, nativeQuery = true)
    @Nullable
    Instant findLatestAnswerAt(@Param("workspaceId") Long workspaceId, @Param("mainId") UUID mainId);

    /** {@link ThreadSummary} columns over the threads {@code c}, grouped by thread. */
    String THREAD_SUMMARIES = """
            SELECT c.id AS "conversationId", c.parent_entry_id AS "parentEntryId", COUNT(e.id) AS "replies",
                   MAX(e.created_at) AS "lastReplyAt", COALESCE(MAX(e.seq), 0) AS "latestSeq"
            FROM conversation c
            LEFT JOIN conversation_entry e ON e.conversation_id = c.id AND e.workspace_id = c.workspace_id AND
            """ + SHOWN;

    @Query(value = THREAD_SUMMARIES + """
            WHERE c.workspace_id = :workspaceId AND c.parent_entry_id IN (:parentEntryIds)
            GROUP BY c.id, c.parent_entry_id
            """, nativeQuery = true)
    List<ThreadSummary> findThreadSummaries(
            @Param("workspaceId") Long workspaceId, @Param("parentEntryIds") Collection<UUID> parentEntryIds);

    /** The threads of a main conversation, latest activity first. */
    @Query(value = THREAD_SUMMARIES + """
            WHERE c.workspace_id = :workspaceId AND c.parent_conversation_id = :mainId
            GROUP BY c.id, c.parent_entry_id, c.last_activity_at
            ORDER BY c.last_activity_at DESC, c.id
            LIMIT :limit
            """, nativeQuery = true)
    List<ThreadSummary> findThreadSummariesOfMain(
            @Param("workspaceId") Long workspaceId, @Param("mainId") UUID mainId, @Param("limit") int limit);

    /**
     * The tool results, as a predicate on {@code r}, of the link tool calls that succeeded. The app shows a link call
     * only when its result is not an error. A result names its call by {@code toolCallId}.
     */
    String SUCCEEDED_LINK_RESULT = """
             r.kind = 'TOOL_RESULT' AND r.redacted_at IS NULL
             AND r.payload -> 'message' ->> 'toolName' IN ('link_result', 'link_problem', 'show_feedback')
             AND COALESCE(r.payload -> 'message' ->> 'isError', 'false') <> 'true'
            """;

    /**
     * The link tool calls of these turns that succeeded: the turn, the tool call id and the tool of each. Reads only the
     * matching tool results, never the content of other tool results.
     */
    @Query(value = """
            SELECT r.turn_id AS "turnId", r.payload -> 'message' ->> 'toolCallId' AS "callId",
                   r.payload -> 'message' ->> 'toolName' AS "tool"
            FROM conversation_entry r
            WHERE r.workspace_id = :workspaceId AND r.turn_id IN (:turnIds) AND
            """ + SUCCEEDED_LINK_RESULT, nativeQuery = true)
    List<ShownLinkCall> findShownLinkCalls(
            @Param("workspaceId") Long workspaceId, @Param("turnIds") Collection<UUID> turnIds);

    /** One row of {@link #findShownLinkCalls}. */
    interface ShownLinkCall {
        UUID getTurnId();

        String getCallId();

        String getTool();
    }

    /** The successful link calls of these turns, keyed by turn, call id and tool. */
    default Set<ConversationPiEntries.ShownCall> shownLinkCalls(Long workspaceId, Collection<UUID> turnIds) {
        return findShownLinkCalls(workspaceId, turnIds).stream()
                .map(row -> new ConversationPiEntries.ShownCall(row.getTurnId(), row.getCallId(), row.getTool()))
                .collect(Collectors.toSet());
    }

    /** The newest message before a sequence number that is not redacted: the developer's, Hephaestus's or a weekly digest. */
    Optional<ConversationEntry>
            findFirstByWorkspaceIdAndConversationIdAndSeqLessThanAndKindInAndRedactedAtIsNullOrderBySeqDesc(
                    Long workspaceId, UUID conversationId, long seq, Collection<ConversationEntry.Kind> kinds);

    @Query("""
            SELECT COUNT(e) AS count, MAX(e.createdAt) AS newest FROM ConversationEntry e
            WHERE e.workspaceId = :workspaceId AND e.conversationId = :conversationId AND e.seq > :afterSeq
              AND e.kind IN (de.tum.cit.aet.hephaestus.conversation.ConversationEntry.Kind.USER,
                             de.tum.cit.aet.hephaestus.conversation.ConversationEntry.Kind.ANSWER,
                             de.tum.cit.aet.hephaestus.conversation.ConversationEntry.Kind.DIGEST)
              AND e.redactedAt IS NULL
            """)
    MessageSpan findMessageSpan(
            @Param("workspaceId") Long workspaceId,
            @Param("conversationId") UUID conversationId,
            @Param("afterSeq") long afterSeq);

    Optional<ConversationEntry> findByWorkspaceIdAndRequestId(Long workspaceId, String requestId);

    Optional<ConversationEntry> findByWorkspaceIdAndTurnIdAndKind(
            Long workspaceId, UUID turnId, ConversationEntry.Kind kind);

    /** The user question or real system digest entry that starts this conversation work. */
    default ConversationEntry findStimulus(Long workspaceId, UUID turnId) {
        return findByWorkspaceIdAndTurnIdAndKind(workspaceId, turnId, ConversationEntry.Kind.USER)
                .or(() -> findByWorkspaceIdAndTurnIdAndKind(workspaceId, turnId, ConversationEntry.Kind.DIGEST))
                .orElseThrow(() -> new IllegalStateException("Conversation turn " + turnId + " has no stimulus"));
    }

    boolean existsByWorkspaceIdAndRequestId(Long workspaceId, String requestId);

    Optional<ConversationEntry> findByWorkspaceIdAndConversationIdAndSlackTs(
            Long workspaceId, UUID conversationId, String slackTs);

    /** The entries with this Slack {@code ts} in a main conversation and its threads. */
    @Query("""
            SELECT e FROM ConversationEntry e
            WHERE e.workspaceId = :workspaceId AND e.slackTs = :slackTs
              AND e.conversationId IN (
                  SELECT c.id FROM Conversation c
                  WHERE c.workspaceId = :workspaceId AND (c.id = :mainId OR c.parentConversationId = :mainId))
            """)
    List<ConversationEntry> findInTreeBySlackTs(
            @Param("workspaceId") Long workspaceId, @Param("mainId") UUID mainId, @Param("slackTs") String slackTs);

    /**
     * Stores Pi's entry for a question. A deleted question keeps no copy of its words, so a redacted row is skipped.
     * An edited question keeps Pi's entry with its current text, never the words the host read before the edit.
     * A digest keeps its fact membership and stores only the Pi id needed to rebuild the path.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE conversation_entry
               SET payload = CASE WHEN kind = 'DIGEST'
                   THEN payload || jsonb_build_object('id', CAST(:piEntry AS jsonb) -> 'id')
                   WHEN edited_at IS NULL THEN CAST(:piEntry AS jsonb)
                   ELSE jsonb_set(CAST(:piEntry AS jsonb), '{message,content}', to_jsonb(text)) END
             WHERE id = :id AND workspace_id = :workspaceId AND redacted_at IS NULL
            """, nativeQuery = true)
    int recordPiEntry(@Param("workspaceId") Long workspaceId, @Param("id") UUID id, @Param("piEntry") String piEntry);

    /** Sets or clears the vote on one of Hephaestus's messages that is not erased. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE ConversationEntry e SET e.vote = :vote
            WHERE e.id = :id AND e.workspaceId = :workspaceId
              AND e.kind = de.tum.cit.aet.hephaestus.conversation.ConversationEntry.Kind.ANSWER
              AND e.redactedAt IS NULL
            """)
    int vote(
            @Param("workspaceId") Long workspaceId,
            @Param("id") UUID id,
            @Param("vote") ConversationEntry.@Nullable Vote vote);

    /**
     * Replaces the text of a developer's Slack message that is not deleted, and the words in Pi's stored copy of it,
     * so no earlier version stays. The database clock stamps the edit, as it stamps a turn's start.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE conversation_entry
               SET text = :text, search_text = :text, edited_at = clock_timestamp(),
                   payload = jsonb_set(payload, '{message,content}', to_jsonb(CAST(:text AS text)))
             WHERE id = :id AND workspace_id = :workspaceId AND kind = 'USER' AND surface = 'SLACK'
               AND redacted_at IS NULL
            """, nativeQuery = true)
    int editSlackMessage(@Param("workspaceId") Long workspaceId, @Param("id") UUID id, @Param("text") String text);

    List<ConversationEntry> findByWorkspaceIdAndTurnIdAndKindOrderBySeqAsc(
            Long workspaceId, UUID turnId, ConversationEntry.Kind kind);

    List<ConversationEntry> findByWorkspaceIdAndIdIn(Long workspaceId, Collection<UUID> ids);

    /** An entry, and the top-level message its thread replies to, or {@code null} in a main conversation. */
    interface EntryPlace {
        UUID getEntryId();

        @Nullable
        UUID getParentEntryId();
    }

    @Query("""
            SELECT e.id AS entryId, c.parentEntryId AS parentEntryId
            FROM ConversationEntry e, Conversation c
            WHERE e.workspaceId = :workspaceId AND e.id IN :ids AND e.redactedAt IS NULL
              AND c.id = e.conversationId AND c.workspaceId = :workspaceId
            """)
    List<EntryPlace> findUnredactedPlaces(@Param("workspaceId") Long workspaceId, @Param("ids") Collection<UUID> ids);

    @Query(value = """
            SELECT id AS "id", conversation_id AS "conversationId", seq AS "seq", kind AS "kind",
                   turn_id AS "turnId",
                   CASE WHEN kind <> 'DIGEST' THEN payload ->> 'id' END AS "piId",
                   CASE WHEN kind = 'SUMMARY' THEN payload ->> 'firstKeptEntryId' END AS "firstKept",
                   (redacted_at IS NOT NULL AND kind NOT IN ('ANSWER', 'TOOL_RESULT')) AS "omitted"
            FROM conversation_entry
            WHERE workspace_id = :workspaceId AND conversation_id = :conversationId
            ORDER BY seq
            """, nativeQuery = true)
    List<Head> findHeadsByWorkspaceIdAndConversationId(
            @Param("workspaceId") Long workspaceId, @Param("conversationId") UUID conversationId);

    /** A search hit: the message and an excerpt of its text around the query's words. */
    interface SearchHit {
        UUID getId();

        UUID getConversationId();

        Instant getCreatedAt();

        String getSurface();

        String getKind();

        String getExcerpt();
    }

    /**
     * Full-text search over the words the app shows ({@link ConversationEntry#getSearchText}), through the full-text index
     * {@code ix_conversation_entry_text_search}, in the messages of some conversations: the
     * developer's, Hephaestus's and weekly digests, never tool results, summaries or redacted entries. Without a query, the
     * newest come first.
     */
    @Query(value = """
            SELECT e.id AS "id", e.conversation_id AS "conversationId", e.created_at AS "createdAt",
                   e.surface AS "surface", e.kind AS "kind",
                   CASE WHEN CAST(:query AS text) IS NULL THEN left(e.search_text, 300)
                        ELSE ts_headline('simple', e.search_text, websearch_to_tsquery('simple', CAST(:query AS text)),
                                         'StartSel="",StopSel="",MaxFragments=1,MaxWords=50,MinWords=20')
                   END AS "excerpt"
            FROM conversation_entry e
            WHERE e.workspace_id = :workspaceId AND e.conversation_id IN (:conversationIds)
              AND e.search_text <> ''
              AND (
            """ + SHOWN + """
              ) AND e.redacted_at IS NULL
              AND (CAST(:query AS text) IS NULL
                   OR to_tsvector('simple', e.search_text) @@ websearch_to_tsquery('simple', CAST(:query AS text)))
              AND (CAST(:before AS timestamptz) IS NULL OR e.created_at < CAST(:before AS timestamptz))
              AND (CAST(:after AS timestamptz) IS NULL OR e.created_at > CAST(:after AS timestamptz))
            ORDER BY CASE WHEN CAST(:query AS text) IS NULL THEN 0
                          ELSE ts_rank(to_tsvector('simple', e.search_text),
                                       websearch_to_tsquery('simple', CAST(:query AS text))) END DESC,
                     e.created_at DESC, e.id
            LIMIT :limit
            """, nativeQuery = true)
    List<SearchHit> searchByWorkspaceId(
            @Param("workspaceId") Long workspaceId,
            @Param("conversationIds") Collection<UUID> conversationIds,
            @Param("query") @Nullable String query,
            @Param("before") @Nullable Instant before,
            @Param("after") @Nullable Instant after,
            @Param("limit") int limit);

    @Query("""
            SELECT e.id FROM ConversationEntry e
            WHERE e.workspaceId = :workspaceId AND e.surface = :surface AND e.redactedAt IS NULL
            """)
    List<UUID> findUnredactedIdsByWorkspaceIdAndSurface(
            @Param("workspaceId") Long workspaceId, @Param("surface") ConversationEntry.Surface surface);

    /**
     * The entries, as a predicate on {@code e}, that may copy anyone's data in their workspace: the results of the
     * context folder's tools ({@link ConversationPiEntries#COPYING_TOOLS}), which read the turn's context documents and the
     * workspace's repositories. Hephaestus's messages that showed feedback about another person have their own erasure step
     * ({@code ConversationFeedbackCopyPersonDataStore}). A {@code search_conversation} result quotes the developer's own
     * conversation and goes with the entries it quotes.
     */
    String COPIES_PERSON_DATA = "e.kind = 'TOOL_RESULT' AND e.payload -> 'message' ->> 'toolName' IN ('read', 'bash',"
            + " 'load_context', 'fetch_context')";

    /** The entries of a workspace not erased yet that were written before the cutoff, oldest first. */
    @Query(value = """
            SELECT e.id FROM conversation_entry e
            WHERE e.workspace_id = :workspaceId AND e.redacted_at IS NULL AND e.created_at < :cutoff
            ORDER BY e.created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findUnredactedIdsWrittenBefore(
            @Param("workspaceId") long workspaceId, @Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** The tool results of a workspace not erased yet that can copy anyone's data, written before the cutoff. */
    @Query(value = """
            SELECT e.id FROM conversation_entry e
            WHERE e.workspace_id = :workspaceId AND e.redacted_at IS NULL AND e.created_at < :cutoff AND
            """ + COPIES_PERSON_DATA + """

            ORDER BY e.created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findUnredactedCopyingToolResultIdsWrittenBefore(
            @Param("workspaceId") long workspaceId, @Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** The messages of the account's conversations as the app shows them, in every workspace, oldest first. */
    @WorkspaceAgnostic("The self-service export reads the account's own messages in every workspace")
    @Query(value = """
            SELECT w.slug AS workspace, e.conversation_id AS conversationId, e.surface AS surface,
                   e.kind AS author, e.text AS text,
                   e.created_at AS writtenAt, e.edited_at AS editedAt
            FROM conversation_entry e
            JOIN conversation c ON c.id = e.conversation_id AND c.workspace_id = e.workspace_id
            JOIN workspace w ON w.id = c.workspace_id
            WHERE c.account_id = :accountId AND e.kind IN ('USER', 'ANSWER', 'DIGEST') AND e.redacted_at IS NULL
            ORDER BY c.created_at, c.id, e.seq
            """, nativeQuery = true)
    List<OwnMessage> findOwnMessages(@Param("accountId") long accountId);

    interface OwnMessage {
        String getWorkspace();

        UUID getConversationId();

        String getSurface();

        String getAuthor();

        String getText();

        Instant getWrittenAt();

        @Nullable
        Instant getEditedAt();
    }

    /** The workspaces that hold a conversation entry not erased yet. */
    @WorkspaceAgnostic("The conversation retention sweep finds its workspaces first and then erases in each one")
    @Query(value = "SELECT DISTINCT workspace_id FROM conversation_entry WHERE redacted_at IS NULL", nativeQuery = true)
    List<Long> findWorkspaceIdsWithUnredactedEntries();

    /** The person-data erasure step that redacts the entries of {@link #COPIES_PERSON_DATA}. */
    String COPIES_PERSON_DATA_STORE = "conversation_entry_tool_result_copy";

    /** Locks the main conversation row of each tree that holds one of these entries, in id order. */
    @WorkspaceAgnostic("Person erasure and Slack removal redact entries by id across the workspaces they name")
    @Query(value = """
            SELECT c.id FROM conversation c
            WHERE c.id IN (
                SELECT COALESCE(t.parent_conversation_id, t.id)
                FROM conversation_entry e JOIN conversation t ON t.id = e.conversation_id
                WHERE e.id = ANY(CAST(:ids AS uuid[])))
            ORDER BY c.id
            FOR NO KEY UPDATE
            """, nativeQuery = true)
    List<UUID> lockTreesOf(@Param("ids") UUID[] ids);

    /**
     * Redacts these entries and their copies as {@link ConversationRedaction} describes; without {@code withTargets}, only
     * the copies.
     */
    @WorkspaceAgnostic("Person erasure and Slack removal redact entries by id across the workspaces they name")
    @Query(value = """
            WITH target AS (
                SELECT id, conversation_id, seq, kind, turn_id FROM conversation_entry
                WHERE id = ANY(CAST(:ids AS uuid[])) AND redacted_at IS NULL
            ), derived AS (
                SELECT a.id, a.conversation_id, a.seq, a.kind
                FROM target t
                JOIN conversation_entry a ON a.conversation_id = t.conversation_id
                    OR a.conversation_id IN (
                        SELECT c.id FROM conversation c WHERE c.parent_conversation_id = t.conversation_id)
                WHERE :withDerivedAnswers AND t.kind = 'TOOL_RESULT' AND a.kind IN ('ANSWER', 'DIGEST') AND a.redacted_at IS NULL
                  AND (a.conversation_id <> t.conversation_id OR a.seq > t.seq OR a.turn_id = t.turn_id)
            ), messages AS (
                SELECT id, conversation_id, seq, kind FROM target
                UNION SELECT id, conversation_id, seq, kind FROM derived
            ), quoting AS (
                SELECT r.id, r.conversation_id, r.seq
                FROM messages m
                JOIN conversation mc ON mc.id = m.conversation_id
                JOIN conversation rc ON rc.id = COALESCE(mc.parent_conversation_id, mc.id)
                    OR rc.parent_conversation_id = COALESCE(mc.parent_conversation_id, mc.id)
                JOIN conversation_entry r ON r.conversation_id = rc.id
                WHERE m.kind IN ('USER', 'ANSWER', 'DIGEST')
                  AND r.kind = 'TOOL_RESULT' AND r.redacted_at IS NULL
                  AND r.payload -> 'message' ->> 'toolName' = 'search_conversation'
                  AND strpos(CAST(r.payload AS text), CAST(m.id AS text)) > 0
            ), changed AS (
                SELECT id, conversation_id, seq FROM target
                UNION SELECT id, conversation_id, seq FROM quoting
                UNION SELECT id, conversation_id, seq FROM derived
            ), covering AS (
                SELECT s.id
                FROM conversation_entry s
                JOIN conversation sc ON sc.id = s.conversation_id
                WHERE s.kind = 'SUMMARY' AND s.redacted_at IS NULL
                  AND (sc.parent_conversation_id IN (SELECT conversation_id FROM changed)
                       OR EXISTS (
                           SELECT 1 FROM changed c
                           WHERE c.conversation_id = s.conversation_id
                             AND c.seq BETWEEN CAST(s.payload ->> 'fromSeq' AS bigint)
                                           AND CAST(s.payload ->> 'toSeq' AS bigint)))
                  AND s.conversation_id IN (
                      SELECT t.id FROM conversation t
                      WHERE t.id IN (SELECT conversation_id FROM changed)
                         OR t.parent_conversation_id IN (SELECT conversation_id FROM changed))
            ), updated AS (
            UPDATE conversation_entry e
               SET redacted_at = clock_timestamp(), text = '', search_text = '',
                   payload = CASE
                       WHEN e.payload IS NULL THEN NULL
                       WHEN e.kind = 'USER' THEN jsonb_build_object(
                           'type', e.payload -> 'type', 'id', e.payload -> 'id', 'parentId', e.payload -> 'parentId')
                       WHEN e.kind NOT IN ('ANSWER', 'TOOL_RESULT')
                           OR jsonb_typeof(e.payload -> 'message') IS DISTINCT FROM 'object' THEN NULL
                       ELSE jsonb_set(
                           e.payload #- '{message,details}', '{message,content}',
                           jsonb_build_array(jsonb_build_object('type', 'text', 'text', CAST(:erased AS text)))
                           || COALESCE((
                               SELECT jsonb_agg(jsonb_build_object(
                                   'type', 'toolCall', 'id', b ->> 'id', 'name', b ->> 'name',
                                   'arguments', jsonb_build_object()) ORDER BY n)
                               FROM jsonb_array_elements(
                                   CASE WHEN e.kind = 'ANSWER'
                                             AND jsonb_typeof(e.payload -> 'message' -> 'content') = 'array'
                                        THEN e.payload -> 'message' -> 'content'
                                        ELSE jsonb_build_array() END) WITH ORDINALITY AS blocks (b, n)
                               WHERE b ->> 'type' = 'toolCall'), jsonb_build_array()))
                   END
             WHERE e.id IN (SELECT id FROM changed WHERE :withTargets OR id NOT IN (SELECT id FROM target)
                            UNION SELECT id FROM covering)
            RETURNING e.id, e.workspace_id, e.conversation_id, e.kind
            )
            SELECT id AS "id", workspace_id AS "workspaceId", conversation_id AS "conversationId"
            FROM updated WHERE kind IN ('USER','ANSWER','DIGEST')
            """, nativeQuery = true)
    List<EntryRef> redactWithCopies(
            @Param("ids") UUID[] ids,
            @Param("erased") String erased,
            @Param("withTargets") boolean withTargets,
            @Param("withDerivedAnswers") boolean withDerivedAnswers);

    /** How many of these entries are redacted, or were edited at or after {@code since}. */
    @Query(value = """
            SELECT COUNT(*) FROM conversation_entry e
            WHERE e.workspace_id = :workspaceId AND e.id = ANY(CAST(:ids AS uuid[]))
              AND (e.redacted_at IS NOT NULL OR e.edited_at >= :since)
            """, nativeQuery = true)
    long countRedactedOrEditedSince(
            @Param("workspaceId") Long workspaceId, @Param("ids") UUID[] ids, @Param("since") Instant since);

    /**
     * How many entries that a new summary of {@code conversationId} may have read ({@link ConversationRedaction}) were
     * redacted or edited at or after {@code since}.
     */
    @WorkspaceAgnostic("The summary's conversation already names the workspace")
    @Query(value = """
            SELECT COUNT(*) FROM conversation_entry e
            JOIN conversation c ON c.id = :conversationId
            WHERE (e.redacted_at >= :since OR e.edited_at >= :since)
              AND ((e.conversation_id = c.id AND e.seq BETWEEN :fromSeq AND :toSeq)
                   OR e.conversation_id = c.parent_conversation_id)
            """, nativeQuery = true)
    long countChangedSinceInCoverage(
            @Param("conversationId") UUID conversationId,
            @Param("fromSeq") long fromSeq,
            @Param("toSeq") long toSeq,
            @Param("since") Instant since);

    /** Read under the conversation lock: the last sequence number taken, {@code 0} for an empty conversation. */
    @Query("""
            SELECT COALESCE(MAX(e.seq), 0) FROM ConversationEntry e
            WHERE e.workspaceId = :workspaceId AND e.conversationId = :conversationId
            """)
    long findMaxSeqByWorkspaceIdAndConversationId(
            @Param("workspaceId") Long workspaceId, @Param("conversationId") UUID conversationId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ConversationEntry x WHERE x.workspaceId = :workspaceId")
    int deleteByWorkspaceId(@Param("workspaceId") Long workspaceId);

    @Query(
            "SELECT e.id FROM ConversationEntry e WHERE e.workspaceId=:workspaceId AND e.id IN (:ids) AND e.redactedAt IS NULL")
    List<UUID> findUnredactedIds(@Param("workspaceId") long workspaceId, @Param("ids") Collection<UUID> ids);

    /** Whether a confirmed Slack answer preceded this question in the conversation. */
    @Query("""
        SELECT COUNT(e) > 0 FROM ConversationEntry e
        WHERE e.workspaceId = :workspaceId AND e.conversationId = :conversationId
          AND e.kind = de.tum.cit.aet.hephaestus.conversation.ConversationEntry.Kind.ANSWER AND e.slackTs IS NOT NULL
          AND EXISTS (SELECT q.id FROM ConversationEntry q WHERE q.workspaceId = e.workspaceId
              AND q.turnId = e.turnId AND q.seq < :seq
              AND q.kind = de.tum.cit.aet.hephaestus.conversation.ConversationEntry.Kind.USER
              AND q.surface = de.tum.cit.aet.hephaestus.conversation.ConversationEntry.Surface.SLACK)
        """)
    boolean answeredInSlackBefore(
            @Param("workspaceId") Long workspaceId,
            @Param("conversationId") UUID conversationId,
            @Param("seq") long seq);

    @Modifying
    @Query(
            "UPDATE ConversationEntry e SET e.slackTs = :ts WHERE e.id = :id AND e.workspaceId = :workspaceId AND e.redactedAt IS NULL")
    int setSlackTs(@Param("workspaceId") Long workspaceId, @Param("id") UUID id, @Param("ts") String ts);
}
