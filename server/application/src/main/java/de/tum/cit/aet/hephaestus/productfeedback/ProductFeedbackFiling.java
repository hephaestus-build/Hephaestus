package de.tum.cit.aet.hephaestus.productfeedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/**
 * One report's way to GitHub: its state, route and reason, the draft, the frozen destination and the issue it became.
 * Sweepers claim rows with a lease; every write checks the attempt it was claimed under, so a stale server's result is
 * dropped. The row is deleted with its report.
 */
@Entity
@Table(
        name = "product_feedback_filing",
        indexes = @Index(name = "idx_product_feedback_filing_state", columnList = "state, next_attempt_at"))
@Getter
@Setter
@NoArgsConstructor
public class ProductFeedbackFiling {
    @Id
    @Column(name = "feedback_id")
    private UUID feedbackId;

    /** Changes only through {@link #apply}, so that {@link FilingLifecycle} sees every move. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Setter(AccessLevel.NONE)
    private FilingState state;

    /** Reserves the daily draft allowance immediately before the provider call. */
    @Column(name = "draft_admitted_at")
    private @Nullable Instant draftAdmittedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private @Nullable FilingRoute route;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private @Nullable FilingReason reason;

    /** The person allowed this report to become public. */
    @Column(name = "allow_public", nullable = false)
    private boolean allowPublic;

    /** The person asked to be mentioned by their GitHub login on a public issue. */
    @Column(name = "mention_login", length = 39)
    private @Nullable String mentionLogin;

    /** The consent event the report was routed under. */
    @Column(name = "consent_id")
    private @Nullable UUID consentId;

    /** A security disposition, a blocking redaction finding or a named person was seen in some revision. */
    @Column(name = "risk_latch", nullable = false)
    private boolean riskLatch;

    /** Counts up for every draft and every hand-over to filing; a result is written only for its own generation. */
    @Column(nullable = false)
    private int attempt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    /** A public report is filed no earlier than this; null for a private one. */
    @Column(name = "filed_after")
    private @Nullable Instant filedAfter;

    /** The text the model drafts from: the person's report or their edit, after redaction. Set by the server. */
    @Column(name = "prompt_input", columnDefinition = "text")
    private @Nullable String promptInput;

    /** The model's raw answer, kept only until the server has validated and routed it. */
    @Column(name = "answer", columnDefinition = "text")
    private @Nullable String answer;

    /** A maintainer's {@code /publish}, persisted until it is carried out or refused. */
    @Column(name = "publish_requested_by", length = 39)
    private @Nullable String publishRequestedBy;

    @Column(name = "publish_requested_at")
    private @Nullable Instant publishRequestedAt;

    /** The private issue a promotion closed. It keeps its text, so erasure must still reach it. */
    @Column(name = "predecessor_repository", length = 200)
    private @Nullable String predecessorRepository;

    @Column(name = "predecessor_repository_id")
    private @Nullable Long predecessorRepositoryId;

    @Column(name = "predecessor_installation_id")
    private @Nullable Long predecessorInstallationId;

    @Column(name = "predecessor_issue_number")
    private @Nullable Long predecessorIssueNumber;

    @Column(name = "predecessor_issue_node_id", length = 100)
    private @Nullable String predecessorIssueNodeId;

    /** The public repository a promotion writes to, frozen before its first write so a retry reconciles there. */
    @Column(name = "promotion_repository", length = 200)
    private @Nullable String promotionRepository;

    @Column(name = "promotion_repository_id")
    private @Nullable Long promotionRepositoryId;

    @Column(name = "promotion_installation_id")
    private @Nullable Long promotionInstallationId;

    @Column(name = "promotion_repository_node_id", length = 100)
    private @Nullable String promotionRepositoryNodeId;

    /** The page route as it may reach the model and an issue, redacted; null when there is none or the report is latched. */
    @Column(name = "page_route", length = 500)
    private @Nullable String pageRoute;

    /** The validated, redacted draft as JSON; null when there is none. */
    @Column(name = "draft", columnDefinition = "text")
    private @Nullable String draft;

    /** What redaction removed, in words for the person, such as "1 email address, 2 links". */
    @Column(name = "removed", length = 200)
    private @Nullable String removed;

    @Column(name = "drafted_at")
    private @Nullable Instant draftedAt;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    /**
     * The composer told the reporter that a model provider can read this report. A report written under an older notice
     * keeps false for good, because a later notice does not reach it: it can move to GitHub, but no model ever reads it.
     */
    @Column(name = "file_disclosed", nullable = false)
    private boolean fileDisclosed;

    /** The repository the report was routed to, frozen at routing. */
    @Column(name = "repository", length = 200)
    private @Nullable String repository;

    @Column(name = "repository_id")
    private @Nullable Long repositoryId;

    @Column(name = "repository_node_id", length = 100)
    private @Nullable String repositoryNodeId;

    @Column(name = "installation_id")
    private @Nullable Long installationId;

    /** The hash of the exact title and body the current attempt sends. */
    @Column(name = "payload_hash", length = 64)
    private @Nullable String payloadHash;

    @Column(name = "first_attempt_at")
    private @Nullable Instant firstAttemptAt;

    @Column(name = "issue_number")
    private @Nullable Long issueNumber;

    @Column(name = "issue_node_id", length = 100)
    private @Nullable String issueNodeId;

    @Column(name = "issue_url", length = 300)
    private @Nullable String issueUrl;

    @Column(name = "issue_open")
    private @Nullable Boolean issueOpen;

    @Column(name = "issue_state_reason", length = 32)
    private @Nullable String issueStateReason;

    /** The newest comment the follow-up has seen. */
    @Column(name = "last_comment_id")
    private @Nullable Long lastCommentId;

    @Column(name = "followed_at")
    private @Nullable Instant followedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    ProductFeedbackFiling(UUID feedbackId, FilingState state, boolean allowPublic, Instant now) {
        this.feedbackId = feedbackId;
        this.state = state;
        this.allowPublic = allowPublic;
        this.fileDisclosed = true;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Call it before the row takes on the public issue: the private locator would be gone after that. */
    void keepPredecessor() {
        predecessorRepository = repository;
        predecessorRepositoryId = repositoryId;
        predecessorInstallationId = installationId;
        predecessorIssueNumber = issueNumber;
        predecessorIssueNodeId = issueNodeId;
    }

    /** The only way a state changes. An event the lifecycle does not allow from here throws and changes nothing. */
    void apply(FilingLifecycle.Event event) {
        state = FilingLifecycle.next(state, event)
                .orElseThrow(() -> new FilingLifecycle.IllegalTransition(feedbackId, state, event));
    }

    boolean publicRoute() {
        return route == FilingRoute.PUBLIC;
    }

    /** Whether an admin can send this saved report to GitHub now. An admin's action only ever files privately. */
    boolean canFileAt(ProductFeedbackDestination destination) {
        return state == FilingState.SAVED && !destination.isPaused() && destination.getPrivateRepository() != null;
    }

    /** Forgets the repository routing froze, so the next claim freezes the one the report's route now names. */
    void clearRecipient() {
        repository = null;
        repositoryId = null;
        repositoryNodeId = null;
        installationId = null;
    }
}
