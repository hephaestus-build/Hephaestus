package de.tum.cit.aet.hephaestus.agent.context.providers;

import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireInt;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireLong;
import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireText;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewContextBuilder;
import de.tum.cit.aet.hephaestus.integration.scm.context.WorkspaceScmProjection;
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
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@Order(100)
public class PullRequestContentSource implements EvidenceSource, ReviewContextBuilder {

    public static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    public static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    public static final SourceKind COMMENTS = new SourceKind("scm.pull-request.comments");

    /** Checked by the integration framework against every descriptor that calls itself reviewable. */
    @Override
    public ArtifactKind artifactKind() {
        return ScmSignals.PULL_REQUEST;
    }

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(CORE, DIFF, COMMENTS);
    }

    /** Pins {@code base_sha} and {@code head_sha} for derived change views and diff citations. */
    public static final String CHANGE_FILE = OUTPUT_PREFIX + "change.json";

    /** Unescaped author text for line-based citations; metadata.json also contains it as a JSON string. */
    public static final String DESCRIPTION_FILE = OUTPUT_PREFIX + "description.md";

    /**
     * Commit messages and file changes, in no promised order. Staged on the server so admission can
     * verify citations of commit messages.
     */
    public static final String COMMITS_FILE = OUTPUT_PREFIX + "commits.json";

    @Override
    public SourceKind sourceKindFor(String path) {
        if (path.endsWith("comments.json")) return COMMENTS;
        if (path.equals(CHANGE_FILE)) return DIFF;
        return CORE;
    }

    private final ObjectMapper objectMapper;
    private final DeliveredPullRequestCommentLookup deliveredCommentLookup;
    private final GitRepositoryManager gitRepositoryManager;
    private final PullRequestRepository pullRequestRepository;
    private final PullRequestReviewCommentRepository reviewCommentRepository;
    private final ReviewRepositoryPreparer repositoryPreparer;

    public PullRequestContentSource(
            ObjectMapper objectMapper,
            GitRepositoryManager gitRepositoryManager,
            PullRequestRepository pullRequestRepository,
            PullRequestReviewCommentRepository reviewCommentRepository,
            ReviewRepositoryPreparer repositoryPreparer,
            DeliveredPullRequestCommentLookup deliveredCommentLookup) {
        this.objectMapper = objectMapper;
        this.gitRepositoryManager = gitRepositoryManager;
        this.pullRequestRepository = pullRequestRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.repositoryPreparer = repositoryPreparer;
        this.deliveredCommentLookup = deliveredCommentLookup;
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
        boolean cloneRequested = readsClone(selectedKinds);
        boolean cloneEnabled = cloneRequested && gitRepositoryManager.isEnabled();
        if (cloneEnabled) {
            prepared = practiceReview.preparation().prepare(repositoryPreparer, job);
        } else {
            repositoryPreparer.authorize(job);
        }
        Map<String, byte[]> files = new HashMap<>();
        Map<SourceKind, SourceCompleteness> completeness = new HashMap<>();
        Map<SourceKind, String> identities = new HashMap<>();
        Map<SourceKind, SourceContentState> contentStates = new HashMap<>();
        Map<SourceKind, List<String>> limitations = new HashMap<>();

        Map<SourceKind, SourceCaptureState> states = cloneRequested && !cloneEnabled
                ? Map.of(DIFF, new SourceCaptureState.NotCollected(SourceAbsenceReason.DISABLED))
                : Map.of();
        if (cloneEnabled) {
            ensureRepositoryAvailable(new RepositoryKey(job.getWorkspace().getId(), repositoryId));
        }
        if (selectedKinds.contains(CORE)) {
            ObjectNode staged = buildPullRequestMetadata(pullRequest, metadata, job.getCreatedAt());
            storeMetadata(files, staged);
            // The words the job was admitted with. A body known to be absent stages an empty description; a body the
            // job does not carry as text stays unknown, so no description is staged rather than the current one.
            JsonNode body = staged.get("body");
            if (body != null) {
                files.put(DESCRIPTION_FILE, (body.isNull() ? "" : body.asString()).getBytes(StandardCharsets.UTF_8));
            }
            List<String> unknown = new ArrayList<>();
            if (!staged.has("title")) unknown.add("RETAINED_TITLE_UNKNOWN");
            if (body == null) unknown.add("RETAINED_BODY_UNKNOWN");
            completeness.put(CORE, unknown.isEmpty() ? SourceCompleteness.COMPLETE : SourceCompleteness.PARTIAL);
            if (!unknown.isEmpty()) limitations.put(CORE, unknown);
            // No observedAt: the record mixes words retained at admission with status as last synced, and
            // the sync time would date the words to a moment they were not read. metadata.json dates each part.
        }
        if (selectedKinds.contains(COMMENTS)) {
            CommentCapture comments = loadComments(job.getWorkspace().getId(), pullRequestId);
            storeComments(files, comments.comments());
            completeness.put(COMMENTS, comments.complete() ? SourceCompleteness.COMPLETE : SourceCompleteness.PARTIAL);
            contentStates.put(
                    COMMENTS, comments.comments().isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY);
        }
        if (prepared != null) {
            String range = prepared.target() + ":" + prepared.head();
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
        return new EvidenceContribution(
                files,
                completeness,
                identities,
                Map.of(),
                Map.of(),
                contentStates,
                states,
                Map.of(),
                null,
                limitations);
    }

    private static boolean readsClone(Set<SourceKind> selectedKinds) {
        return selectedKinds.contains(DIFF);
    }

    private void ensureRepositoryAvailable(RepositoryKey repositoryId) {
        if (!gitRepositoryManager.isRepositoryCloned(repositoryId)) {
            throw new JobPreparationException(
                    "Repository is not available locally for evidence capture: repoId=" + repositoryId);
        }
    }

    private void storeMetadata(Map<String, byte[]> files, JsonNode content) {
        try {
            files.put(
                    OUTPUT_PREFIX + "metadata.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(content));
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

    /** The fields copied from the job as it was admitted; every other field is the mirror at capture. */
    private static final List<String> ADMISSION_FIELDS = List.of(
            "pr_number",
            "pr_url",
            "repository_full_name",
            "source_branch",
            "target_branch",
            "commit_sha",
            "subject_role",
            "title",
            "body");

    private ObjectNode buildPullRequestMetadata(
            PullRequest pullRequest, JsonNode jobMetadata, @Nullable Instant admittedAt) {
        ObjectNode result = objectMapper.createObjectNode();
        // Which time each part describes. Admission is when the job was accepted, not when the provider event
        // happened; the mirror sync time dates the status fields only. No status is reconstructed for admission.
        ObjectNode basis = result.putObject("basis");
        ArrayNode admitted = basis.putArray("admission_fields");
        ADMISSION_FIELDS.forEach(admitted::add);
        putInstant(basis, "admitted_at", admittedAt);
        basis.put("other_fields", "CAPTURE");
        putInstant(basis, "mirror_synced_at", pullRequest.getLastSyncAt());
        result.put("pr_number", requireInt(jobMetadata, "pr_number"));
        result.put("pr_url", requireText(jobMetadata, "pr_url"));
        result.put("repository_full_name", requireText(jobMetadata, "repository_full_name"));
        result.put("source_branch", requireText(jobMetadata, "source_branch"));
        result.put("target_branch", requireText(jobMetadata, "target_branch"));
        result.put("commit_sha", requireText(jobMetadata, "commit_sha"));
        result.put(
                "subject_role",
                "REVIEWER".equals(MetaJson.optString(jobMetadata, "subject_role")) ? "REVIEWER" : "AUTHOR");

        // Absent key: the job does not carry the text, which is unknown, never the current text instead.
        JsonNode title = jobMetadata.get("title");
        if (title != null && title.isString()) {
            result.put("title", title.asString());
        }
        JsonNode body = jobMetadata.get("body");
        if (body != null && (body.isString() || body.isNull())) {
            result.put("body", body.isNull() ? null : body.asString());
        }
        if (pullRequest.getState() != null) {
            result.put("state", pullRequest.getState().name());
        }
        result.put("is_draft", pullRequest.isDraft());
        // Stated on its own because state alone does not carry it: a pull request the webhook closed
        // after the merge is stored CLOSED with merged_at set, and is merged.
        result.put("is_merged", pullRequest.isMerged());
        putInstant(result, "created_at", pullRequest.getCreatedAt());
        putInstant(result, "closed_at", pullRequest.getClosedAt());
        putInstant(result, "merged_at", pullRequest.getMergedAt());
        result.put("additions", pullRequest.getAdditions());
        result.put("deletions", pullRequest.getDeletions());
        result.put("changed_files", pullRequest.getChangedFiles());
        if (pullRequest.getAuthor() != null) {
            result.put("author", pullRequest.getAuthor().getLogin());
            result.put("author_id", pullRequest.getAuthor().getNativeId());
            if (pullRequest.getAuthor().getType() == User.Type.BOT) {
                result.put("author_bot", true);
            }
        }
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
        // What the checks said about this head; a state observed for an earlier head is not the
        // current one and is left out, as is a head no sync or event has reported on.
        if (pullRequest.getHeadCheckState() != null
                && pullRequest.getHeadCheckSha() != null
                && pullRequest.getHeadCheckSha().equals(pullRequest.getHeadRefOid())) {
            result.put("head_checks", pullRequest.getHeadCheckState().name());
        }

        return result;
    }

    private static void putInstant(ObjectNode node, String field, @Nullable Instant instant) {
        if (instant != null) node.put(field, instant.toString());
    }

    private CommentCapture loadComments(long workspaceId, long pullRequestId) {
        Set<Long> postedComments = deliveredCommentLookup
                .findForPullRequest(workspaceId, pullRequestId)
                .inline();
        var comments = new ArrayList<>(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(
                pullRequestId, WorkspaceScmProjection.HEPHAESTUS_MARKER, Pageable.unpaged()));
        comments.removeIf(comment -> comment.getNativeId() != null && postedComments.contains(comment.getNativeId()));
        boolean complete = true;
        comments.sort(Comparator.comparing(
                PullRequestReviewComment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return new CommentCapture(comments, complete);
    }

    private JsonNode buildReviewComments(List<PullRequestReviewComment> comments) {
        var commentsArray = objectMapper.createArrayNode();
        for (var comment : comments) {
            var commentNode = objectMapper.createObjectNode();
            // Thread and parent-comment IDs link these entries to review_threads.json and comments.json.
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
            commentNode.put("native_id", comment.getNativeId());
            String reviewedRevision = comment.reviewedRevision();
            if (reviewedRevision != null) {
                commentNode.put("commit_id", reviewedRevision);
            }
            commentNode.put("body", comment.getBody());
            if (comment.getCreatedAt() != null) {
                commentNode.put("created_at", comment.getCreatedAt().toString());
            }
            if (comment.getUpdatedAt() != null) {
                commentNode.put("updated_at", comment.getUpdatedAt().toString());
            }
            if (comment.getAuthor() != null) {
                commentNode.put("author", comment.getAuthor().getLogin());
                commentNode.put("author_id", comment.getAuthor().getNativeId());
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
