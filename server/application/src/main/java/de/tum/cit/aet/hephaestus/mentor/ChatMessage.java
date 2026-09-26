package de.tum.cit.aet.hephaestus.mentor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Message in a conversation tree structure that maps to AI SDK UI Message format.
 * Each message can have multiple children (branches), but only one parent.
 *
 * Maps directly to the UIMessage type from the AI SDK
 */
@Entity
@Table(
        name = "chat_message",
        indexes = {@Index(name = "idx_chat_message_thread_created", columnList = "thread_id, created_at")})
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ChatMessage {

    @Id
    @EqualsAndHashCode.Include
    private UUID id;

    /**
     * Thread this message belongs to.
     */
    @NonNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "thread_id", nullable = false)
    @ToString.Exclude
    @JsonIgnore
    private ChatThread thread;

    /**
     * Parent message - null for root messages (enables tree structure)
     */
    @Nullable
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_message_id")
    @ToString.Exclude
    @JsonIgnore
    private ChatMessage parentMessage;

    /**
     * Raw {@code parent_message_id} FK exposed as a {@link UUID} that does NOT trigger Hibernate
     * lazy-load of the parent entity. Used by {@link ChatMessageDTO#from} so listing a thread
     * with N messages issues 1 query, not 1 + N (lazy proxy per message). {@code insertable} /
     * {@code updatable} = false because the {@link #parentMessage} relation owns the write side.
     */
    @Nullable
    @Column(name = "parent_message_id", insertable = false, updatable = false)
    private UUID parentMessageId;

    /**
     * Child messages - branches from this message
     */
    @OneToMany(
            mappedBy = "parentMessage",
            cascade = {CascadeType.PERSIST, CascadeType.MERGE},
            fetch = FetchType.LAZY,
            orphanRemoval = true)
    @OrderBy("createdAt ASC")
    @ToString.Exclude
    @JsonIgnore
    private List<ChatMessage> childMessages = new ArrayList<>();

    /**
     * Role of the message sender.
     */
    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    /**
     * Turn lifecycle status. The only correctness-critical metadata field: drives the partial
     * unique index {@code ux_chat_message_in_flight_v2} (one in_flight row per thread) and
     * the {@code MentorInFlightReaper} stuck-row sweep. Promoted from
     * {@code metadata.status} JSONB to a real column in migration {@code mentor-1071-add-status-column}.
     */
    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.in_flight;

    /**
     * Message metadata as a JSON object. Conventional keys (written by MentorChatService):
     * {@code model}, {@code inputTokens}, {@code outputTokens}, {@code cacheReadTokens},
     * {@code cacheWriteTokens}, {@code costUsd}, {@code finishReason}, {@code durationMs},
     * {@code toolCalls}, {@code error}. Pure billing/observability — status is its own column.
     * Shape is enforced by the {@code chk_chat_message_metadata_shape} CHECK constraint
     * (must be NULL or object).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode metadata;

    @NonNull
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // UIMessage parts. Liquibase enforces NOT NULL + JSONB-array CHECK; MentorChatService always writes explicitly.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parts", columnDefinition = "jsonb", nullable = false)
    private JsonNode parts;

    /**
     * Tokens the LLM proxy has served for this turn, added one call at a time by
     * {@code ChatMessageRepository#accumulateLlmUsage} while the row is {@code in_flight} — the only
     * record of a turn that dies before its runner reports anything.
     *
     * <p>Read-only to JPA on purpose: that native UPDATE is the single writer, so an entity flush of a
     * snapshot loaded before a proxy call landed cannot roll that call's tokens back. {@code @ColumnDefault}
     * therefore has to supply the initial zero that satisfies NOT NULL, and keeps the schema Hibernate
     * generates for the test tier in step with Liquibase.
     */
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "llm_total_calls", nullable = false, insertable = false, updatable = false)
    private int llmTotalCalls;

    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "llm_total_input_tokens", nullable = false, insertable = false, updatable = false)
    private long llmTotalInputTokens;

    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "llm_total_output_tokens", nullable = false, insertable = false, updatable = false)
    private long llmTotalOutputTokens;

    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "llm_total_reasoning_tokens", nullable = false, insertable = false, updatable = false)
    private long llmTotalReasoningTokens;

    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "llm_cache_read_tokens", nullable = false, insertable = false, updatable = false)
    private long llmCacheReadTokens;

    @org.hibernate.annotations.ColumnDefault("0")
    @Column(name = "llm_cache_write_tokens", nullable = false, insertable = false, updatable = false)
    private long llmCacheWriteTokens;

    /**
     * Optimistic-lock version — Hibernate bumps it on every managed write, including the reaper's.
     * Stale-snapshot writers (reaper-vs-complete, complete-vs-interrupt) get
     * {@code OptimisticLockingFailureException}; the first writer's outcome survives.
     * {@code accumulateLlmUsage} deliberately leaves it alone — see that query.
     */
    @org.hibernate.annotations.ColumnDefault("0")
    @jakarta.persistence.Version
    @Column(nullable = false)
    private Long version;

    /**
     * Turn lifecycle status. Stored as VARCHAR(16) with a CHECK constraint enforcing the enum
     * values. Lowercase names match the historical metadata.status JSON values verbatim so
     * the backfill UPDATE is a one-liner (see migration {@code mentor-1071-backfill-status}).
     */
    public enum Status {
        in_flight,
        completed,
        interrupted,
    }

    public enum Role {
        USER("user"),
        ASSISTANT("assistant"),
        SYSTEM("system");

        private final String value;

        Role(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static Role fromValue(String value) {
            for (Role role : values()) {
                if (role.value.equals(value)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("Unknown role: " + value);
        }
    }
}
