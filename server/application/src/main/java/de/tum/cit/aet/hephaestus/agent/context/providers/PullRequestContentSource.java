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
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.NativeGitExecutor.RepositoryKey;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
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
        if (path.endsWith("diff.patch")
                || path.endsWith("diff_stat.txt")
                || path.endsWith("diff_summary.md")
                || path.endsWith("diff_paths.nul")) return DIFF;
        return CORE;
    }

    private static final Logger log = LoggerFactory.getLogger(PullRequestContentSource.class);

    static final int MAX_COMMENTS = EvidenceLimits.MAX_ITEMS_PER_SOURCE;

    private final ObjectMapper objectMapper;
    private final GitRepositoryManager gitRepositoryManager;
    private final PullRequestRepository pullRequestRepository;
    private final PullRequestReviewCommentRepository reviewCommentRepository;
    private final GitDiffOperations gitDiffOperations;
    private final ReviewRepositoryPreparer repositoryPreparer;

    public PullRequestContentSource(
            ObjectMapper objectMapper,
            GitRepositoryManager gitRepositoryManager,
            PullRequestRepository pullRequestRepository,
            PullRequestReviewCommentRepository reviewCommentRepository,
            GitDiffOperations gitDiffOperations,
            ReviewRepositoryPreparer repositoryPreparer) {
        this.objectMapper = objectMapper;
        this.gitRepositoryManager = gitRepositoryManager;
        this.pullRequestRepository = pullRequestRepository;
        this.reviewCommentRepository = reviewCommentRepository;
        this.gitDiffOperations = gitDiffOperations;
        this.repositoryPreparer = repositoryPreparer;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof ContextRequest.PracticeReviewRequest;
    }

    @Override
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        throw new UnsupportedOperationException("Pull request diffs are staged from disk; use capture()");
    }

    @Override
    public void contributeSelected(ContextRequest request, Set<SourceKind> selectedKinds, Map<String, byte[]> files) {
        if (readsClone(selectedKinds))
            throw new UnsupportedOperationException("Repository capture requires disk-backed inputs");
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
        boolean prepareGit = readsClone(selectedKinds) && gitRepositoryManager.isEnabled();
        if (!pullRequestRepository.existsByIdAndDeletedAtIsNull(pullRequestId)) {
            return EvidenceContribution.unavailable(selectedKinds, SourceAbsenceReason.NOT_FOUND);
        }
        ReviewRepositoryPreparer.PreparedReview prepared = null;
        if (prepareGit) {
            prepared = repositoryPreparer.prepare(job);
        } else {
            repositoryPreparer.authorize(job);
        }
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

        if (readsClone(selectedKinds)) {
            ensureRepositoryAvailable(new RepositoryKey(job.getWorkspace().getId(), repositoryId));
        }
        if (selectedKinds.contains(CORE)) {
            storeMetadata(files, pullRequest, metadata);
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
        if (readsClone(selectedKinds)) {
            var key = new RepositoryKey(job.getWorkspace().getId(), repositoryId);
            String[] range = resolveChangeRange(key, prepared);
            Map<String, Path> onDisk = new HashMap<>();
            List<java.io.Closeable> captures = new ArrayList<>();
            try {
                if (selectedKinds.contains(CORE)) {
                    var commits = gitDiffOperations.captureCommits(key, range[0], range[1]);
                    captures.add(commits);
                    onDisk.put(OUTPUT_PREFIX + "commits.json", commits.path());
                    identities.put(CORE, range[0] + ":" + range[1]);
                }
                if (selectedKinds.contains(DIFF)) {
                    var diff = gitDiffOperations.capture(key, range[0], range[1]);
                    captures.add(diff);
                    completeness.put(DIFF, SourceCompleteness.COMPLETE);
                    identities.put(DIFF, range[0] + ":" + range[1]);
                    contentStates.put(DIFF, diff.isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY);
                    diff.files().forEach((name, path) -> onDisk.put(OUTPUT_PREFIX + name, path));
                }
                return new EvidenceContribution(
                        files,
                        completeness,
                        identities,
                        observedAt,
                        Map.of(),
                        contentStates,
                        Map.of(),
                        onDisk,
                        () -> org.apache.commons.io.IOUtils.close(captures.toArray(java.io.Closeable[]::new)),
                        Map.of());
            } catch (java.io.IOException | RuntimeException exception) {
                try {
                    org.apache.commons.io.IOUtils.close(captures.toArray(java.io.Closeable[]::new));
                } catch (java.io.IOException cleanup) {
                    exception.addSuppressed(cleanup);
                }
                throw new JobPreparationException("Could not stage reviewed change", exception);
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

    private String[] resolveChangeRange(
            RepositoryKey repository, ReviewRepositoryPreparer.@Nullable PreparedReview prepared) {
        if (prepared == null) throw new JobPreparationException("Repository evidence requires Git preparation");
        String[] range = gitDiffOperations.resolveDiffRange(repository, prepared.target(), prepared.head());
        if (range == null) throw new JobPreparationException("The pinned review diff range is unavailable");
        return range;
    }
}
