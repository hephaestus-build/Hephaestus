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
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewContextBuilder;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmTokenSource;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.patch.FormatError;
import org.eclipse.jgit.patch.Patch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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

    @Override
    public SourceKind sourceKindFor(String path) {
        if (path.endsWith("comments.json")) return COMMENTS;
        if (path.endsWith("diff.patch") || path.endsWith("diff_stat.txt") || path.endsWith("diff_summary.md"))
            return DIFF;
        return CORE;
    }

    private static final Logger log = LoggerFactory.getLogger(PullRequestContentSource.class);

    static final int MAX_COMMENTS = EvidenceLimits.MAX_ITEMS_PER_SOURCE;
    static final int MAX_COMMITS = EvidenceLimits.MAX_ITEMS_PER_SOURCE;

    private final ObjectMapper objectMapper;
    private final GitRepositoryManager gitRepositoryManager;
    private final PullRequestRepository pullRequestRepository;
    private final PullRequestReviewCommentRepository reviewCommentRepository;
    private final GitDiffOperations gitDiffOperations;
    private final ConnectionService connectionService;

    private final Map<IntegrationKind, ScmTokenSource> tokenSources;

    public PullRequestContentSource(
            ObjectMapper objectMapper,
            GitRepositoryManager gitRepositoryManager,
            PullRequestRepository pullRequestRepository,
            PullRequestReviewCommentRepository reviewCommentRepository,
            GitDiffOperations gitDiffOperations,
            ConnectionService connectionService,
            List<ScmTokenSource> tokenSourceList) {
        this.objectMapper = objectMapper;
        this.gitRepositoryManager = gitRepositoryManager;
        this.pullRequestRepository = pullRequestRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.gitDiffOperations = gitDiffOperations;
        this.connectionService = connectionService;
        Map<IntegrationKind, ScmTokenSource> map = new EnumMap<>(IntegrationKind.class);
        for (ScmTokenSource src : tokenSourceList) {
            map.put(src.kind(), src);
        }
        this.tokenSources = map;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof ContextRequest.PracticeReviewRequest;
    }

    @Override
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        prepareCapture(request, sourceKinds());
        contributeSelected(request, sourceKinds(), files);
    }

    @Override
    public void prepareCapture(ContextRequest request, Set<SourceKind> selectedKinds) {
        if (!(request instanceof ContextRequest.PracticeReviewRequest practiceReview)) return;
        if (!readsClone(selectedKinds)) return;
        JsonNode metadata = practiceReview.job().getMetadata();
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) return;
        if (!pullRequestRepository.existsByIdAndDeletedAtIsNull(requireLong(metadata, "pull_request_id"))) return;
        long repositoryId = requireLong(metadata, "repository_id");
        if (!gitRepositoryManager.isEnabled() || !gitRepositoryManager.isRepositoryCloned(repositoryId)) return;
        String headSha = metadata.path("commit_sha").asString(null);
        fetchAndVerifyHead(repositoryId, practiceReview.job(), headSha);
    }

    @Override
    public void contributeSelected(ContextRequest request, Set<SourceKind> selectedKinds, Map<String, byte[]> files) {
        files.putAll(captureSelected(request, selectedKinds).files());
    }

    @Override
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        return captureSelected(request, selectedKinds);
    }

    private EvidenceContribution captureSelected(ContextRequest request, Set<SourceKind> selectedKinds) {
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
        PullRequest pullRequest = pullRequestRepository
                .findByIdWithAuthorAndRepository(pullRequestId)
                .orElse(null);
        if (pullRequest == null || pullRequest.getDeletedAt() != null) {
            return EvidenceContribution.unavailable(selectedKinds, SourceAbsenceReason.NOT_FOUND);
        }
        Map<String, byte[]> files = new HashMap<>();
        Map<SourceKind, SourceCompleteness> completeness = new HashMap<>();
        Map<SourceKind, String> identities = new HashMap<>();
        Map<SourceKind, java.time.Instant> observedAt = new HashMap<>();
        Map<SourceKind, SourceContentState> contentStates = new HashMap<>();

        if (selectedKinds.contains(COMMENTS)) {
            CommentCapture comments = loadComments(pullRequestId);
            storeComments(files, comments.comments());
            completeness.put(COMMENTS, comments.complete() ? SourceCompleteness.COMPLETE : SourceCompleteness.PARTIAL);
            contentStates.put(
                    COMMENTS, comments.comments().isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY);
        }
        if (readsClone(selectedKinds)) {
            // The record's commits are read from the clone over the diff's range, so the record fails as the
            // diff does when the clone or the range is unavailable.
            ChangeRange range = resolveChangeRange(repositoryId, metadata);
            if (selectedKinds.contains(CORE)) {
                storeMetadata(files, pullRequest, metadata);
                boolean commitsTruncated = storeCommits(files, range, repositoryId);
                completeness.put(CORE, commitsTruncated ? SourceCompleteness.PARTIAL : SourceCompleteness.COMPLETE);
                if (pullRequest.getLastSyncAt() != null) {
                    observedAt.put(CORE, pullRequest.getLastSyncAt());
                }
            }
            if (selectedKinds.contains(DIFF)) {
                computeAndStoreDiff(files, range, repositoryId);
                completeness.put(DIFF, SourceCompleteness.COMPLETE);
                identities.put(DIFF, range.head());
                byte[] diff = files.get(OUTPUT_PREFIX + "diff.patch");
                contentStates.put(
                        DIFF,
                        diff == null || diff.length == 0 ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY);
            }
        }
        return new EvidenceContribution(files, completeness, identities, observedAt, Map.of(), contentStates);
    }

    private void ensureRepositoryAvailable(long repositoryId) {
        if (!gitRepositoryManager.isEnabled()) {
            throw new JobPreparationException(
                    "Git local storage is disabled but required for repository evidence: repoId=" + repositoryId);
        }
        if (!gitRepositoryManager.isRepositoryCloned(repositoryId)) {
            throw new JobPreparationException(
                    "Repository is not available locally for evidence capture: repoId=" + repositoryId);
        }
    }

    private boolean fetchAndVerifyHead(long repositoryId, AgentJob job, String headSha) {
        if (!gitRepositoryManager.isRepositoryCloned(repositoryId)) {
            log.debug("Repository not cloned locally, skipping fetch: repoId={}", repositoryId);
            return false;
        }

        var workspace = job.getWorkspace();
        var kind = workspace == null
                ? Optional.<IntegrationKind>empty()
                : connectionService.findActiveProviderKind(workspace.getId());
        ScmTokenSource source = kind.map(tokenSources::get).orElse(null);

        boolean fetched = false;
        String serverUrl = null;
        try {
            if (source != null) {
                Long scopeId = workspace.getId();
                serverUrl = source.serverUrl(scopeId).orElse(null);
                String token = source.accessToken(scopeId).orElse(null);
                JsonNode metadata = job.getMetadata();
                String repoFullName = metadata != null && metadata.has("repository_full_name")
                        ? metadata.get("repository_full_name").asString()
                        : null;
                if (serverUrl != null && token != null && repoFullName != null) {
                    String cloneUrl = serverUrl + "/" + repoFullName + ".git";
                    gitRepositoryManager.ensureRepository(repositoryId, cloneUrl, token);
                    fetched = true;
                    long pullRequestNumber = requireLong(
                            Objects.requireNonNull(metadata, "pull request job metadata is required"), "pr_number");
                    Optional<String> reviewHeadRef = source.reviewHeadRef(pullRequestNumber);
                    if (headSha != null && !headSha.isBlank() && reviewHeadRef.isPresent()) {
                        boolean pinnedHeadFetched = gitRepositoryManager.fetchRemoteCommit(
                                repositoryId, reviewHeadRef.get(), headSha, token);
                        if (!pinnedHeadFetched) {
                            log.warn("Remote review ref did not match the pinned commit: repoId={}", repositoryId);
                        }
                    }
                    log.debug("Fetched latest refs: repoId={}", repositoryId);
                }
            }
        } catch (Exception e) {
            log.warn(
                    "Pre-diff fetch failed: repoId={}, kind={}, serverUrl={}",
                    repositoryId,
                    kind.orElse(null),
                    serverUrl,
                    e);
        }

        if (headSha != null && !headSha.isBlank()) {
            boolean exists = gitRepositoryManager.commitExists(repositoryId, headSha);
            if (!exists && fetched) {
                log.error(
                        "Head commit {} not found in local clone after successful fetch. repoId={}",
                        headSha,
                        repositoryId);
            } else if (!exists) {
                log.warn(
                        "Head commit {} not found locally (no fetch possible). Diff may fail. repoId={}",
                        headSha,
                        repositoryId);
            }
            return exists;
        }
        return false;
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
        result.put("additions", pullRequest.getAdditions());
        result.put("deletions", pullRequest.getDeletions());
        result.put("changed_files", pullRequest.getChangedFiles());
        if (pullRequest.getAuthor() != null) {
            result.put("author", pullRequest.getAuthor().getLogin());
        }

        return result;
    }

    private CommentCapture loadComments(long pullRequestId) {
        var comments = new ArrayList<>(reviewCommentRepository.findRecentByPullRequestIdWithAuthor(
                pullRequestId, PageRequest.of(0, MAX_COMMENTS + 1)));
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
            commentNode.put("path", comment.getPath());
            // line is a primitive int; a file-level comment has no anchor and reports 0. Omit the key then,
            // so an absent anchor reads as absent rather than as a literal line-0 anchor.
            if (comment.getLine() > 0) {
                commentNode.put("line", comment.getLine());
            }
            commentNode.put("body", comment.getBody());
            if (comment.getCreatedAt() != null) {
                commentNode.put("created_at", comment.getCreatedAt().toString());
            }
            if (comment.getAuthor() != null) {
                commentNode.put("author", comment.getAuthor().getLogin());
            }
            commentsArray.add(commentNode);
        }
        return commentsArray;
    }

    private record CommentCapture(List<PullRequestReviewComment> comments, boolean complete) {}

    private static boolean readsClone(Set<SourceKind> selectedKinds) {
        return selectedKinds.contains(CORE) || selectedKinds.contains(DIFF);
    }

    private record ChangeRange(Path repoPath, String base, String head) {}

    private ChangeRange resolveChangeRange(long repositoryId, JsonNode metadata) {
        ensureRepositoryAvailable(repositoryId);
        String headSha = metadata.path("commit_sha").asString("");
        if (headSha.isBlank()) {
            throw new JobPreparationException("Cannot resolve the change range because commit_sha is missing");
        }
        String targetBranch = requireText(metadata, "target_branch");
        String sourceBranch = requireText(metadata, "source_branch");
        Path repoPath = gitRepositoryManager.getRepositoryPath(repositoryId);
        String[] range = gitDiffOperations.resolveDiffRange(repoPath, targetBranch, sourceBranch, headSha);
        if (range == null) {
            String reason = gitRepositoryManager.commitExists(repositoryId, headSha)
                    ? "all resolution strategies failed"
                    : "the pinned head commit is unavailable after repository refresh";
            throw new JobPreparationException("Cannot resolve the change range because " + reason
                    + ". headSha="
                    + headSha
                    + ", targetBranch="
                    + targetBranch
                    + ", sourceBranch="
                    + sourceBranch
                    + ", repoId="
                    + repositoryId);
        }
        return new ChangeRange(repoPath, range[0], range[1]);
    }

    private boolean storeCommits(Map<String, byte[]> files, ChangeRange range, long repositoryId) {
        GitDiffOperations.CommitLog commitLog =
                gitDiffOperations.commitLog(range.repoPath(), range.base(), range.head(), MAX_COMMITS);
        // A null log is a failed read, never an empty history: a resolved range holds at least one commit.
        if (commitLog == null) {
            throw new JobPreparationException("Commit log could not be read for range=" + range.base()
                    + ".."
                    + range.head()
                    + ", repoId="
                    + repositoryId);
        }
        var commits = objectMapper.createArrayNode();
        for (var commit : commitLog.commits()) {
            var node = commits.addObject();
            node.put("sha", commit.sha());
            node.put("subject", commit.subject());
            if (commit.body() != null) {
                node.put("body", commit.body());
            }
            node.put("authored_at", commit.authoredAt().toString());
            node.put("committed_at", commit.committedAt().toString());
            node.put("parent_count", commit.parentCount());
            if (commit.changedFiles() != null) {
                node.put("changed_files", commit.changedFiles());
            }
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.set("commits", commits);
        root.put("truncated", commitLog.truncated());
        try {
            files.put(
                    OUTPUT_PREFIX + "commits.json",
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        } catch (JacksonException e) {
            throw new JobPreparationException("Failed to serialize pull request commits", e);
        }
        log.info(
                "Pre-computed commit log: range={}..{}, commits={}, truncated={}",
                range.base(),
                range.head(),
                commits.size(),
                commitLog.truncated());
        return commitLog.truncated();
    }

    private void computeAndStoreDiff(Map<String, byte[]> files, ChangeRange range, long repositoryId) {
        try {
            String diffStat = gitDiffOperations.diffStat(range.repoPath(), range.base(), range.head());
            String diff = gitDiffOperations.diff(range.repoPath(), range.base(), range.head());
            // A null diff denotes a failed read (unresolved object, I/O error, or the size cap), never an
            // empty diff: storing zero bytes would report a change that was never read as AVAILABLE,
            // EMPTY and COMPLETE.
            if (diff == null) {
                throw new JobPreparationException("Diff could not be read for range=" + range.base()
                        + ".."
                        + range.head()
                        + ", repoId="
                        + repositoryId);
            }
            computeAndStoreDiffSummary(files, diff);
            if (!diff.isBlank()) {
                String annotatedDiff = GitDiffOperations.annotateDiffWithLineNumbers(diff);
                files.put(OUTPUT_PREFIX + "diff.patch", annotatedDiff.getBytes(StandardCharsets.UTF_8));
                if (diffStat != null) {
                    files.put(OUTPUT_PREFIX + "diff_stat.txt", diffStat.getBytes(StandardCharsets.UTF_8));
                }

                int addedLines = 0;
                int removedLines = 0;
                for (String line : diff.split("\n", -1)) {
                    if (line.startsWith("+") && !line.startsWith("+++")) addedLines++;
                    else if (line.startsWith("-") && !line.startsWith("---")) removedLines++;
                }
                log.info(
                        "Pre-computed diff: range={}..{}, +{}/-{} lines, {} bytes (annotated: {} bytes)",
                        range.base(),
                        range.head(),
                        addedLines,
                        removedLines,
                        diff.length(),
                        annotatedDiff.length());
            } else {
                files.put(OUTPUT_PREFIX + "diff.patch", new byte[0]);
                files.put(OUTPUT_PREFIX + "diff_stat.txt", new byte[0]);
                log.info("Pre-computed empty diff: range={}..{}", range.base(), range.head());
            }
        } catch (JobPreparationException e) {
            throw e;
        } catch (Exception e) {
            throw new JobPreparationException("Failed to pre-compute diff: " + e.getMessage(), e);
        }
    }

    void computeAndStoreDiffSummary(Map<String, byte[]> files, String diff) {
        byte[] rawDiff = diff.getBytes(StandardCharsets.UTF_8);
        Patch patch = new Patch();
        patch.parse(rawDiff, 0, rawDiff.length);
        // JGit records recoverable oddities as warnings on the same list; only an ERROR means the
        // headers cannot be trusted to name the files they claim.
        boolean unreadable =
                patch.getErrors().stream().anyMatch(error -> error.getSeverity() == FormatError.Severity.ERROR);
        if (unreadable || (!diff.isBlank() && patch.getFiles().isEmpty())) {
            throw new JobPreparationException("Diff could not be parsed for its summary");
        }

        StringBuilder summary = new StringBuilder("# Diff Summary\n\n");
        summary.append("**")
                .append(patch.getFiles().size())
                .append(patch.getFiles().size() == 1 ? " file changed" : " files changed")
                .append("**\n\n");
        int ordinal = 0;
        for (var file : patch.getFiles()) {
            String path = file.getChangeType() == DiffEntry.ChangeType.DELETE ? file.getOldPath() : file.getNewPath();
            int added = file.getHunks().stream()
                    .flatMap(hunk -> hunk.toEditList().stream())
                    .mapToInt(Edit::getLengthB)
                    .sum();
            summary.append("File ")
                    .append(++ordinal)
                    .append(": +")
                    .append(added)
                    .append(added == 1 ? " line\n\n    " : " lines\n\n    ")
                    .append(objectMapper.writeValueAsString(path))
                    .append("\n\n");
        }

        // Keep one line-annotated copy of the change for citation; this file is only its index, so it
        // carries no [L<n>] markers to quote from.
        summary.append("The change itself is in `diff.patch`, annotated with line numbers for citation.\n");
        files.put(OUTPUT_PREFIX + "diff_summary.md", summary.toString().getBytes(StandardCharsets.UTF_8));
    }
}
