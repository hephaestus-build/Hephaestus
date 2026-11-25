package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.core.ContinuingLifecycle;
import de.tum.cit.aet.hephaestus.core.Lifecycle;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A contact that the person agreed to, with its scope, kind and state (ADR 0133, ADR 0139). Only the person's own act
 * creates one. The schedule and the event condition of a check-in are added by #4938.
 */
@Entity
@PersonOwned
@Table(
        name = "check_in",
        indexes = {
            @Index(name = "idx_check_in_account_workspace", columnList = "account_id, workspace_id"),
            @Index(name = "idx_check_in_workspace", columnList = "workspace_id"),
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckIn {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    /** The person who agreed to the contact. */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private Kind kind;

    /** What the contact is about, such as a goal or an item. {@code null} means the whole workspace. */
    @Nullable
    @Column(name = "topic", length = 255)
    private String topic;

    /** Where the contact appears. */
    @Enumerated(EnumType.STRING)
    @Column(name = "place", nullable = false, length = 16)
    private ContactPolicy.Route place;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private State state;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "state_changed_at", nullable = false)
    private Instant stateChangedAt;

    public enum Kind {
        CHECK_IN,
        GOAL_FOLLOW_UP,
        DRAFT_CHECK,
    }

    /**
     * Each state has its reverse: Pause and Resume, Stop and Resume while the row exists (ADR 0133 decision 16). A
     * record in review sends its check-ins to {@code NEEDS_REVIEW} (decision 17).
     */
    @ContinuingLifecycle("A stopped check-in can resume while its row exists; erasure or a purge deletes the row")
    public enum State implements Lifecycle<State> {
        ACTIVE,
        PAUSED,
        STOPPED,
        NEEDS_REVIEW;

        private static final Map<State, Set<State>> LEGAL = Map.of(
                ACTIVE, Set.of(PAUSED, STOPPED, NEEDS_REVIEW),
                PAUSED, Set.of(ACTIVE, STOPPED, NEEDS_REVIEW),
                STOPPED, Set.of(ACTIVE),
                NEEDS_REVIEW, Set.of(ACTIVE, STOPPED));

        @Override
        public boolean canTransitionTo(State next) {
            return Objects.requireNonNull(LEGAL.get(this)).contains(next);
        }
    }
}
