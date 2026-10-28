package de.tum.cit.aet.hephaestus.integration.scm.domain.capture;

import static de.tum.cit.aet.hephaestus.integration.scm.domain.capture.ScmCaptureContext.OUTPUT_PREFIX;

import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CitableText;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConditionalOnCaptureRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureCode;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemCaptureException;
import de.tum.cit.aet.hephaestus.integration.scm.context.WorkspaceScmProjection;
import de.tum.cit.aet.hephaestus.integration.scm.domain.commit.CommitDetails;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.HeadCheckRuns;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
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
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Component
@ConditionalOnCaptureRole
@Order(100)
public class PullRequestContentSource {

    private static final int MAX_COMMIT_RECORD_BYTES = 4 * 1024 * 1024;

    public static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    public static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    public static final SourceKind COMMENTS = new SourceKind("scm.pull-request.comments");

    public Set<SourceKind> sourceKinds() {
        return Set.of(CORE, DIFF, COMMENTS);
    }

    /** Pins {@code base_sha} and {@code head_sha} for derived change views and diff citations. */
    public static final String CHANGE_FILE = OUTPUT_PREFIX + "change.json";

    /**
     * Unescaped author text for line-based citations; metadata.json also contains it as a JSON
     * string.
     */
    public static final String DESCRIPTION_FILE = OUTPUT_PREFIX + "description.md";

    /**
     * Commit messages and file changes, in no promised order. Staged on the server so admission can
     * verify citations of commit messages.
     */
    public static final String COMMITS_FILE = OUTPUT_PREFIX + "commits.json";

    public SourceKind sourceKindFor(String path) {
        var recordSource = ScmCitableTexts.recordSource(path);
        if (recordSource != null && sourceKinds().contains(recordSource)) return recordSource;
        if (path.endsWith("comments.json")) return COMMENTS;
        if (path.equals(CHANGE_FILE) || path.startsWith("context/citable/")) return DIFF;
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

    public CaptureContribution capture(ScmCaptureContext request, Set<SourceKind> selectedKinds) {
        if (!request.pullRequest()) {
            throw new IllegalStateException("PullRequestContentSource.capture called with unsupported variant: "
                    + request.getClass().getSimpleName());
        }
        ScmCaptureContext capture = request;
        ScmCapturedWork metadata = capture.work();
        long repositoryId = metadata.repositoryId();
        long pullRequestId = metadata.sourceId();
        PullRequest pullRequest =
                pullRequestRepository.findByIdForReviewContext(pullRequestId).orElse(null);
        if (pullRequest == null || pullRequest.getDeletedAt() != null) {
            return CaptureContribution.unavailable(selectedKinds, SourceAbsenceReason.NOT_FOUND);
        }
        ReviewRepositoryPreparer.PreparedReview prepared = null;
        var occasion = capture.occasion();
        // A review submitted on an older commit attests no diff of the occasion's pair: its words stay without code.
        boolean pairAttested = occasion == null || occasion.pairAttested();
        boolean cloneRequested = pairAttested
                && (readsClone(selectedKinds)
                        || (selectedKinds.contains(CORE)
                                && request.request().permittedSources().contains(DIFF)));
        boolean cloneEnabled = cloneRequested && gitRepositoryManager.isEnabled();
        if (cloneEnabled) {
            prepared = request.preparation().prepare(repositoryPreparer, capture);
        } else {
            repositoryPreparer.authorize(capture);
        }
        Map<String, byte[]> files = new HashMap<>();
        Map<SourceKind, SourceCompleteness> completeness = new HashMap<>();
        Map<SourceKind, String> identities = new HashMap<>();
        Map<SourceKind, SourceContentState> contentStates = new HashMap<>();
        Map<SourceKind, List<String>> limitations = new HashMap<>();
        List<CitableText> citable = new ArrayList<>();

        Map<SourceKind, SourceCaptureState> states = selectedKinds.contains(DIFF) && !cloneEnabled
                ? Map.of(DIFF, new SourceCaptureState.NotCollected(SourceAbsenceReason.DISABLED))
                : Map.of();
        var unavailable = request.preparation().unavailableReason();
        if (selectedKinds.contains(DIFF) && unavailable != null)
            states = Map.of(DIFF, new SourceCaptureState.Unavailable(unavailable));
        if (selectedKinds.contains(DIFF) && !pairAttested)
            states = Map.of(DIFF, new SourceCaptureState.Unavailable(SourceAbsenceReason.REVIEW_VERSION_UNAVAILABLE));
        if (prepared != null) {
            ensureRepositoryAvailable(new RepositoryKey(capture.workspaceId(), repositoryId));
        }
        if (selectedKinds.contains(CORE)) {
            // An event review stages the record its occasion retained, whole: today's words and status never join it.
            ObjectNode staged =
                    occasion != null ? occasion.record() : workRecord(pullRequest, metadata, capture.capturedAt());
            storeMetadata(files, staged);
            // The words of the verified pinned version, read at capture. A body known to be absent
            // stages an empty description; a body the record does not carry as text stays unknown,
            // so no description is staged.
            JsonNode body = staged.get("body");
            if (body != null) {
                files.put(DESCRIPTION_FILE, (body.isNull() ? "" : body.asString()).getBytes(StandardCharsets.UTF_8));
            }
            List<String> unknown = new ArrayList<>();
            if (!staged.has("title")) unknown.add("RETAINED_TITLE_UNKNOWN");
            if (body == null) unknown.add("RETAINED_BODY_UNKNOWN");
            completeness.put(CORE, unknown.isEmpty() ? SourceCompleteness.COMPLETE : SourceCompleteness.PARTIAL);
            if (!unknown.isEmpty()) limitations.put(CORE, unknown);
            // No observedAt: the record mixes words read at capture with status as last synced,
            // and the sync time would date the words to a moment they were not read.
            // metadata.json dates each part.
        }
        if (selectedKinds.contains(COMMENTS)) {
            CommentCapture comments = loadComments(capture.workspaceId(), pullRequestId);
            storeComments(files, comments);
            completeness.put(COMMENTS, comments.complete() ? SourceCompleteness.COMPLETE : SourceCompleteness.PARTIAL);
            contentStates.put(
                    COMMENTS, comments.comments().isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY);
        }
        if (prepared != null) {
            // Commit words stay personal CORE records; the authorized DIFF plan supplies their pinned range.
            if (selectedKinds.contains(CORE)) storeCommits(files, prepared);
            String range = prepared.target() + ":" + prepared.head();
            if (selectedKinds.contains(DIFF)) {
                var changedPaths =
                        gitRepositoryManager.changedPaths(prepared.key(), prepared.target(), prepared.head());
                storeChange(files, prepared, changedPaths);
                citable.addAll(ScmCitableTexts.changed(gitRepositoryManager, prepared, files));
                completeness.put(DIFF, SourceCompleteness.COMPLETE);
                identities.put(DIFF, range);
                contentStates.put(
                        DIFF, changedPaths.isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY);
            }
        }
        return new CaptureContribution(
                files,
                completeness,
                identities,
                Map.of(),
                Map.of(),
                contentStates,
                states,
                Map.of(),
                null,
                limitations,
                List.of(),
                List.of(),
                Stream.concat(
                                citable.stream(),
                                ScmCitableTexts.files(files, this::sourceKindFor, objectMapper).stream()
                                        .filter(text -> !text.stagedPath().startsWith("context/citable/")))
                        .toList());
    }

    private static boolean readsClone(Set<SourceKind> selectedKinds) {
        return selectedKinds.contains(DIFF);
    }

    private void ensureRepositoryAvailable(RepositoryKey repositoryId) {
        if (!gitRepositoryManager.isRepositoryCloned(repositoryId)) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED,
                    "Repository is not available locally for evidence capture: repoId=" + repositoryId);
        }
    }

    private void storeMetadata(Map<String, byte[]> files, JsonNode content) {
        try {
            files.put(
                    OUTPUT_PREFIX + "metadata.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(content));
        } catch (JacksonException e) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "Failed to serialize pull request metadata", e);
        }
    }

    private void storeChange(
            Map<String, byte[]> files, ReviewRepositoryPreparer.PreparedReview prepared, Set<String> changedPaths) {
        ObjectNode change = objectMapper.createObjectNode();
        change.put("base_sha", prepared.target());
        change.put("head_sha", prepared.head());
        change.set(
                "changed_paths",
                objectMapper.valueToTree(changedPaths.stream().sorted().toList()));
        change.put("diff", gitRepositoryManager.unifiedDiff(prepared.key(), prepared.target(), prepared.head()));
        try {
            files.put(CHANGE_FILE, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(change));
        } catch (JacksonException e) {
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "Failed to serialize the reviewed change", e);
        }
    }

    private void storeCommits(Map<String, byte[]> files, ReviewRepositoryPreparer.PreparedReview prepared) {
        var root = objectMapper.createObjectNode();
        var commits = root.putArray("commits");
        long[] bytes = {0};
        gitRepositoryManager.forEachCommitBetween(prepared.key(), prepared.target(), prepared.head(), commit -> {
            ObjectNode record = commitRecord(commit);
            bytes[0] += objectMapper.writeValueAsBytes(record).length;
            if (bytes[0] > MAX_COMMIT_RECORD_BYTES)
                throw new ItemCaptureException(
                        ItemCaptureCode.CAPTURE_FAILED, "Captured commit records exceed the size limit");
            commits.add(record);
        });
        byte[] captured = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        if (captured.length > MAX_COMMIT_RECORD_BYTES)
            throw new ItemCaptureException(
                    ItemCaptureCode.CAPTURE_FAILED, "Captured commit records exceed the size limit");
        files.put(COMMITS_FILE, captured);
    }

    private ObjectNode commitRecord(CommitDetails commit) {
        var record = objectMapper.createObjectNode();
        record.put("sha", commit.sha());
        record.put("message", commit.rawMessage());
        record.put("author", commit.authorName());
        record.put("authoredAt", commit.authoredAt().toString());
        record.put("committer", commit.committerName());
        record.put("committedAt", commit.committedAt().toString());
        var parents = record.putArray("parents");
        commit.parentShas().forEach(parents::add);
        var files = record.putArray("files");
        for (var change : commit.fileChanges()) {
            var file = files.addObject();
            file.put("path", change.filename());
            file.put(
                    "status",
                    switch (change.changeType()) {
                        case ADDED -> "A";
                        case MODIFIED -> "M";
                        case REMOVED -> "D";
                        case RENAMED -> "R";
                        case COPIED -> "C";
                        case CHANGED -> "T";
                        case UNKNOWN -> "U";
                    });
            if (change.previousFilename() != null) file.put("oldPath", change.previousFilename());
            file.put("additions", change.additions());
            file.put("deletions", change.deletions());
        }
        return record;
    }

    private void storeComments(Map<String, byte[]> files, CommentCapture comments) {
        JsonNode serialized = buildReviewComments(comments.comments(), comments.delivered());
        try {
            files.put(
                    OUTPUT_PREFIX + "comments.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(serialized));
        } catch (JacksonException e) {
            throw new ItemCaptureException(ItemCaptureCode.CAPTURE_FAILED, "Failed to serialize review comments", e);
        }
    }

    /**
     * The pull request's work record, {@code context/metadata.json}, as the practice runner reads
     * it ({@code WORK_RECORDS["scm.pull_request"]} in {@code lib/contract.ts}): the mirror as read
     * at capture, after the capture verified it against the pinned version.
     */
    public static ObjectNode workRecord(PullRequest pullRequest, ScmCapturedWork work, @Nullable Instant capturedAt) {
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        // Which time each part describes. Every field is read at capture: that time is neither the
        // provider event time nor the admission time, and it recovers no earlier snapshot. The
        // mirror sync time dates the status fields only.
        ObjectNode basis = result.putObject("basis");
        basis.put("other_fields", "CAPTURE");
        putInstant(basis, "captured_at", capturedAt);
        putInstant(basis, "mirror_synced_at", pullRequest.getLastSyncAt());
        result.put("pr_number", work.number());
        result.put("pr_url", work.url());
        result.put("repository_full_name", work.repositoryPath());
        // Preserve the pinned repository identity; the mirror supplies its provider only while it
        // still matches.
        result.put("repository_id", work.repositoryId());
        var repository = pullRequest.getRepository();
        if (repository != null && Objects.equals(repository.getId(), work.repositoryId()))
            result.put("provider_id", repository.getProvider().getId());
        result.put("source_branch", work.sourceBranch());
        result.put("target_branch", work.targetBranch());
        String reviewedHead = Objects.requireNonNull(work.head(), "Pinned pull request has no head");
        result.put("commit_sha", reviewedHead);
        result.put("subject_role", work.reviewSourceId() == null ? "AUTHOR" : "REVIEWER");

        // Equal to the pinned version's words: the capture refuses a version whose title or body changed.
        result.put("title", work.title());
        result.put("body", work.body());
        if (pullRequest.getState() != null) {
            result.put("state", pullRequest.getState().name());
        }
        result.put("is_draft", pullRequest.isDraft());
        // Stated on its own because state alone does not carry it: a pull request the webhook
        // closed after the merge is stored CLOSED with merged_at set, and is merged.
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
        // Both come from the GraphQL sync only; a webhook-only record has neither, and an absent
        // key says so rather than a null a program might read as a value.
        if (pullRequest.getMergeStateStatus() != null) {
            result.put("merge_state_status", pullRequest.getMergeStateStatus().name());
        }
        if (pullRequest.getReviewDecision() != null) {
            result.put("review_decision", pullRequest.getReviewDecision().name());
        }
        // What the checks said about the reviewed head, as last observed. A state observed for any
        // other commit, the current mirror head included, is not about the reviewed change and is
        // left out, as is a head no sync or event has reported on.
        CheckState checkState = pullRequest.getHeadCheckState();
        if (checkState != null && reviewedHead.equals(pullRequest.getHeadCheckSha())) {
            result.put("head_checks", checkState.name());
        }
        // The single runs behind that state, under the same rule. An absent key says no read listed them for the
        // reviewed head, which is not the same as a head without runs.
        HeadCheckRuns checkRuns = pullRequest.getHeadCheckRuns();
        if (checkRuns != null && reviewedHead.equals(checkRuns.sha())) {
            ObjectNode node = result.putObject("head_check_runs");
            node.put("complete", checkRuns.complete());
            if (checkRuns.limitation() != null) node.put("limitation", checkRuns.limitation());
            putInstant(node, "observed_at", checkRuns.observedAt());
            ArrayNode runs = node.putArray("runs");
            for (HeadCheckRuns.Run run : checkRuns.runs()) {
                ObjectNode entry = runs.addObject();
                entry.put("name", run.name());
                if (run.group() != null) entry.put("group", run.group());
                entry.put("state", run.state());
                entry.put("finished", run.finished());
                if (run.url() != null) entry.put("url", run.url());
                putInstant(entry, "started_at", run.startedAt());
                putInstant(entry, "finished_at", run.finishedAt());
            }
        }

        return result;
    }

    /**
     * The work record of an occasion, as {@link #workRecord} reads it in the transaction that records the occasion.
     * Its basis says so: every field describes the work when the occasion was observed, which is when Hephaestus stored
     * it, not a provider time. The mirror sync time dates the status fields only.
     */
    public static ObjectNode occasionRecord(PullRequest pullRequest, ScmCapturedWork work, Instant observedAt) {
        ObjectNode result = workRecord(pullRequest, work, null);
        ObjectNode basis = (ObjectNode) result.get("basis");
        basis.put("other_fields", "OCCASION");
        putInstant(basis, "observed_at", observedAt);
        return result;
    }

    private static void putInstant(ObjectNode node, String field, @Nullable Instant instant) {
        if (instant != null) node.put(field, instant.toString());
    }

    private CommentCapture loadComments(long workspaceId, long pullRequestId) {
        Set<Long> postedComments = deliveredCommentLookup
                .findForPullRequest(workspaceId, pullRequestId, ScmItemEvents.PULL_REQUEST)
                .inline();
        var comments = new ArrayList<>(reviewCommentRepository.findRecentHumanByPullRequestIdWithAuthor(
                pullRequestId, WorkspaceScmProjection.HEPHAESTUS_MARKER, Pageable.unpaged()));
        comments.removeIf(comment -> comment.getNativeId() != null && postedComments.contains(comment.getNativeId()));
        boolean complete = true;
        comments.sort(Comparator.comparing(
                PullRequestReviewComment::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return new CommentCapture(comments, postedComments, complete);
    }

    private JsonNode buildReviewComments(List<PullRequestReviewComment> comments, Set<Long> delivered) {
        var commentsArray = objectMapper.createArrayNode();
        for (var comment : comments) {
            var commentNode = objectMapper.createObjectNode();
            // Thread and parent-comment IDs link these entries to review_threads.json and
            // comments.json.
            if (comment.getId() != null) {
                commentNode.put("id", comment.getId());
            }
            if (comment.getThread() != null && comment.getThread().getId() != null) {
                commentNode.put("thread", comment.getThread().getId());
            }
            PullRequestReviewComment parent = comment.getInReplyTo();
            if (parent != null && parent.getId() != null) {
                commentNode.put("in_reply_to", parent.getId());
                String omitted = omittedParentReason(parent, delivered);
                if (omitted != null) commentNode.put("in_reply_to_omitted_reason", omitted);
            }
            commentNode.put("path", comment.getPath());
            // line is a primitive int; a file-level comment has no anchor and reports 0. Omit the
            // key then, so an absent anchor reads as absent rather than as a literal line-0 anchor.
            if (comment.getLine() > 0) {
                commentNode.put("line", comment.getLine());
            }
            // LEFT is a line of the base, RIGHT a line of the head; the provider's UNKNOWN says
            // nothing.
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

    /**
     * Why the capture left a reply's parent out, by the rule that left it out: the delivered-feedback ledger
     * first, then the feedback marker that the repository query filters on. A person can quote the marker, so
     * that reason names the rule, not who wrote the parent. A parent that no rule left out has no reason.
     */
    private static @Nullable String omittedParentReason(PullRequestReviewComment parent, Set<Long> delivered) {
        if (parent.getNativeId() != null && delivered.contains(parent.getNativeId())) return "DELIVERED_FEEDBACK";
        String body = parent.getBody();
        if (body != null && body.contains(WorkspaceScmProjection.HEPHAESTUS_MARKER)) return "FEEDBACK_MARKER";
        return null;
    }

    private record CommentCapture(List<PullRequestReviewComment> comments, Set<Long> delivered, boolean complete) {}
}
