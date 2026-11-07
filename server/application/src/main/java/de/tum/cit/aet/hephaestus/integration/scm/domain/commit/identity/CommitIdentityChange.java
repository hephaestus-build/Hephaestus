package de.tum.cit.aet.hephaestus.integration.scm.domain.commit.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One link that the provider's answer changed, with its reason. It holds numbers only, and it is the reverse path: a
 * wrong correction can be undone from this row.
 */
@Entity
@Table(
        name = "commit_identity_change",
        indexes = {
            @Index(name = "idx_commit_identity_change_changed_at", columnList = "changed_at"),
            @Index(name = "idx_commit_identity_change_old_user", columnList = "old_user_id"),
            @Index(name = "idx_commit_identity_change_new_user", columnList = "new_user_id")
        })
@Getter
@NoArgsConstructor
public class CommitIdentityChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The changed column, for example {@code git_commit.author_id}. */
    @Column(name = "target", nullable = false, length = 64)
    private String target;

    @Column(name = "row_id", nullable = false)
    private Long rowId;

    @Column(name = "old_user_id")
    private @Nullable Long oldUserId;

    @Column(name = "new_user_id")
    private @Nullable Long newUserId;

    @Column(name = "outcome", nullable = false, length = 16)
    private String outcome;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;
}
