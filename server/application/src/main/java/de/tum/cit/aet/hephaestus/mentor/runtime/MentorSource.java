package de.tum.cit.aet.hephaestus.mentor.runtime;

import de.tum.cit.aet.hephaestus.core.Lifecycle;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned;
import de.tum.cit.aet.hephaestus.mentor.spi.Producer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One canonical provider message, owned by the account of its verified author. Its producer is pinned at first insert, so edits and deletes follow
 * it across cutover and rollback. A delete leaves the row as a tombstone, so a create that arrives late opens nothing.
 * Only the intake writes it.
 */
@Entity
@PersonOwned
@Table(
        name = "mentor_source",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_mentor_source_message",
                        columnNames = {"binding_id", "container_id", "native_message_id"}),
        indexes = {
            @Index(name = "idx_mentor_source_account", columnList = "account_id"),
            @Index(name = "idx_mentor_source_workspace", columnList = "workspace_id")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MentorSource {
    @Id
    private UUID id;

    private long accountId;

    private long bindingId;

    /** The binding's workspace: its purge and its Slack disconnect erase the source with its events and request. */
    private long workspaceId;

    @Column(nullable = false)
    private String containerId;

    @Column(nullable = false)
    private String nativeMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Producer producer;

    /** The newest provider revision applied. */
    @Column(nullable = false, length = 64)
    private String revision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;

    public enum State implements Lifecycle<State> {
        LIVE,
        DELETED;

        @Override
        public boolean canTransitionTo(State next) {
            return this == LIVE && next == DELETED;
        }
    }
}
