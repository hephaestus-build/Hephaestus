package de.tum.cit.aet.hephaestus.integration.scm.gitlab.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace.GitLabWorkspaceLinkService;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class GitLabNoteReconciliationIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private org.springframework.context.ApplicationEventPublisher events;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private IssueCommentRepository comments;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private PullRequestReviewCommentRepository diffComments;

    @Autowired
    private PullRequestReviewThreadRepository threads;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private TransactionTemplate transactions;

    private Repository repository;
    private Issue issue;
    private PullRequest mr;

    @BeforeEach
    void setUpNotes() {
        var provider = ensureGitLabProvider();
        repository = new Repository();
        repository.setNativeId(4001L);
        repository.setProvider(provider);
        repository.setName("notes");
        repository.setNameWithOwner("team/notes");
        repository.setHtmlUrl("https://gitlab.com/team/notes");
        repository.setVisibility(Repository.Visibility.PRIVATE);
        repository = repositories.save(repository);
        issue = new Issue();
        issue.setProvider(provider);
        issue.setNativeId(4002L);
        issue.setNumber(1);
        issue.setTitle("Issue");
        issue.setState(Issue.State.OPEN);
        issue.setRepository(repository);
        issue = issues.save(issue);
        mr = new PullRequest();
        mr.setProvider(provider);
        mr.setNativeId(4003L);
        mr.setNumber(1);
        mr.setTitle("MR");
        mr.setState(Issue.State.OPEN);
        mr.setRepository(repository);
        mr = pullRequests.save(mr);
    }

    @Test
    void shouldRemoveDeletedIssueNotesIncludingTheLastNoteWithoutTouchingAnotherParent() {
        var deleted = comment(issue, 5001);
        var other = comment(mr, 5002);
        var service = service(request -> Mono.just(page("[]", 0, 1, "")));
        assertThat(service.reconcileParent(1, repository, issue, null).removed())
                .isEqualTo(1);
        assertThat(comments.findById(deleted.getId())).isEmpty();
        assertThat(comments.findById(other.getId())).isPresent();
        // The live-content query used by Heph and evidence reads has no deleted body left to capture.
        assertThat(comments.findRecentHumanByIssueIdWithAuthor(issue.getId(), "hephaestus", PageRequest.of(0, 10)))
                .isEmpty();
    }

    @Test
    void shouldRemoveDeletedGeneralAndDiffMrNotesAndDetachASurvivingReply() {
        var general = comment(mr, 5100);
        var thread = thread(5101);
        var root = diffNote(thread, 5102);
        var reply = diffNote(thread, 5103);
        reply.setInReplyTo(root);
        diffComments.save(reply);
        thread.setRootComment(root);
        threads.save(thread);
        var service = service(request -> Mono.just(page(
                "[{\"id\":5103,\"body\":\"Still here\",\"type\":\"DiffNote\",\"system\":false,\"author\":{\"id\":42}}]",
                1,
                1,
                "")));
        assertThat(service.reconcileParent(1, repository, mr, null).removed()).isEqualTo(2);
        assertThat(comments.findById(general.getId())).isEmpty();
        assertThat(diffComments.findById(root.getId())).isEmpty();
        assertThat(diffComments.findById(reply.getId()).orElseThrow().getInReplyTo())
                .isNull();
        assertThat(threads.findById(thread.getId()).orElseThrow().getRootComment())
                .isNull();
        assertThat(diffComments.findRecentHumanByPullRequestIdWithAuthor(
                        mr.getId(), "hephaestus", PageRequest.of(0, 10)))
                .extracting(PullRequestReviewComment::getId)
                .containsExactly(reply.getId());
        assertThat(service(request -> Mono.just(page("[]", 0, 1, "")))
                        .reconcileParent(1, repository, mr, null)
                        .removed())
                .isEqualTo(1);
        assertThat(threads.findById(thread.getId())).isEmpty();
    }

    @Test
    void shouldDeleteNothingOnAnIncompleteListingAndStillReconcileTheNextParent() {
        var note = comment(issue, 5200);
        var other = comment(mr, 5201);
        AtomicInteger calls = new AtomicInteger();
        var service = service(request -> {
            if (request.url().getPath().contains("merge_requests")) return Mono.just(page("[]", 0, 1, ""));
            return calls.incrementAndGet() == 1
                    ? Mono.just(page("[{\"id\":5202}]", 2, 1, "2"))
                    : Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                            .build());
        });
        var result = service.reconcileRepository(1, repository, null);
        assertThat(result.skipped()).isTrue();
        assertThat(result.removed()).isEqualTo(1);
        assertThat(comments.findById(note.getId())).isPresent();
        assertThat(comments.findById(other.getId())).isEmpty();
    }

    @Test
    void shouldKeepNotesInsertedWhileListingAndRejectChangingCountsAndMissingPaginationProof() {
        var existing = comment(issue, 5300);
        AtomicInteger calls = new AtomicInteger();
        var service = service(request -> {
            if (calls.incrementAndGet() == 1) {
                comment(issue, 5301);
                return Mono.just(page("[{\"id\":5302}]", 2, 1, "2"));
            }
            return Mono.just(page("[{\"id\":5303}]", 3, 2, ""));
        });
        assertThat(service.reconcileParent(1, repository, issue, null).skipped())
                .isTrue();
        assertThat(comments.findById(existing.getId())).isPresent();
        var missingHeaders = service(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("[]")
                .build()));
        assertThat(missingHeaders.reconcileParent(1, repository, issue, null).skipped())
                .isTrue();
        assertThat(comments.findById(existing.getId())).isPresent();
        assertThat(service(request -> {
                            comment(issue, 5304);
                            return Mono.just(page("[]", 0, 1, ""));
                        })
                        .reconcileParent(1, repository, issue, null)
                        .removed())
                .isEqualTo(2);
        assertThat(comments.findByNativeIdAndProviderId(
                        5304L,
                        java.util.Objects.requireNonNull(
                                repository.getProvider().getId())))
                .isPresent();
    }

    @Test
    void shouldKeepEveryCandidateWhenADirectCheckFindsAMissedNoteOrFails() {
        var missing = comment(issue, 5400);
        var stillPresent = comment(issue, 5401);
        var complete = (ExchangeFunction) request -> Mono.just(page("[]", 0, 1, ""));
        var service = service(
                complete,
                request -> Mono.just(ClientResponse.create(
                                request.url().getPath().endsWith("/5401") ? HttpStatus.OK : HttpStatus.NOT_FOUND)
                        .build()));
        assertThat(service.reconcileParent(1, repository, issue, null).skipped())
                .isTrue();
        assertThat(comments.findById(missing.getId())).isPresent();
        assertThat(comments.findById(stillPresent.getId())).isPresent();
        assertThat(service(
                                complete,
                                request -> Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                                        .build()))
                        .reconcileParent(1, repository, issue, null)
                        .skipped())
                .isTrue();
        assertThat(comments.findById(missing.getId())).isPresent();
        assertThat(comments.findById(stillPresent.getId())).isPresent();
    }

    private GitLabNoteReconciliationService service(ExchangeFunction exchange) {
        return service(
                exchange,
                request -> Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).build()));
    }

    private GitLabNoteReconciliationService service(ExchangeFunction exchange, ExchangeFunction confirmation) {
        var tokens = mock(GitLabTokenService.class);
        when(tokens.getAccessToken(1L)).thenReturn("stored-token");
        when(tokens.resolveServerUrl(1L)).thenReturn("https://gitlab.com");
        var links = mock(GitLabWorkspaceLinkService.class);
        when(links.mayWriteRepository(1, repository)).thenReturn(true);
        var properties = new GitLabProperties(
                "https://gitlab.com",
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ZERO,
                Duration.ofMinutes(5));
        return new GitLabNoteReconciliationService(
                issues,
                comments,
                diffComments,
                threads,
                tokens,
                properties,
                transactions,
                WebClient.builder()
                        .exchangeFunction(request -> request.url().getPath().matches(".*/notes/[0-9]+")
                                ? confirmation.exchange(request)
                                : exchange.exchange(request)),
                links,
                events);
    }

    private static ClientResponse page(String body, int total, int page, String next) {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .header("X-Total", String.valueOf(total))
                .header("X-Page", String.valueOf(page))
                .header("X-Next-Page", next)
                .body(body)
                .build();
    }

    private IssueComment comment(Issue parent, long id) {
        var comment = new IssueComment();
        comment.setProvider(repository.getProvider());
        comment.setNativeId(id);
        comment.setIssue(parent);
        comment.setBody("Note " + id);
        comment.setHtmlUrl("https://gitlab.com/note/" + id);
        comment.setAuthorAssociation(AuthorAssociation.NONE);
        comment.setCreatedAt(Instant.now());
        return comments.save(comment);
    }

    private PullRequestReviewThread thread(long id) {
        var thread = new PullRequestReviewThread();
        thread.setProvider(repository.getProvider());
        thread.setNativeId(id);
        thread.setNodeId("discussion-" + id);
        thread.setPath("file.java");
        thread.setPullRequest(mr);
        return threads.save(thread);
    }

    private PullRequestReviewComment diffNote(PullRequestReviewThread thread, long id) {
        var comment = new PullRequestReviewComment();
        comment.setProvider(repository.getProvider());
        comment.setNativeId(id);
        comment.setBody("Diff note " + id);
        comment.setPath("file.java");
        comment.setHtmlUrl("https://gitlab.com/note/" + id);
        comment.setPullRequest(mr);
        comment.setThread(thread);
        comment.setCreatedAt(Instant.now());
        return diffComments.save(comment);
    }
}
