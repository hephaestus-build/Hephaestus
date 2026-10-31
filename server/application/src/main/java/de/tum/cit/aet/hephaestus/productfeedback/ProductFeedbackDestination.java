package de.tum.cit.aet.hephaestus.productfeedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/** The instance's one product feedback destination setting. With no repository set, reports go to the admin email. */
@Entity
@Table(name = "product_feedback_destination")
@Getter
@Setter
@NoArgsConstructor
public class ProductFeedbackDestination {
    static final short SINGLETON = 1;

    @Id
    private short id = SINGLETON;

    /** {@code owner/name} of the public repository, or null for none. */
    @Column(name = "public_repository", length = 200)
    private @Nullable String publicRepository;

    /** {@code owner/name} of the private repository, or null for none. */
    @Column(name = "private_repository", length = 200)
    private @Nullable String privateRepository;

    /** The instance-level chat model that drafts issues, or null for none. */
    @Column(name = "llm_model_id")
    private @Nullable Long llmModelId;

    @Column(name = "daily_draft_limit", nullable = false)
    private int dailyDraftLimit = 50;

    /** New and unfiled reports go to the admin email while paused; follow-up, withdrawal and cleanup continue. */
    @Column(nullable = false)
    private boolean paused;

    /** The version of the privacy notice the sharing choice refers to; a new version asks every person again. */
    @Column(name = "notice_version", nullable = false)
    private int noticeVersion = 1;

    /** The public repository's open issues as {@code number<TAB>title} lines, newest update first. */
    @Column(name = "snapshot", columnDefinition = "text")
    private @Nullable String snapshot;

    @Column(name = "snapshot_at")
    private @Nullable Instant snapshotAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.EPOCH;

    @Column(name = "updated_by_account_id")
    private @Nullable Long updatedByAccountId;

    boolean configured() {
        return publicRepository != null || privateRepository != null;
    }
}
