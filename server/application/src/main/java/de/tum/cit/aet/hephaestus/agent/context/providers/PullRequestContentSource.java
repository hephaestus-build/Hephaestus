package de.tum.cit.aet.hephaestus.agent.context.providers;

import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireInt;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireLong;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireText;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceLimits;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewContextBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitFileChange.ChangeType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.RepositoryKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(100)
public class PullRequestContentSource implements EvidenceSource, ReviewContextBuilder {

    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final SourceKind COMMENTS = new SourceKind("scm.pull-request.comments");

    /** Checked by the integration framework against every descriptor that calls itself reviewable. */
    @Override
    public ArtifactKind artifactKind() {
        return ScmSignals.PULL_REQUEST;
    }

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(CORE, DIFF, COMMENTS);
    }

    /**
     * The change the review is about, as {@code base_sha} and {@code head_sha}. The container derives
     * every view of it — the patch, its statistics, its commits — from the checkout with {@code git};
     * this file is what pins the range those views are of, and the artifact a diff citation names.
     */
    public static final String CHANGE_FILE = OUTPUT_PREFIX + "change.json";

    /**
     * The description as its author wrote it, one line per line. {@code metadata.json} carries the same
     * text as a JSON string for programs; a review quotes the description from here, where a sentence is
     * the bytes it reads, not their escaped form.
     */
    public static final String DESCRIPTION_FILE = OUTPUT_PREFIX + "description.md";

    /**
     * The commits of the change, oldest first, each with its message and file changes. Staged rather
     * than derived in the container because a quote of a commit message is verified against the
     * artifact it cites, and admission verifies only what the server staged: a review that reads the
     * commits from a file git wrote under {@code work/} has nothing it may cite.
     */
    public static final String COMMITS_FILE = OUTPUT_PREFIX + "commits.json";

    @Override
    public SourceKind sourceKindFor(String path) {
        if (path.endsWith("comments.json")) return COMMENTS;
        if (path.equals(CHANGE_FILE)) return DIFF;
        return CORE;
    }

    static final int MAX_COMMENTS = EvidenceLimits.MAX_ITEMS_PER_SOURCE;

    /**
     * The prefix every note Hephaestus posts on a line carries ({@code <!-- hephaestus-diff-note -->},
     * {@code <!-- hephaestus-approved-package:… -->}): a comment holding it is the tool's own earlier
     * feedback, not a reviewer's, and is never fed back as one.
     */
    static final String HEPHAESTUS_MARKER = "<!-- hephaestus";

    private final ObjectMapper objectMapper;
    private final GitRepositoryManager gitRepositoryManager;
    private final PullRequestRepository pullRequestRepository;
    private final PullRequestReviewCommentRepository reviewCommentRepository;
    private final ReviewRepositoryPreparer repositoryPreparer;

    public PullRequestContentSource(
            ObjectMapper objectMapper,
            GitRepositoryManager gitRepositoryManager,
            PullRequestRepository pullRequestRepository,
            PullRequestReviewCommentRepository reviewCommentRepository,
            ReviewRepositoryPreparer repositoryPreparer) {
        this.objectMapper = objectMapper;
        this.gitRepositoryManager = gitRepositoryManager;
        this.pullRequestRepository = pullRequestRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.repositoryPreparer = repositoryPreparer;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof ContextRequest.PracticeReviewRequest;
    }

    @Override
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        throw new UnsupportedOperationException("Pull request evidence records its capture state; use capture()");
    }

    @Override
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        if (!(request instanceof ContextRequest.PracticeReviewRequest practiceReview)) {
            throw new IllegalStateException("PullRequestContentSource.contribute called with unsupported variant: "
                    + request.getClass().getSimpleName());
        }
        AgentJob job = practiceReview.job();
        JsonNode metadata = job.getMetadata();
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            throw new JobPreparationException("Job has no metadata: jobId=" + job.getId());
        }
        long repositoryId = requireLong(metadata, "repository_id");
        long pullRequestId = requireLong(metadata, "pull_request_id");
        PullRequest pullRequest =
                pullRequestRepository.findByIdForReviewContext(pullRequestId).orElse(null);
        if (pullRequest == null || pullRequest.getDeletedAt() != null) {
            return EvidenceContribution.unavailable(selectedKinds, SourceAbsenceReason.NOT_FOUND);
        }
        ReviewRepositoryPreparer.PreparedReview prepared = null;
        if (readsClone(selectedKinds) && gitRepositoryManager.isEnabled()) {
            prepared = practiceReview.preparation().prepare(repositoryPreparer, job);
        } else {
            repositoryPreparer.authorize(job);
        }
        Map<String, byte[]> files = new HashMap<>();
        Map<SourceKind, SourceCompleteness> completeness = new HashMap<>();
        Map<SourceKind, String> identities = new HashMap<>();
        Map<SourceKind, Instant> observedAt = new HashMap<>();
        Map<SourceKind, SourceContentState> contentStates = new HashMap<>();

        if (readsClone(selectedKinds)) {
            ensureRepositoryAvailable(new RepositoryKey(job.getWorkspace().getId(), repositoryId));
        }
        if (selectedKinds.contains(CORE)) {
            storeMetadata(files, pullRequest, metadata);
            files.put(
                    DESCRIPTION_FILE,
                    (pullRequest.getBody() == null ? "" : pullRequest.getBody()).getBytes(StandardCharsets.UTF_8));
            completeness.put(CORE, SourceCompleteness.COMPLETE);
            if (pullRequest.getLastSyncAt() != null) {
                observedAt.put(CORE, pullRequest.getLastSyncAt());
            }
        }
        if (selectedKinds.contains(COMMENTS)) {
            CommentCapture comments = loadComments(pullRequestId);
            storeComments(files, comments.comments());
            completeness.put(COMMENTS, comments.complete() ? SourceCompleteness.COMPLETE : SourceCompleteness.PARTIAL);
            contentStates.put(
                    COMMENTS, comments.comments().isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY);
        }
        if (prepared != null) {
            String range = prepared.target() + ":" + prepared.head();
            if (selectedKinds.contains(CORE)) {
                identities.put(CORE, range);
                storeCommits(files, prepared);
            }
            if (selectedKinds.contains(DIFF)) {
                storeChange(files, prepared);
                completeness.put(DIFF, SourceCompleteness.COMPLETE);
                identities.put(DIFF, range);
                contentStates.put(
                        DIFF,
                        gitRepositoryManager
                                        .changedPaths(prepared.key(), prepared.target(), prepared.head())
                                        .isEmpty()
                                ? SourceContentState.EMPTY
                                : SourceContentState.NON_EMPTY);
            }
        }
        return new EvidenceContribution(files, completeness, identities, observedAt, Map.of(), contentStates);
    }

    private static boolean readsClone(Set<SourceKind> selectedKinds) {
        return selectedKinds.contains(CORE) || selectedKinds.contains(DIFF);
    }

    private void ensureRepositoryAvailable(RepositoryKey repositoryId) {
        if (!gitRepositoryManager.isEnabled()) {
            throw new JobPreparationException(
                    "Git local storage is disabled but required for repository evidence: repoId=" + repositoryId);
        }
        if (!gitRepositoryManager.isRepositoryCloned(repositoryId)) {
            throw new JobPreparationException(
                    "Repository is not available locally for evidence capture: repoId=" + repositoryId);
        }
    }

    private void storeMetadata(Map<String, byte[]> files, PullRequest pullRequest, JsonNode metadata) {
        ObjectNode pullRequestMetadata = buildPullRequestMetadata(pullRequest, metadata);
        try {
            files.put(
                    OUTPUT_PREFIX + "metadata.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(pullRequestMetadata));
        } catch (JacksonException e) {
            throw new JobPreparationException("Failed to serialize pull request metadata", e);
        }
    }

    private void storeChange(Map<String, byte[]> files, ReviewRepositoryPreparer.PreparedReview prepared) {
        ObjectNode change = objectMapper.createObjectNode();
        change.put("base_sha", prepared.target());
        change.put("head_sha", prepared.head());
        try {
            files.put(CHANGE_FILE, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(change));
        } catch (JacksonException e) {
            throw new JobPreparationException("Failed to serialize the reviewed change", e);
        }
    }

    private void storeCommits(Map<String, byte[]> files, ReviewRepositoryPreparer.PreparedReview prepared) {
        List<CommitDetails> commits =
                gitRepositoryManager.commitsBetween(prepared.key(), prepared.target(), prepared.head());
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode array = root.putArray("commits");
        for (CommitDetails commit : commits) {
            ObjectNode node = array.addObject();
            node.put("sha", commit.sha());
            ArrayNode parents = node.putArray("parents");
            commit.parentShas().forEach(parents::add);
            // Names, never addresses: the record names who did what, and an address is not that.
            node.put("author", commit.authorName());
            node.put("authoredAt", commit.authoredAt().toString());
            node.put("committer", commit.committerName());
            node.put("committedAt", commit.committedAt().toString());
            node.put(
                    "message",
                    commit.messageBody() == null ? commit.message() : commit.message() + "\n\n" + commit.messageBody());
            ArrayNode changes = node.putArray("files");
            for (CommitDetails.FileChange change : commit.fileChanges()) {
                ObjectNode file = changes.addObject();
                file.put("path", change.filename());
                file.put("status", statusLetter(change.changeType()));
                if (change.previousFilename() != null) {
                    file.put("oldPath", change.previousFilename());
                }
                file.put("additions", change.additions());
                file.put("deletions", change.deletions());
            }
        }
        try {
            files.put(
                    COMMITS_FILE, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        } catch (JacksonException e) {
            throw new JobPreparationException("Failed to serialize the commits of the change", e);
        }
    }

    /** The status letter {@code git diff --name-status} prints, which is what the container's readers parse. */
    private static String statusLetter(ChangeType changeType) {
        return switch (changeType) {
            case ADDED -> "A";
            case MODIFIED -> "M";
            case REMOVED -> "D";
            case RENAMED -> "R";
            case COPIED -> "C";
            // The mirror's diff never yields these two; a provider's own sync does.
            case CHANGED -> "M";
            case UNKNOWN -> "M";
        };
    }

    private void storeComments(Map<String, byte[]> files, List<PullRequestReviewComment> comments) {
        JsonNode serialized = buildReviewComments(comments);
        try {
            files.put(
                    OUTPUT_PREFIX + "comments.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(serialized));
        } catch (JacksonException e) {
            throw new JobPreparationException("Failed to serialize review comments", e);
        }
    }

    private ObjectNode buildPullRequestMetadata(PullRequest pullRequest, JsonNode jobMetadata) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("pr_number", requireInt(jobMetadata, "pr_number"));
        result.put("pr_url", requireText(jobMetadata, "pr_url"));
        result.put("repository_full_name", requireText(jobMetadata, "repository_full_name"));
        result.put("source_branch", requireText(jobMetadata, "source_branch"));
        result.put("target_branch", requireText(jobMetadata, "target_branch"));
        result.put("commit_sha", requireText(jobMetadata, "commit_sha"));

        result.put("title", pullRequest.getTitle());
        result.put("body", pullRequest.getBody());
        if (pullRequest.getState() != null) {
            result.put("state", pullRequest.getState().name());
        }
        result.put("is_draft", pullRequest.isDraft());
        // Stated on its own because state alone does not carry it: a pull request the webhook closed
        // after the merge is stored CLOSED with merged_at set, and is merged.
        result.put("is_merged", pullRequest.isMerged());
        // The moments a review places the state of the work against: a merge before the last thread
        // was resolved is a different fact from one after it, and only a dated record can tell them apart.
        putInstant(result, "created_at", pullRequest.getCreatedAt());
        putInstant(result, "closed_at", pullRequest.getClosedAt());
        putInstant(result, "merged_at", pullRequest.getMergedAt());
        result.put("additions", pullRequest.getAdditions());
        result.put("deletions", pullRequest.getDeletions());
        result.put("changed_files", pullRequest.getChangedFiles());
        if (pullRequest.getAuthor() != null) {
            result.put("author", pullRequest.getAuthor().getLogin());
            // The provider's own classification, as the adapter stored it; absent means a person.
            if (pullRequest.getAuthor().getType() == User.Type.BOT) {
                result.put("author_bot", true);
            }
        }
        // Who landed it: a practice about the act of merging reads this beside merged_at and the
        // approvals in review_threads.json, and says nothing when the two people differ.
        if (pullRequest.getMergedBy() != null) {
            result.put("merged_by", pullRequest.getMergedBy().getLogin());
        }
        ArrayNode labels = result.putArray("labels");
        pullRequest.getLabels().stream().map(Label::getName).sorted().forEach(labels::add);
        ArrayNode assignees = result.putArray("assignees");
        pullRequest.getAssignees().stream().map(User::getLogin).sorted().forEach(assignees::add);
        if (pullRequest.getMilestone() != null) {
            result.put("milestone", pullRequest.getMilestone().getTitle());
        }
        // Both come from the GraphQL sync only; a webhook-only record has neither, and an absent key
        // says so rather than a null a program might read as a value.
        if (pullRequest.getMergeStateStatus() != null) {
            result.put("merge_state_status", pullRequest.getMergeStateStatus().name());
        }
        if (pullRequest.getReviewDecision() != null) {
            result.put("review_decision", pullRequest.getReviewDecision().name());
        }

        return result;
    }

    private static void putInstant(ObjectNode node, String field, @Nullable Instant instant) {
        if (instant != null) node.put(field, instant.toString());
    }

    private CommentCapture loadComments(long pullRequestId) {
        var comments = new ArrayList<>(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(
                pullRequestId, HEPHAESTUS_MARKER, PageRequest.of(0, MAX_COMMENTS + 1)));
        if (comments.size() > MAX_COMMENTS + 1) {
            comments = new ArrayList<>(comments.subList(0, MAX_COMMENTS + 1));
        }
        boolean complete = comments.size() <= MAX_COMMENTS;
        if (!complete) comments.remove(comments.size() - 1);
        comments.sort(Comparator.comparing(
                PullRequestReviewComment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return new CommentCapture(comments, complete);
    }

    private JsonNode buildReviewComments(List<PullRequestReviewComment> comments) {
        var commentsArray = objectMapper.createArrayNode();
        for (var comment : comments) {
            var commentNode = objectMapper.createObjectNode();
            // The stored ids, so a review can say which thread a comment belongs to and which comment it
            // answers: `thread` is the `id` of an entry in review_threads.json, `in_reply_to` the `id` of
            // another comment here.
            if (comment.getId() != null) {
                commentNode.put("id", comment.getId());
            }
            if (comment.getThread() != null && comment.getThread().getId() != null) {
                commentNode.put("thread", comment.getThread().getId());
            }
            if (comment.getInReplyTo() != null && comment.getInReplyTo().getId() != null) {
                commentNode.put("in_reply_to", comment.getInReplyTo().getId());
            }
            commentNode.put("path", comment.getPath());
            // line is a primitive int; a file-level comment has no anchor and reports 0. Omit the key then,
            // so an absent anchor reads as absent rather than as a literal line-0 anchor.
            if (comment.getLine() > 0) {
                commentNode.put("line", comment.getLine());
            }
            // LEFT is a line of the base, RIGHT a line of the head; the provider's UNKNOWN says nothing.
            PullRequestReviewComment.Side side = comment.getSide();
            if (side != null && side != PullRequestReviewComment.Side.UNKNOWN) {
                commentNode.put("side", side.name());
            }
            if (Boolean.TRUE.equals(comment.getOutdated())) {
                commentNode.put("outdated", true);
            }
            commentNode.put("body", comment.getBody());
            if (comment.getCreatedAt() != null) {
                commentNode.put("created_at", comment.getCreatedAt().toString());
            }
            if (comment.getAuthor() != null) {
                commentNode.put("author", comment.getAuthor().getLogin());
                if (comment.getAuthor().getType() == User.Type.BOT) {
                    commentNode.put("bot", true);
                }
            }
            commentsArray.add(commentNode);
        }
        return commentsArray;
    }

    private record CommentCapture(List<PullRequestReviewComment> comments, boolean complete) {}
}
