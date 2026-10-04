package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncPhase;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncProgress;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NoteIdProjection;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceLinkService;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Per-parent deletion proof. REST lists every MR note, including DiffNote, without the nested
 * GraphQL discussion-note cap. A missing count/header, changing count, duplicate, cancellation,
 * page cap, or any failed call leaves that parent's mirror untouched.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true")
public class GitLabNoteReconciliationService {
    private final GitLabWorkspaceLinkService workspaceLinks;
    private final IssueRepository issues;
    private final IssueCommentRepository comments;
    private final PullRequestReviewCommentRepository diffComments;
    private final PullRequestReviewThreadRepository threads;
    private final GitLabTokenService tokens;
    private final GitLabProperties properties;
    private final TransactionTemplate transactions;
    private final WebClient client;
    private final ApplicationEventPublisher events;

    public GitLabNoteReconciliationService(
            IssueRepository issues,
            IssueCommentRepository comments,
            PullRequestReviewCommentRepository diffComments,
            PullRequestReviewThreadRepository threads,
            GitLabTokenService tokens,
            GitLabProperties properties,
            TransactionTemplate transactions,
            WebClient.Builder builder,
            GitLabWorkspaceLinkService workspaceLinks,
            ApplicationEventPublisher events) {
        this.workspaceLinks = workspaceLinks;
        this.events = events;
        this.issues = issues;
        this.comments = comments;
        this.diffComments = diffComments;
        this.threads = threads;
        this.tokens = tokens;
        this.properties = properties;
        this.transactions = transactions;
        this.client = builder.build();
    }

    public record Outcome(int removed, boolean skipped) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Note(long id) {}

    public Outcome reconcileRepository(long workspaceId, Repository repository, @Nullable SyncExecutionHandle handle) {
        if (!workspaceLinks.mayWriteRepository(workspaceId, repository)) return new Outcome(0, true);
        int removed = 0;
        boolean skipped = false;
        // Traverse local live parents, not just the incremental updated-at window: deleting a note
        // need not update its parent. Keyset pagination also tolerates parent inserts during this pass.
        long after = 0;
        while (!cancelled(handle)) {
            List<Issue> parents = issues.findLiveNoteParents(repository.getId(), after, PageRequest.of(0, 100));
            if (parents.isEmpty()) break;
            for (Issue parent : parents) {
                if (cancelled(handle)) return new Outcome(removed, true);
                Outcome outcome = reconcileParent(workspaceId, repository, parent, handle);
                removed += outcome.removed();
                skipped |= outcome.skipped();
                after = parent.getId();
            }
        }
        return new Outcome(removed, skipped);
    }

    public Outcome reconcileParent(
            long workspaceId, Repository repository, Issue parent, @Nullable SyncExecutionHandle handle) {
        if (parent.getRepository() == null
                || !parent.getRepository().getId().equals(repository.getId())
                || !workspaceLinks.mayWriteRepository(workspaceId, repository)) return new Outcome(0, true);
        // Capture candidates BEFORE the first network call. Never remove a concurrently inserted note.
        var localComments = comments.findNoteIdsByParentId(parent.getId());
        var localDiff = parent instanceof PullRequest
                ? diffComments.findNoteIdsByParentId(parent.getId())
                : List.<NoteIdProjection>of();
        var localThreads = parent instanceof PullRequest
                ? threads.findRecentIdsByPullRequestId(parent.getId(), Pageable.unpaged())
                : List.<Long>of();
        if (localComments.isEmpty() && localDiff.isEmpty() && localThreads.isEmpty()) return new Outcome(0, false);
        try {
            Set<Long> upstream = listNotes(
                    workspaceId,
                    repository.getNameWithOwner(),
                    parent.getNumber(),
                    parent instanceof PullRequest,
                    handle);
            if (upstream == null || cancelled(handle)) return new Outcome(0, true);
            List<Long> staleComments = localComments.stream()
                    .filter(c -> !upstream.contains(c.getNativeId()))
                    .map(c -> c.getId())
                    .toList();
            List<Long> staleDiff = localDiff.stream()
                    .filter(c -> !upstream.contains(c.getNativeId()))
                    .map(c -> c.getId())
                    .toList();
            // Offset pages can shift after a deletion plus an insertion without changing X-Total.
            // A direct 404 proves each candidate is absent, rather than just missed by pagination.
            List<Long> missingNativeIds = Stream.concat(
                            localComments.stream()
                                    .filter(c -> !upstream.contains(c.getNativeId()))
                                    .map(c -> c.getNativeId()),
                            localDiff.stream()
                                    .filter(c -> !upstream.contains(c.getNativeId()))
                                    .map(c -> c.getNativeId()))
                    .distinct()
                    .toList();
            if (!confirmMissingNotes(
                    workspaceId,
                    repository.getNameWithOwner(),
                    parent.getNumber(),
                    parent instanceof PullRequest,
                    missingNativeIds,
                    handle)) return new Outcome(0, true);
            Integer removed = transactions.execute(status -> {
                if (cancelled(handle) || !workspaceLinks.mayWriteRepository(workspaceId, repository)) return 0;
                int count = 0;
                if (!staleComments.isEmpty()) count += comments.deleteReconciled(parent.getId(), staleComments);
                if (!staleDiff.isEmpty()) {
                    threads.clearDeletedRoots(parent.getId(), staleDiff);
                    diffComments.clearDeletedReplies(parent.getId(), staleDiff);
                    count += diffComments.deleteReconciled(parent.getId(), staleDiff);
                }
                if (!localThreads.isEmpty()) threads.deleteEmptyReconciled(parent.getId(), localThreads);
                if (count > 0) {
                    for (long id : staleComments)
                        events.publishEvent(new ScmDomainEvent.CommentDeleted(
                                id,
                                parent.getId(),
                                EventContext.forSync(
                                        workspaceId,
                                        Objects.requireNonNull(RepositoryRef.from(repository)),
                                        IdentityProviderType.GITLAB)));
                    for (long id : staleDiff)
                        events.publishEvent(new ScmDomainEvent.ReviewCommentDeleted(
                                id,
                                parent.getId(),
                                EventContext.forSync(
                                        workspaceId,
                                        Objects.requireNonNull(RepositoryRef.from(repository)),
                                        IdentityProviderType.GITLAB)));
                }
                return count;
            });
            return new Outcome(Objects.requireNonNull(removed), false);
        } catch (RuntimeException e) {
            log.warn("GitLab note reconciliation incomplete: workspaceId={}, parentId={}", workspaceId, parent.getId());
            return new Outcome(0, true);
        }
    }

    private @Nullable Set<Long> listNotes(
            long workspaceId, String projectPath, int iid, boolean mergeRequest, @Nullable SyncExecutionHandle handle) {
        Set<Long> ids = new HashSet<>();
        Long total = null;
        String token = tokens.getAccessToken(workspaceId);
        String url = tokens.resolveServerUrl(workspaceId) + "/api/v4/projects/{project}/"
                + (mergeRequest ? "merge_requests" : "issues") + "/{iid}/notes";
        for (int page = 1; page <= GitLabSyncConstants.MAX_PAGINATION_PAGES; page++) {
            if (cancelled(handle) || Thread.currentThread().isInterrupted()) return null;
            var response = client.get()
                    .uri(url + "?per_page=100&page={page}&order_by=created_at&sort=asc", projectPath, iid, page)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, workspaceId)
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<List<Note>>() {})
                    .block(properties.graphqlTimeout());
            if (handle != null) {
                handle.progress(
                        null,
                        null,
                        SyncProgress.ofResource(
                                SyncPhase.SWEEP,
                                "Checking notes on " + projectPath + (mergeRequest ? "!" : "#") + iid + ", page "
                                        + page,
                                projectPath,
                                null,
                                null));
            }
            if (response == null || response.getBody() == null) return null;
            HttpHeaders headers = response.getHeaders();
            String next = headers.getFirst("X-Next-Page");
            String count = headers.getFirst("X-Total");
            String current = headers.getFirst("X-Page");
            if (next == null || count == null || current == null || Integer.parseInt(current) != page) return null;
            long observedTotal = Long.parseLong(count);
            if (observedTotal < 0 || (total != null && total != observedTotal)) return null;
            total = observedTotal;
            for (Note note : response.getBody()) {
                if (note.id() <= 0 || !ids.add(note.id())) return null;
            }
            if (next.isBlank()) return ids.size() == total ? ids : null;
            if (Integer.parseInt(next) != page + 1 || response.getBody().isEmpty()) return null;
        }
        return null;
    }

    private boolean confirmMissingNotes(
            long workspaceId,
            String projectPath,
            int iid,
            boolean mergeRequest,
            List<Long> nativeIds,
            @Nullable SyncExecutionHandle handle) {
        String token = tokens.getAccessToken(workspaceId);
        String url = tokens.resolveServerUrl(workspaceId) + "/api/v4/projects/{project}/"
                + (mergeRequest ? "merge_requests" : "issues") + "/{iid}/notes/{note}";
        for (Long nativeId : nativeIds) {
            if (nativeId == null
                    || nativeId <= 0
                    || cancelled(handle)
                    || Thread.currentThread().isInterrupted()) return false;
            Integer status = client.get()
                    .uri(url, projectPath, iid, nativeId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .attribute(GitLabGraphQlClientProvider.SCOPE_ID_ATTRIBUTE, workspaceId)
                    .exchangeToMono(response -> response.releaseBody()
                            .thenReturn(response.statusCode().value()))
                    .block(properties.graphqlTimeout());
            if (status == null || status != 404) return false;
            if (handle != null)
                handle.progress(
                        null,
                        null,
                        SyncProgress.ofResource(
                                SyncPhase.SWEEP,
                                "Confirming missing notes on " + projectPath + (mergeRequest ? "!" : "#") + iid,
                                projectPath,
                                null,
                                null));
        }
        return true;
    }

    private static boolean cancelled(@Nullable SyncExecutionHandle handle) {
        return handle != null && handle.isCancellationRequested();
    }
}
