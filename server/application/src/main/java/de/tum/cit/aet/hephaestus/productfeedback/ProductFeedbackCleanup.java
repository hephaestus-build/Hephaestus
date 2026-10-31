package de.tum.cit.aet.hephaestus.productfeedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * What erasing a report needs on GitHub after its rows are gone. It carries no person data: the marker is a random id,
 * and the repository and issue are locators. It stays until its steps are done or an admin confirms the manual step.
 */
@Entity
@Table(name = "product_feedback_cleanup")
@Getter
@Setter
@NoArgsConstructor
public class ProductFeedbackCleanup {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "repository", nullable = false, length = 200)
    private String repository;

    @Column(name = "repository_id", nullable = false)
    private long repositoryId;

    @Column(name = "installation_id", nullable = false)
    private long installationId;

    /** The report's marker, to find an issue whose create had an unclear result. */
    @Column(nullable = false)
    private UUID marker;

    @Column(name = "issue_number")
    private @Nullable Long issueNumber;

    /** Kept once known, so the issue can still move after the scrub removed its marker. */
    @Column(name = "issue_node_id", length = 100)
    private @Nullable String issueNodeId;

    /** True when the issue is in the public repository and has to move to the private one first. */
    @Column(name = "public_issue", nullable = false)
    private boolean publicIssue;

    @Column(name = "since", nullable = false)
    private Instant since;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(nullable = false)
    private int attempts;

    ProductFeedbackCleanup(
            String repository,
            long repositoryId,
            long installationId,
            UUID marker,
            @Nullable Long issueNumber,
            boolean publicIssue,
            Instant since,
            Instant now) {
        this.repository = repository;
        this.repositoryId = repositoryId;
        this.installationId = installationId;
        this.marker = marker;
        this.issueNumber = issueNumber;
        this.publicIssue = publicIssue;
        this.since = since;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }
}
