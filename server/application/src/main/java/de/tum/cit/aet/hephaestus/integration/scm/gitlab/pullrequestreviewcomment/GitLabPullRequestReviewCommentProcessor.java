package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.PostgresStringUtils;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewthread.GitLabPullRequestReviewThreadProcessor;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitLab merge request diff notes mapped to {@link PullRequestReviewComment}.
 * <p>
 * GitLab diff notes (notes with a {@code position}) are the equivalent of GitHub's
 * pull request review comments. Each diff note belongs to a discussion (thread).
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabPullRequestReviewCommentProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitLabPullRequestReviewCommentProcessor.class);

    private final PullRequestReviewCommentRepository commentRepository;
    private final ApplicationEventPublisher eventPublisher;

    public GitLabPullRequestReviewCommentProcessor(
            PullRequestReviewCommentRepository commentRepository, ApplicationEventPublisher eventPublisher) {
        this.commentRepository = commentRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Data record for a GitLab diff note extracted from GraphQL or webhook data.
     * <p>
     * Position fields map to GitLab's {@code DiffPosition}: {@code new_path}/{@code old_path}
     * for file path (deletion falls back to {@code old_path}), {@code new_line}/{@code old_line}
     * for diff lines, and {@code head_sha}/{@code base_sha}/{@code start_sha} for diff refs.
     */
    public record DiffNoteData(
            String noteGlobalId,
            @Nullable String body,
            @Nullable String url,
            @Nullable String filePath,
            @Nullable Integer newLine,
            @Nullable Integer oldLine,
            @Nullable String newPath,
            @Nullable String oldPath,
            @Nullable String headSha,
            @Nullable String baseSha,
            @Nullable String startSha,
            @Nullable Instant createdAt,
            @Nullable Instant updatedAt) {
        /**
         * Backward-compatible constructor for callers that don't carry {@code startSha}
         * (e.g., older webhook paths). Passes {@code null} for the new field.
         */
        public DiffNoteData(
                String noteGlobalId,
                @Nullable String body,
                @Nullable String url,
                @Nullable String filePath,
                @Nullable Integer newLine,
                @Nullable Integer oldLine,
                @Nullable String newPath,
                @Nullable String oldPath,
                @Nullable String headSha,
                @Nullable String baseSha,
                @Nullable Instant createdAt,
                @Nullable Instant updatedAt) {
            this(
                    noteGlobalId,
                    body,
                    url,
                    filePath,
                    newLine,
                    oldLine,
                    newPath,
                    oldPath,
                    headSha,
                    baseSha,
                    null,
                    createdAt,
                    updatedAt);
        }
    }

    /**
     * Groups the contextual parameters needed to find or create a review comment.
     */
    public record CommentContext(
            PullRequestReviewThread thread,
            PullRequest pr,
            @Nullable User author,
            IdentityProvider provider,
            @Nullable PullRequestReviewComment inReplyTo,
            @Nullable PullRequestReview review,
            Long scopeId) {}

    /**
     * Finds or creates a review comment from a GitLab diff note.
     *
     * @param data the diff note data
     * @param context the contextual parameters (thread, PR, author, provider, inReplyTo, scopeId)
     * @return the persisted comment entity, or null if noteGlobalId is invalid
     */
    @Transactional
    @Nullable
    public PullRequestReviewComment findOrCreateComment(DiffNoteData data, CommentContext context) {
        long nativeId;
        try {
            nativeId = GitLabSyncConstants.extractNumericId(data.noteGlobalId());
        } catch (IllegalArgumentException e) {
            log.warn("Skipped diff note: reason=invalidGlobalId, gid={}", data.noteGlobalId());
            return null;
        }

        Long providerId = Objects.requireNonNull(
                Objects.requireNonNull(context.provider()).getId());

        return commentRepository
                .findByNativeIdAndProviderId(nativeId, providerId)
                .map(existing -> updateComment(existing, data, context))
                .orElseGet(() -> createComment(nativeId, data, context));
    }

    private PullRequestReviewComment updateComment(
            PullRequestReviewComment existing, DiffNoteData data, CommentContext context) {
        PullRequestReviewThread thread = context.thread();
        PullRequest pr = context.pr();
        Long scopeId = Objects.requireNonNull(context.scopeId());
        Set<String> changedFields = new HashSet<>();
        String sanitizedBody = PostgresStringUtils.sanitize(data.body());
        if (sanitizedBody != null && !sanitizedBody.equals(existing.getBody())) {
            existing.setBody(sanitizedBody);
            changedFields.add("body");
        }
        // Reconcile thread: move comment to the authoritative sync-created thread
        // if it was originally created by a webhook with a different thread
        if (existing.getThread() == null || !existing.getThread().getId().equals(thread.getId())) {
            existing.setThread(thread);
            changedFields.add("thread");
        }
        // Backfill the review link when a synthetic COMMENTED review is now available
        if (existing.getReview() == null && context.review() != null) {
            context.review().addComment(existing);
            changedFields.add("review");
        }
        if (data.updatedAt() != null) {
            existing.setUpdatedAt(data.updatedAt());
        }
        if (changedFields.isEmpty()) {
            return existing;
        }
        PullRequestReviewComment saved = commentRepository.save(existing);
        log.debug("Updated diff note: nativeId={}", saved.getNativeId());

        eventPublisher.publishEvent(new ScmDomainEvent.ReviewCommentEdited(
                ScmEventPayload.ReviewCommentData.from(saved),
                pr.getId(),
                changedFields,
                createSyncContext(pr, scopeId)));
        return saved;
    }

    private PullRequestReviewComment createComment(long nativeId, DiffNoteData data, CommentContext context) {
        PullRequestReviewComment comment = new PullRequestReviewComment();
        comment.setNativeId(nativeId);
        comment.setProvider(Objects.requireNonNull(context.provider()));
        comment.setPullRequest(context.pr());
        comment.setThread(context.thread());

        String sanitizedBody = PostgresStringUtils.sanitize(data.body());
        comment.setBody(sanitizedBody != null ? sanitizedBody : "");

        // Path from position data: prefer new_path, fall back to old_path on deletion.
        // filePath is GitLab's resolved path (equals new_path for additions/modifications, old_path for deletions).
        String path = resolvePath(data);
        comment.setPath(path);

        // Line numbers — preserve nullability semantics on the int/int columns (0 == "not present").
        // A note on a removed line has only old_line; that is its line, on the LEFT side below.
        Integer line = GitLabPullRequestReviewThreadProcessor.anchoredLine(data.newLine(), data.oldLine());
        comment.setLine(line != null ? line : 0);
        comment.setOriginalLine(data.oldLine() != null ? data.oldLine() : 0);

        // Side: RIGHT if comment anchored on new side (new_line set), LEFT if only old_line present.
        comment.setSide(deriveSide(data.newLine(), data.oldLine()));
        // GraphQL DiffPosition doesn't expose a per-note multi-line range; start_side mirrors side.
        comment.setStartSide(comment.getSide());

        // Commit SHAs: head_sha / base_sha come from diffRefs. Fall back to start_sha
        // when base_sha is absent (GitLab may omit base_sha on rebased MRs but always
        // emits start_sha for the compared branch).
        comment.setCommitId(data.headSha() != null ? data.headSha() : "");
        String originalSha = data.baseSha() != null ? data.baseSha() : data.startSha();
        comment.setOriginalCommitId(originalSha != null ? originalSha : "");

        // GitLab doesn't expose an author association.
        comment.setAuthorAssociation(null);

        // Reconstruct a minimal unified-diff header stub from position data so downstream
        // consumers that expect a diff_hunk have a parseable `@@ -a,b +c,d @@` anchor.
        // GitLab's GraphQL DiffPosition does NOT expose line_range, so we can only emit a
        // 1-line range at the commented position.
        comment.setDiffHunk(buildDiffHunkStub(data.oldLine(), data.newLine()));

        // HTML URL
        comment.setHtmlUrl(data.url() != null ? data.url() : "");

        // Timestamps
        comment.setCreatedAt(data.createdAt());
        comment.setUpdatedAt(data.updatedAt());

        // Relationships
        if (context.author() != null) {
            comment.setAuthor(context.author());
        }
        if (context.inReplyTo() != null) {
            comment.setInReplyTo(context.inReplyTo());
        }
        if (context.review() != null) {
            context.review().addComment(comment);
        }

        PullRequestReviewComment saved = commentRepository.save(comment);
        log.debug("Created diff note: nativeId={}, path={}, line={}", nativeId, path, data.newLine());

        eventPublisher.publishEvent(new ScmDomainEvent.ReviewCommentCreated(
                ScmEventPayload.ReviewCommentData.from(saved),
                context.pr().getId(),
                createSyncContext(context.pr(), Objects.requireNonNull(context.scopeId()))));

        return saved;
    }

    private static EventContext createSyncContext(PullRequest pr, Long scopeId) {
        RepositoryRef repoRef =
                pr.getRepository() != null ? Objects.requireNonNull(RepositoryRef.from(pr.getRepository())) : null;
        return EventContext.forSync(scopeId, Objects.requireNonNull(repoRef), IdentityProviderType.GITLAB);
    }

    /**
     * Resolves the file path a diff note anchors to. Precedence: {@code new_path}
     * (the file's current-side path, populated for additions/modifications), then
     * {@code old_path} (populated for deletions), then GitLab's pre-resolved
     * {@code filePath}, then empty string.
     */
    static String resolvePath(DiffNoteData data) {
        if (data.newPath() != null && !data.newPath().isBlank()) {
            return data.newPath();
        }
        if (data.oldPath() != null && !data.oldPath().isBlank()) {
            return data.oldPath();
        }
        if (data.filePath() != null && !data.filePath().isBlank()) {
            return data.filePath();
        }
        return "";
    }

    /**
     * Derives the diff side a note anchors on:
     * <ul>
     *   <li>{@code RIGHT} when {@code new_line} is set (note on the current/head side)</li>
     *   <li>{@code LEFT} when only {@code old_line} is set (note on the base side — typically a deletion)</li>
     *   <li>{@code RIGHT} as a safe default when neither line is provided (matches GitHub mapper fallback)</li>
     * </ul>
     */
    static PullRequestReviewComment.@Nullable Side deriveSide(@Nullable Integer newLine, @Nullable Integer oldLine) {
        if (newLine != null) {
            return PullRequestReviewComment.Side.RIGHT;
        }
        if (oldLine != null) {
            return PullRequestReviewComment.Side.LEFT;
        }
        return PullRequestReviewComment.Side.RIGHT;
    }

    /**
     * Builds a minimal unified-diff header stub (single-line range) so consumers that
     * parse {@code diff_hunk} don't choke on a null value. GitLab's GraphQL
     * {@code DiffPosition} does not expose a multi-line range, so this stub intentionally
     * covers only the anchored line. Returns {@code null} when neither line is provided.
     */
    @Nullable
    static String buildDiffHunkStub(@Nullable Integer oldLine, @Nullable Integer newLine) {
        int o = oldLine != null && oldLine > 0 ? oldLine : 0;
        int n = newLine != null && newLine > 0 ? newLine : 0;
        if (o == 0 && n == 0) {
            return null;
        }
        return String.format("@@ -%d,1 +%d,1 @@", o, n);
    }
}
