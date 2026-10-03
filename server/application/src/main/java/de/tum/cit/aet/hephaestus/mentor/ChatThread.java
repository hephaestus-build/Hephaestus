package de.tum.cit.aet.hephaestus.mentor;

import com.fasterxml.jackson.annotation.JsonIgnore;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.jspecify.annotations.NonNull;

/**
 * Simple conversation thread for AI SDK that contains a tree of messages.
 * The AI SDK extracts uses a linear conversation path from this tree structure.
 */
@Entity
@Table(name = "chat_thread")
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ChatThread {

    @Id
    @EqualsAndHashCode.Include
    private UUID id;

    @NonNull
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(columnDefinition = "TEXT", length = 256)
    @Size(max = 256)
    private String title;

    /**
     * The surface this thread is conducted on (changelog {@code 1782980500800-1}). Existing rows backfill to
     * {@code WEB}; a Slack DM thread is created with {@code SLACK_DM}. Value-constrained by
     * {@code chk_chat_thread_surface}.
     */
    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(name = "surface", nullable = false, length = 16)
    @ColumnDefault("'WEB'")
    private ThreadSurface surface = ThreadSurface.WEB;

    /**
     * All messages in this thread. DB-side {@code ON DELETE CASCADE} (migration
     * {@code mentor-1071-cascade-legacy-fks}) covers parent-delete propagation in a single
     * statement; the JPA-level cascade would issue N per-row DELETEs for nothing. We keep
     * {@code orphanRemoval} for collection-mutation semantics.
     */
    @OneToMany(mappedBy = "thread", fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    @ToString.Exclude
    @JsonIgnore
    private List<ChatMessage> allMessages = new ArrayList<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    @ToString.Exclude
    @JsonIgnore
    private User user;

    @NonNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    @ToString.Exclude
    @JsonIgnore
    private Workspace workspace;

    /**
     * Verbatim Pi SDK session JSONL bytes. BYTEA, plain {@code byte[]} — NOT {@code @Lob},
     * which would force Postgres OID mode and break auto-commit reads. Bulk reads go through
     * {@code ChatThreadRepository#findSessionJsonl}. Person erasure leaves an empty journal: the next
     * turn must not continue any session a warm runtime still holds for the thread.
     */
    @Column(name = "session_jsonl", columnDefinition = "bytea")
    @ToString.Exclude
    @JsonIgnore
    private byte[] sessionJsonl;
}
