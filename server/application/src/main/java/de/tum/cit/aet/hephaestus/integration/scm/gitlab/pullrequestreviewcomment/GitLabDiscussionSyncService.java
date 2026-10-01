package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabFieldUtils;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabUserLookup;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabPageInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.GitLabIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreview.GitLabReviewReconciler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewthread.GitLabPullRequestReviewThreadProcessor;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;

/**
 * Syncs GitLab merge request discussions via GraphQL API.
 * <p>
 * Unlike the flat note-based sync in {@link de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.GitLabNoteSyncService},
 * this service fetches <b>discussions</b> which preserve:
 * <ul>
 *   <li>Thread structure (discussion = thread of related notes)</li>
 *   <li>Resolution state ({@code resolved}, {@code resolvedBy})</li>
 *   <li>Diff position data ({@code filePath}, {@code newLine}, {@code oldLine}, {@code diffRefs})</li>
 * </ul>
 * <p>
 * Routing logic:
 * <ul>
 *   <li>Discussions where any note has {@code position != null} &rarr; {@link PullRequestReviewThread} + {@link PullRequestReviewComment}</li>
 *   <li>General discussions (no position) &rarr; {@link de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment} (via existing processor)</li>
 * </ul>
 * <p>
 * The same read also serves a merge request webhook, which carries no discussion state; GitLab documents such
 * an event for all threads becoming resolved, and not for every single thread resolved or reopened.
 * {@link #readThreadResolutions} reads outside any transaction and {@link #applyThreadResolutions} records it. A
 * thread's stored resolution is as current as the last whole read of the merge request's discussions, from a
 * webhook or the sync.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabDiscussionSyncService {

    private static final Logger log = LoggerFactory.getLogger(GitLabDiscussionSyncService.class);

    private static final String GET_MR_DISCUSSIONS_DOCUMENT = "GetMergeRequestDiscussions";
    private static final int DISCUSSION_SYNC_PAGE_SIZE = 50;

    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabGraphQlResponseHandler responseHandler;
    private final GitLabPullRequestReviewThreadProcessor threadProcessor;
    private final GitLabPullRequestReviewCommentProcessor reviewCommentProcessor;
    private final GitLabIssueCommentProcessor issueCommentProcessor;
    private final GitLabReviewReconciler reviewReconciler;
    private final GitLabProperties gitLabProperties;

    public GitLabDiscussionSyncService(
            GitLabGraphQlClientProvider graphQlClientProvider,
            GitLabGraphQlResponseHandler responseHandler,
            GitLabPullRequestReviewThreadProcessor threadProcessor,
            GitLabPullRequestReviewCommentProcessor reviewCommentProcessor,
            GitLabIssueCommentProcessor issueCommentProcessor,
            GitLabReviewReconciler reviewReconciler,
            GitLabProperties gitLabProperties) {
        this.graphQlClientProvider = graphQlClientProvider;
        this.responseHandler = responseHandler;
        this.threadProcessor = threadProcessor;
        this.reviewCommentProcessor = reviewCommentProcessor;
        this.issueCommentProcessor = issueCommentProcessor;
        this.reviewReconciler = reviewReconciler;
        this.gitLabProperties = gitLabProperties;
    }

    /**
     * Syncs all discussions for a merge request, routing diff discussions to review
     * threads/comments and general discussions to issue comments. The threads' resolution is recorded only when every
     * page was read ({@link #applyThreadResolutions}).
     *
     * @param scopeId the scope ID for rate limiting
     * @param repository the repository entity
     * @param mrIid the merge request IID (internal ID)
     * @param pr the parent PullRequest entity
     * @return total number of notes synced (diff + general)
     */
    public int syncDiscussionsForMergeRequest(Long scopeId, Repository repository, int mrIid, PullRequest pr) {
        String projectPath = repository.getNameWithOwner();
        String safeContext = sanitizeForLog(projectPath) + "!" + mrIid;
        IdentityProvider provider = repository.getProvider();
        Long providerId = Objects.requireNonNull(provider.getId());

        // Who withdrew an approval by note in this pass: the only approvals a later note re-gives.
        Set<Long> unapproved = new HashSet<>();
        List<ThreadResolution> resolutions = new ArrayList<>();
        // diff notes, general notes, skipped
        int[] totals = new int[3];
        Instant requestedAt = readStart();
        boolean whole = readDiscussions(scopeId, projectPath, mrIid, safeContext, nodes -> {
            for (Map<String, Object> discussionNode : nodes) {
                ThreadResolution resolution = threadResolution(discussionNode);
                if (resolution != null) {
                    resolutions.add(resolution);
                }
                try {
                    int[] result = processDiscussion(
                            discussionNode, pr, repository, provider, providerId, scopeId, unapproved);
                    totals[0] += result[0];
                    totals[1] += result[1];
                    totals[2] += result[2];
                } catch (Exception e) {
                    log.warn(
                            "Error processing discussion: context={}, id={}", safeContext, discussionNode.get("id"), e);
                    totals[2]++;
                }
            }
        });
        if (whole) {
            try {
                applyThreadResolutions(repository, mrIid, new DiscussionRead(requestedAt, resolutions), scopeId);
            } catch (Exception e) {
                log.warn("Error recording discussion resolution: context={}", safeContext, e);
            }
        }
        int totalDiffNotes = totals[0];
        int totalGeneralNotes = totals[1];
        int totalSkipped = totals[2];

        int totalSynced = totalDiffNotes + totalGeneralNotes;
        if (totalSynced > 0 || totalSkipped > 0) {
            log.info(
                    "Synced discussions: context={}, diffNotes={}, generalNotes={}, skipped={}",
                    safeContext,
                    totalDiffNotes,
                    totalGeneralNotes,
                    totalSkipped);
        }

        return totalSynced;
    }

    /**
     * What a read of a diff discussion said: its thread, whose resolution is null where the read did not state it, and
     * who resolved it.
     */
    public record ThreadResolution(
            GitLabPullRequestReviewThreadProcessor.ThreadData thread,
            @Nullable GitLabUserLookup resolvedBy) {}

    /** A whole read of a merge request's diff discussions, begun at {@code requestedAt}. */
    public record DiscussionRead(Instant requestedAt, List<ThreadResolution> threads) {}

    /**
     * Reads whether each diff discussion of a merge request is resolved, and writes nothing.
     *
     * @return the read, or null when GitLab was not read whole to the last page
     */
    public @Nullable DiscussionRead readThreadResolutions(Long scopeId, String projectPath, int mrIid) {
        String safeContext = sanitizeForLog(projectPath) + "!" + mrIid;
        List<ThreadResolution> read = new ArrayList<>();
        Instant requestedAt = readStart();
        boolean whole = readDiscussions(scopeId, projectPath, mrIid, safeContext, nodes -> {
            for (Map<String, Object> discussionNode : nodes) {
                ThreadResolution resolution = threadResolution(discussionNode);
                if (resolution != null) {
                    read.add(resolution);
                }
            }
        });
        return whole ? new DiscussionRead(requestedAt, read) : null;
    }

    /**
     * Records on the stored threads of merge request {@code iid} the resolution {@code read} found, through
     * {@link GitLabPullRequestReviewThreadProcessor#applyDiscussionRead}: not where a later read was recorded, and not
     * for a thread whose resolution the read did not state.
     */
    public void applyThreadResolutions(Repository repository, int iid, DiscussionRead read, Long scopeId) {
        IdentityProvider provider = repository.getProvider();
        Long providerId = Objects.requireNonNull(provider.getId());
        List<GitLabPullRequestReviewThreadProcessor.ThreadData> threads = new ArrayList<>();
        for (ThreadResolution thread : read.threads()) {
            GitLabUserLookup resolvedBy = thread.resolvedBy();
            User resolver = Boolean.TRUE.equals(thread.thread().resolved()) && resolvedBy != null
                    ? issueCommentProcessor.findOrCreateUser(resolvedBy, providerId)
                    : null;
            threads.add(thread.thread().withResolvedBy(resolver));
        }
        threadProcessor.applyDiscussionRead(repository, iid, read.requestedAt(), threads, provider, scopeId);
    }

    /** Now, to the microsecond the database stores, so that a read compares with the one recorded exactly. */
    private static Instant readStart() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * A diff discussion's thread as its node states it: the resolution, and the position of its first note that has
     * one (a reply can omit its own), with no resolver, which only a transaction may look up.
     */
    @SuppressWarnings("unchecked")
    private static GitLabPullRequestReviewThreadProcessor.ThreadData threadData(
            String discussionGlobalId,
            @Nullable Boolean resolved,
            Map<String, Object> discussionNode,
            List<Map<String, Object>> noteNodes) {
        Map<String, Object> rootNote = findRootDiffNote(noteNodes);
        Map<String, Object> rootPosition = rootNote != null ? (Map<String, Object>) rootNote.get("position") : null;

        String filePath = null;
        Integer newLine = null;
        Integer oldLine = null;
        String headSha = null;
        String baseSha = null;
        PullRequestReviewComment.Side threadSide = null;
        Boolean outdated = null;

        if (rootPosition != null) {
            String np = (String) rootPosition.get("newPath");
            String op = (String) rootPosition.get("oldPath");
            String fp = (String) rootPosition.get("filePath");
            filePath = np != null ? np : (op != null ? op : fp);
            newLine = GitLabFieldUtils.toInteger(rootPosition.get("newLine"));
            oldLine = GitLabFieldUtils.toInteger(rootPosition.get("oldLine"));
            threadSide = GitLabPullRequestReviewCommentProcessor.deriveSide(newLine, oldLine);

            Map<String, Object> diffRefs = (Map<String, Object>) rootPosition.get("diffRefs");
            if (diffRefs != null) {
                headSha = (String) diffRefs.get("headSha");
                baseSha = (String) diffRefs.get("baseSha");
                if (baseSha == null) {
                    baseSha = (String) diffRefs.get("startSha");
                }
            }

            // Infer the "outdated" state that GitLab reports for discussions whose diff
            // anchor has been dropped by a later push. A text-position note with a non-null
            // file path but no newLine/oldLine is GitLab's signal that the referenced hunk
            // no longer exists in the head diff — i.e. the discussion is outdated.
            String positionType = (String) rootPosition.get("positionType");
            if ("text".equals(positionType) && filePath != null) {
                outdated = newLine == null && oldLine == null;
            }
        }

        Object resolvedAt = discussionNode.get("resolvedAt");
        return new GitLabPullRequestReviewThreadProcessor.ThreadData(
                discussionGlobalId,
                resolved,
                null,
                filePath,
                newLine,
                oldLine,
                threadSide,
                headSha,
                baseSha,
                outdated,
                parseTimestamp((String) noteNodes.get(0).get("createdAt")),
                parseTimestamp(resolvedAt == null ? null : resolvedAt.toString()));
    }

    /** The resolution a diff discussion's node states; null for a discussion that is not on the diff. */
    @SuppressWarnings("unchecked")
    private static @Nullable ThreadResolution threadResolution(Map<String, Object> discussionNode) {
        String id = discussionNode.get("id") instanceof String value ? value : null;
        Map<String, Object> notes = (Map<String, Object>) discussionNode.get("notes");
        List<Map<String, Object>> noteNodes = notes == null ? null : (List<Map<String, Object>>) notes.get("nodes");
        if (id == null || noteNodes == null || noteNodes.stream().noneMatch(note -> note.get("position") != null)) {
            return null;
        }
        Boolean resolved = discussionNode.get("resolved") instanceof Boolean value ? value : null;
        return new ThreadResolution(
                threadData(id, resolved, discussionNode, noteNodes),
                userLookup((Map<String, Object>) discussionNode.get("resolvedBy")));
    }

    /**
     * Reads the merge request's discussions page by page, handing each page to {@code onPage}.
     *
     * @return whether every page was read whole, to the one that says none follows
     */
    private boolean readDiscussions(
            Long scopeId,
            String projectPath,
            int mrIid,
            String safeContext,
            Consumer<List<Map<String, Object>>> onPage) {
        String cursor = null;
        String previousCursor = null;
        int page = 0;

        try {
            do {
                if (page >= GitLabSyncConstants.MAX_PAGINATION_PAGES) {
                    log.warn("Discussion sync reached max pages: context={}", safeContext);
                    return false;
                }

                graphQlClientProvider.acquirePermission();

                try {
                    graphQlClientProvider.waitIfRateLimitLow(scopeId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Discussion sync interrupted: context={}", safeContext);
                    return false;
                }

                int remaining = graphQlClientProvider.getRateLimitRemaining(scopeId);
                int pageSize = GitLabSyncConstants.adaptPageSize(DISCUSSION_SYNC_PAGE_SIZE, remaining);

                HttpGraphQlClient client = graphQlClientProvider.forScope(scopeId);

                ClientGraphQlResponse response = client.documentName(GET_MR_DISCUSSIONS_DOCUMENT)
                        .variable("fullPath", projectPath)
                        .variable("iid", String.valueOf(mrIid))
                        .variable("first", pageSize)
                        .variable("after", cursor)
                        .execute()
                        .block(gitLabProperties.graphqlTimeout());

                var handleResult = responseHandler.handle(response, "discussions for " + safeContext, log);
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                    continue;
                }
                if (handleResult.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                    graphQlClientProvider.recordFailure(
                            new GitLabSyncException("Invalid GraphQL response: context=" + safeContext));
                    return false;
                }

                graphQlClientProvider.recordSuccess();

                String discussionsPath = "project.mergeRequest.discussions";
                if (!responseHandler.isWholePage(Objects.requireNonNull(response), discussionsPath)) {
                    log.warn("Discussion page not whole: context={}, page={}", safeContext, page);
                    return false;
                }

                @SuppressWarnings("rawtypes")
                List nodesRaw = response.field(discussionsPath + ".nodes").toEntityList(Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> nodes = (List<Map<String, Object>>) nodesRaw;
                if (!nodes.isEmpty()) {
                    onPage.accept(nodes);
                }

                // Pagination
                GitLabPageInfo pageInfo = Objects.requireNonNull(
                        response.field(discussionsPath + ".pageInfo").toEntity(GitLabPageInfo.class));
                if (!pageInfo.hasNextPage()) {
                    return true;
                }
                cursor = pageInfo.endCursor();
                if (cursor == null) {
                    log.warn("Discussion pagination cursor null despite hasNextPage=true: context={}", safeContext);
                    return false;
                }
                if (responseHandler.isPaginationLoop(cursor, previousCursor, "discussions for " + safeContext, log)) {
                    return false;
                }
                previousCursor = cursor;
                page++;

                try {
                    Thread.sleep(gitLabProperties.paginationThrottle().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } while (true);
        } catch (Exception e) {
            graphQlClientProvider.recordFailure(e);
            log.error("Discussion sync failed: context={}", safeContext, e);
            return false;
        }
    }

    /**
     * Processes a single discussion node, routing to diff thread/comment or general comment.
     *
     * @return int[3]: [diffNotes, generalNotes, skipped]
     */
    @SuppressWarnings("unchecked")
    private int[] processDiscussion(
            Map<String, Object> discussionNode,
            PullRequest pr,
            Repository repository,
            IdentityProvider provider,
            Long providerId,
            Long scopeId,
            Set<Long> unapproved) {
        String discussionGlobalId = (String) discussionNode.get("id");
        if (discussionGlobalId == null) {
            return new int[] {0, 0, 1};
        }

        Boolean resolved = (Boolean) discussionNode.get("resolved");

        // Extract notes
        Map<String, Object> notesMap = (Map<String, Object>) discussionNode.get("notes");
        if (notesMap == null) {
            return new int[] {0, 0, 1};
        }

        List<Map<String, Object>> noteNodes = (List<Map<String, Object>>) notesMap.get("nodes");
        if (noteNodes == null || noteNodes.isEmpty()) {
            return new int[] {0, 0, 1};
        }

        // Detect notes truncation (100-note limit per discussion)
        Map<String, Object> notesPageInfo = (Map<String, Object>) notesMap.get("pageInfo");
        if (notesPageInfo != null && Boolean.TRUE.equals(notesPageInfo.get("hasNextPage"))) {
            log.warn(
                    "Discussion has more than 100 notes (truncated): discussionId={}, fetchedNotes={}",
                    discussionGlobalId,
                    noteNodes.size());
        }

        // Check if this is a diff discussion (any note has position)
        boolean isDiffDiscussion = noteNodes.stream().anyMatch(note -> note.get("position") != null);

        if (isDiffDiscussion) {
            return processDiffDiscussion(
                    discussionNode,
                    discussionGlobalId,
                    resolved,
                    noteNodes,
                    pr,
                    repository,
                    provider,
                    providerId,
                    scopeId);
        } else {
            return processGeneralDiscussion(noteNodes, pr, providerId, scopeId, unapproved);
        }
    }

    /**
     * Processes a diff discussion into PullRequestReviewThread + PullRequestReviewComment(s).
     */
    @SuppressWarnings("unchecked")
    private int[] processDiffDiscussion(
            Map<String, Object> discussionNode,
            String discussionGlobalId,
            @Nullable Boolean resolved,
            List<Map<String, Object>> noteNodes,
            PullRequest pr,
            Repository repository,
            IdentityProvider provider,
            Long providerId,
            Long scopeId) {
        int diffNotes = 0;
        Map<String, Object> rootNote = findRootDiffNote(noteNodes);
        Map<String, Object> rootPosition = rootNote != null ? (Map<String, Object>) rootNote.get("position") : null;

        // The thread for the comments; its resolution is recorded from the whole read (applyThreadResolutions).
        var threadData = threadData(discussionGlobalId, resolved, discussionNode, noteNodes);
        PullRequestReviewThread thread = threadProcessor.findOrCreateThread(threadData, pr, provider, scopeId);
        if (thread == null) {
            return new int[] {0, 0, 1};
        }

        // Pre-compute one synthetic COMMENTED review per (author, discussion) so each note
        // below can attach to the matching review without redundant DB lookups.
        Map<Long, PullRequestReview> reviewsByAuthor = reconcileDiscussionReviews(
                noteNodes, discussionGlobalId, pr, repository, provider, providerId, scopeId);

        // Process each note in the discussion as a review comment
        PullRequestReviewComment previousComment = null;
        for (Map<String, Object> noteNode : noteNodes) {
            // Skip system and internal notes within discussions
            if (Boolean.TRUE.equals(noteNode.get("system")) || Boolean.TRUE.equals(noteNode.get("internal"))) {
                continue;
            }

            String noteGlobalId = (String) noteNode.get("id");
            if (noteGlobalId == null) {
                continue;
            }

            // Extract position data for this note. Reply notes in GitLab can omit their
            // own position; fall back to the root diff note's position so path/line/sha
            // are still populated on every comment.
            Map<String, Object> position = (Map<String, Object>) noteNode.get("position");
            Map<String, Object> effectivePosition = position != null ? position : rootPosition;

            String noteFilePath = null;
            Integer noteNewLine = null;
            Integer noteOldLine = null;
            String newPath = null;
            String oldPath = null;
            String noteHeadSha = null;
            String noteBaseSha = null;
            String noteStartSha = null;

            if (effectivePosition != null) {
                noteFilePath = (String) effectivePosition.get("filePath");
                noteNewLine = GitLabFieldUtils.toInteger(effectivePosition.get("newLine"));
                noteOldLine = GitLabFieldUtils.toInteger(effectivePosition.get("oldLine"));
                newPath = (String) effectivePosition.get("newPath");
                oldPath = (String) effectivePosition.get("oldPath");

                Map<String, Object> diffRefs = (Map<String, Object>) effectivePosition.get("diffRefs");
                if (diffRefs != null) {
                    noteHeadSha = (String) diffRefs.get("headSha");
                    noteBaseSha = (String) diffRefs.get("baseSha");
                    noteStartSha = (String) diffRefs.get("startSha");
                }
            }

            // Resolve author
            User author = resolveAuthor(noteNode, providerId);

            var noteData = new GitLabPullRequestReviewCommentProcessor.DiffNoteData(
                    noteGlobalId,
                    (String) noteNode.get("body"),
                    (String) noteNode.get("url"),
                    noteFilePath,
                    noteNewLine,
                    noteOldLine,
                    newPath,
                    oldPath,
                    noteHeadSha,
                    noteBaseSha,
                    noteStartSha,
                    parseTimestamp((String) noteNode.get("createdAt")),
                    parseTimestamp((String) noteNode.get("updatedAt")));

            PullRequestReview review =
                    author != null && author.getNativeId() != null ? reviewsByAuthor.get(author.getNativeId()) : null;

            var commentContext = new GitLabPullRequestReviewCommentProcessor.CommentContext(
                    thread,
                    pr,
                    author,
                    provider,
                    previousComment, // first note has no parent, subsequent notes are replies
                    review,
                    scopeId);
            PullRequestReviewComment comment = reviewCommentProcessor.findOrCreateComment(noteData, commentContext);

            if (comment != null) {
                diffNotes++;
                previousComment = comment;
            }
        }

        return new int[] {diffNotes, 0, 0};
    }

    /**
     * Groups the non-system notes in a discussion by author, finds each author's earliest
     * createdAt, and reconciles one synthetic COMMENTED {@link PullRequestReview} per author.
     * <p>
     * This is the bridge that brings GitLab MR discussions up to GitHub parity: every note
     * author gets a review row that downstream scoring/profile UIs expect.
     *
     * @return map keyed by author native ID to the reconciled review (never null, possibly empty)
     */
    @SuppressWarnings("unchecked")
    private Map<Long, PullRequestReview> reconcileDiscussionReviews(
            List<Map<String, Object>> noteNodes,
            String discussionGlobalId,
            PullRequest pr,
            Repository repository,
            IdentityProvider provider,
            Long providerId,
            Long scopeId) {
        record AuthorEarliest(User author, @Nullable Instant earliest) {}

        Map<Long, AuthorEarliest> byAuthor = new HashMap<>();
        for (Map<String, Object> noteNode : noteNodes) {
            if (Boolean.TRUE.equals(noteNode.get("system")) || Boolean.TRUE.equals(noteNode.get("internal"))) {
                continue;
            }
            User author = resolveAuthor(noteNode, providerId);
            if (author == null || author.getNativeId() == null) {
                continue;
            }
            Instant createdAt = parseTimestamp((String) noteNode.get("createdAt"));
            byAuthor.merge(author.getNativeId(), new AuthorEarliest(author, createdAt), (existing, incoming) -> {
                if (existing.earliest() == null) return incoming;
                if (incoming.earliest() == null) return existing;
                return incoming.earliest().isBefore(existing.earliest()) ? incoming : existing;
            });
        }

        // Emit REVIEW_COMMENTED events during bulk GraphQL sync so the activity ledger records
        // COMMENTED reviews. Without a ProcessingContext the review reconciler silently skips
        // event publication.
        ProcessingContext ctx = repository != null ? ProcessingContext.forSync(scopeId, repository) : null;

        Map<Long, PullRequestReview> result = new HashMap<>();
        for (Map.Entry<Long, AuthorEarliest> entry : byAuthor.entrySet()) {
            AuthorEarliest info = entry.getValue();
            PullRequestReview review = reviewReconciler.findOrCreateCommentedReview(
                    pr, info.author(), discussionGlobalId, info.earliest(), provider, ctx);
            if (review != null) {
                result.put(entry.getKey(), review);
            }
        }
        return result;
    }

    /**
     * Processes a general discussion into IssueComment(s) via the existing processor.
     */
    private int[] processGeneralDiscussion(
            List<Map<String, Object>> noteNodes, PullRequest pr, Long providerId, Long scopeId, Set<Long> unapproved) {
        int generalNotes = 0;

        for (Map<String, Object> noteNode : noteNodes) {
            // A system note is not a comment, but the ones that record a review decision are the only
            // place GitLab says who approved, withdrew an approval or requested changes, and when.
            if (Boolean.TRUE.equals(noteNode.get("system"))) {
                recordReviewDecisionFromSystemNote(noteNode, pr, providerId, unapproved);
                continue;
            }
            if (Boolean.TRUE.equals(noteNode.get("internal"))) {
                continue;
            }

            String globalId = (String) noteNode.get("id");
            if (globalId == null) {
                continue;
            }

            try {
                long noteId = GitLabSyncConstants.extractNumericId(globalId);
                @SuppressWarnings("unchecked")
                Map<String, Object> authorMap = (Map<String, Object>) noteNode.get("author");

                var syncData = new GitLabIssueCommentProcessor.SyncNoteData(
                        noteId,
                        (String) noteNode.get("body"),
                        (String) noteNode.get("url"),
                        authorMap != null ? (String) authorMap.get("id") : null,
                        authorMap != null ? (String) authorMap.get("username") : null,
                        authorMap != null ? (String) authorMap.get("name") : null,
                        authorMap != null ? (String) authorMap.get("avatarUrl") : null,
                        authorMap != null ? (String) authorMap.get("webUrl") : null,
                        GitLabUserLookup.botOf(authorMap),
                        noteNode.get("createdAt") != null
                                ? noteNode.get("createdAt").toString()
                                : null,
                        noteNode.get("updatedAt") != null
                                ? noteNode.get("updatedAt").toString()
                                : null);

                if (issueCommentProcessor.processFromSync(syncData, pr, providerId, scopeId) != null) {
                    generalNotes++;
                }
            } catch (Exception e) {
                log.warn("Error processing general note: gid={}", globalId, e);
            }
        }

        return new int[] {0, generalNotes, 0};
    }

    /**
     * @param unapproved the authors whose "unapproved" note this pass has already seen; an "approved"
     *     note by one of them gives the approval again
     */
    void recordReviewDecisionFromSystemNote(
            Map<String, Object> noteNode, PullRequest pr, Long providerId, Set<Long> unapproved) {
        String body = String.valueOf(noteNode.get("body"));
        String noteGlobalId = (String) noteNode.get("id");
        Instant at = parseTimestamp((String) noteNode.get("createdAt"));
        if (noteGlobalId == null || at == null || !GitLabReviewReconciler.isReviewDecision(body)) {
            return;
        }
        User author = resolveAuthor(noteNode, providerId);
        if (author == null) {
            return;
        }
        reviewReconciler.recordSystemNote(
                pr,
                author,
                new GitLabReviewReconciler.SystemNote(body, at, noteGlobalId, false),
                pr.getProvider(),
                unapproved);
    }

    /**
     * Returns the first note in the discussion that carries a non-null {@code position}.
     * Used to source thread-level metadata (path, line, side, SHAs) when the leading
     * note is a reply without its own position.
     */
    @Nullable
    private static Map<String, Object> findRootDiffNote(List<Map<String, Object>> noteNodes) {
        for (Map<String, Object> note : noteNodes) {
            if (note.get("position") != null) {
                return note;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private User resolveAuthor(Map<String, Object> noteNode, Long providerId) {
        GitLabUserLookup author = userLookup((Map<String, Object>) noteNode.get("author"));
        return author == null ? null : issueCommentProcessor.findOrCreateUser(author, providerId);
    }

    private static @Nullable GitLabUserLookup userLookup(@Nullable Map<String, Object> user) {
        if (user == null) {
            return null;
        }
        return GitLabUserLookup.of(
                (String) user.get("id"),
                (String) user.get("username"),
                (String) user.get("name"),
                (String) user.get("avatarUrl"),
                (String) user.get("webUrl"),
                GitLabUserLookup.botOf(user));
    }

    @Nullable
    static Instant parseTimestamp(@Nullable String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            log.warn("Could not parse timestamp: value={}", value);
            return null;
        }
    }
}
