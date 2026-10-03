package de.tum.cit.aet.hephaestus.mentor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.jspecify.annotations.Nullable;

/**
 * Represents a vote on a chat message.
 */
@Entity
@Table(name = "chat_message_vote")
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ChatMessageVote {

    /**
     * The message ID - serves as primary key since each message has at most one vote.
     * Explicit {@code @Column(name = "message_id")} aligns the logical name with the
     * {@link #message} relation below, which targets the same physical column with
     * {@code insertable=false, updatable=false} so Hibernate sees the FK in its model
     * snapshot without owning the write path.
     */
    @Id
    @EqualsAndHashCode.Include
    @Column(name = "message_id")
    private UUID messageId;

    /**
     * Owning chat message. Read-only mirror of {@link #messageId} ({@code insertable=false,
     * updatable=false}) — writes go through {@link #messageId}, and Hibernate uses this mapping
     * to surface the FK ({@code fk_chat_message_vote_message}, added in
     * {@code mentor-1071-chat-message-vote-fk}) in its model snapshot so the Liquibase diff stays
     * clean.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "message_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_chat_message_vote_message"))
    @ToString.Exclude
    @JsonIgnore
    private @Nullable ChatMessage message;

    /**
     * Boolean vote: true = upvote (helpful), false = downvote (not helpful)
     */
    @NotNull
    @Column(nullable = false)
    private Boolean isUpvoted;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private @Nullable Instant updatedAt;

    /**
     * Constructor for new votes
     */
    public ChatMessageVote(UUID messageId, Boolean isUpvoted) {
        this.messageId = messageId;
        this.isUpvoted = isUpvoted;
    }
}
