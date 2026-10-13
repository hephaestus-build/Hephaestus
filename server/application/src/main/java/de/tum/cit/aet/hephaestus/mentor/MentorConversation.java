package de.tum.cit.aet.hephaestus.mentor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Persistable;

/**
 * One Heph conversation. The main conversation is the developer's single conversation in a workspace: the Slack
 * DM top level and the app timeline. A thread is a conversation of its own that forks the main conversation at one
 * top-level entry, so it runs its own turns in parallel with the main conversation.
 *
 * <p>The partial unique index {@code ux_mentor_conversation_main} allows one main conversation per
 * {@code (workspace_id, user_id)}; {@code parent_entry_id} is unique, so a parent entry has at most one thread. The
 * Slack identity triple is set on the main conversation only, all or none ({@code chk_mentor_conversation_slack}).
 */
@Entity
@Table(
        name = "mentor_conversation",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_mentor_conversation_parent_entry",
                        columnNames = {"parent_entry_id"}),
        indexes = {
            @Index(name = "idx_mentor_conversation_workspace_user", columnList = "workspace_id, user_id"),
            @Index(name = "idx_mentor_conversation_parent", columnList = "parent_conversation_id"),
        })
@DynamicUpdate
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MentorConversation implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /** The developer this conversation is with. A thread carries its main conversation's developer. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Nullable
    @Column(name = "parent_conversation_id")
    private UUID parentConversationId;

    @Nullable
    @Column(name = "parent_entry_id")
    private UUID parentEntryId;

    @Nullable
    @Column(name = "slack_team_id", length = 32)
    private String slackTeamId;

    @Nullable
    @Column(name = "slack_user_id", length = 32)
    private String slackUserId;

    @Nullable
    @Column(name = "slack_channel_id", length = 32)
    private String slackChannelId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_activity_at", nullable = false)
    private Instant lastActivityAt;

    /** How far the developer read the conversation, on any device; set on the main conversation only. */
    @Nullable
    @Column(name = "last_read_at")
    private Instant lastReadAt;

    @Transient
    private boolean fresh;

    private MentorConversation(
            long workspaceId,
            long userId,
            @Nullable UUID parentConversationId,
            @Nullable UUID parentEntryId,
            Instant now) {
        this.id = UUID.randomUUID();
        this.workspaceId = workspaceId;
        this.userId = userId;
        this.parentConversationId = parentConversationId;
        this.parentEntryId = parentEntryId;
        this.createdAt = now;
        this.lastActivityAt = now;
        this.fresh = true;
    }

    static MentorConversation main(long workspaceId, long userId, Instant now) {
        return new MentorConversation(workspaceId, userId, null, null, now);
    }

    static MentorConversation thread(MentorConversation main, UUID parentEntryId, Instant now) {
        return new MentorConversation(main.getWorkspaceId(), main.getUserId(), main.getId(), parentEntryId, now);
    }

    public boolean isThread() {
        return parentConversationId != null;
    }

    /** The main conversation of this conversation tree: this one, or the one a thread forks. */
    public UUID rootId() {
        UUID parent = parentConversationId;
        return parent != null ? parent : id;
    }

    void moveTo(long userId) {
        this.userId = userId;
    }

    void touch(Instant now) {
        if (now.isAfter(lastActivityAt)) {
            lastActivityAt = now;
        }
    }

    void bindSlack(String teamId, String userId, String channelId) {
        if (isThread()) {
            throw new IllegalStateException("A Heph thread inherits the Slack identity of its main conversation");
        }
        this.slackTeamId = teamId;
        this.slackUserId = userId;
        this.slackChannelId = channelId;
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
