package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.TimeOrderedIds;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;
import tools.jackson.databind.JsonNode;

/**
 * One row of a Hephaestus conversation ledger. The ledger is append-only: an entry is never deleted while its conversation
 * exists. Redaction clears its content ({@link ConversationRedaction}), and an edit replaces the text of a Slack message.
 * {@code seq} orders the entries of one conversation in append order; {@link ConversationContextPathBuilder} owns the order
 * of the context path.
 *
 * <p>{@code payload} holds the Pi session entry for every entry that the turn driver appended ({@code ANSWER},
 * {@code TOOL_RESULT}, {@code SYSTEM}, {@code SUMMARY}), and for a {@code USER} entry once the end of its turn stored
 * Pi's entry for it ({@link ConversationTurnService#end}). A {@code DIGEST} payload holds the weekly digest membership.
 */
@Entity
@Table(
        name = "conversation_entry",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_conversation_entry_conversation_seq",
                    columnNames = {"conversation_id", "seq"}),
            @UniqueConstraint(
                    name = "uk_conversation_entry_conversation_slack_ts",
                    columnNames = {"conversation_id", "slack_ts"}),
            @UniqueConstraint(
                    name = "uk_conversation_entry_tenant",
                    columnNames = {"id", "workspace_id"}),
        },
        indexes = {
            @Index(name = "idx_conversation_entry_turn", columnList = "turn_id"),
            @Index(name = "idx_conversation_entry_workspace_surface", columnList = "workspace_id, surface"),
        })
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConversationEntry implements Persistable<UUID> {

    public enum Kind {
        /** A message the developer wrote. */
        USER,
        /** A message Hephaestus wrote: one Pi assistant message. */
        ANSWER,
        TOOL_RESULT,
        /** A Pi entry that is not a message: a system prompt snapshot, a model or thinking change, a context edit. */
        SYSTEM,
        /** A Pi compaction entry. */
        SUMMARY,
        /** A weekly digest that the server wrote. */
        DIGEST;

        /** The developer's messages, Hephaestus's messages and weekly digests: what the app and search show. */
        public boolean isMessage() {
            return this == USER || this == ANSWER || this == DIGEST;
        }
    }

    /** Where an entry was written. */
    public enum Surface {
        APP,
        SLACK,
        /** Written by the server, not by the developer or by Hephaestus on their behalf. */
        SYSTEM,
    }

    /** How a Slack post stands, as its outbox action shows it ({@link ConversationSlackReplies}). */
    public enum SlackDelivery {
        PENDING,
        DELIVERED,
        /** The post may have reached Slack; a read of the Slack history decides. */
        UNCERTAIN,
        /** Slack refused the post, there was nothing to post, or complete history proved it absent. */
        FAILED,
    }

    public enum Vote {
        HELPFUL,
        NOT_HELPFUL,
    }

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Nullable
    @Column(name = "turn_id")
    private UUID turnId;

    @Nullable
    @Column(name = "request_id", length = 200)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "surface", nullable = false, length = 8)
    private Surface surface;

    /** Plain text for search, export and the app: the developer's words, Hephaestus's text, a summary or a digest line. */
    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    /**
     * The words the app shows for a message, stored once when it is written: its text and, for Hephaestus's message, the
     * {@code text} of each link call that succeeded. Never thinking, other tool calls or tool results. Search reads it
     * through its full-text index, and the personal-data export reads it. An edit replaces it and a redaction clears it.
     */
    @Column(name = "search_text", nullable = false, columnDefinition = "TEXT")
    private String searchText;

    @Nullable
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    private JsonNode payload;

    @Nullable
    @Column(name = "slack_ts", length = 32)
    private String slackTs;

    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "vote", length = 16)
    private Vote vote;

    @Nullable
    @Column(name = "edited_at")
    private Instant editedAt;

    @Nullable
    @Column(name = "redacted_at")
    private Instant redactedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean fresh;

    ConversationEntry(Conversation conversation, long seq, ConversationEntryDraft draft, Instant now) {
        this.id = TimeOrderedIds.next();
        this.workspaceId = conversation.getWorkspaceId();
        this.conversationId = conversation.getId();
        this.seq = seq;
        this.turnId = draft.turnId();
        this.requestId = draft.requestId();
        this.kind = draft.kind();
        this.surface = draft.surface();
        this.text = draft.text();
        this.searchText = draft.searchText();
        this.payload = draft.payload();
        this.slackTs = draft.slackTs();
        this.createdAt = now;
        this.fresh = true;
    }

    public boolean isRedacted() {
        return redactedAt != null;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    @PostLoad
    @PostPersist
    void markStored() {
        fresh = false;
    }
}
