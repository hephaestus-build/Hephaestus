package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreview;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.GitLabIssueCommentProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabPullRequestReviewCommentProcessor;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.RecordingScmEventListener;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class GitLabDetachedSyncIntegrationTest extends BaseIntegrationTest {
    private static final Instant NOTE_AT = Instant.parse("2026-01-03T10:11:12Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private UserRepository users;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private PullRequestReviewRepository reviews;

    @Autowired
    private IssueCommentRepository comments;

    @Autowired
    private PullRequestReviewCommentRepository diffComments;

    @Autowired
    private PullRequestReviewThreadRepository threads;

    @Autowired
    private GitLabPullRequestReviewCommentProcessor diffCommentProcessor;

    @Autowired
    private GitLabReviewReconciler reconciler;

    @Autowired
    private GitLabIssueCommentProcessor commentProcessor;

    @Autowired
    private RecordingScmEventListener events;

    private record Fixture(
            long workspaceId,
            Repository repository,
            IdentityProvider provider,
            long providerId,
            User author,
            long parentId) {}

    private Fixture fixture(boolean pullRequest) {
        String unique = UUID.randomUUID().toString();
        var workspace = new Workspace();
        workspace.setWorkspaceSlug("detached-" + unique);
        workspace.setDisplayName("Detached sync");
        workspace.setAccountLogin("detached");
        workspace.setAccountType(AccountType.ORG);
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace = workspaces.saveAndFlush(workspace);
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://" + unique + ".example.com"));
        long providerId = Objects.requireNonNull(provider.getId());
        long repositoryId =
                Objects.requireNonNull(jdbc.queryForObject("""
                    INSERT INTO repository(native_id,provider_id,name,name_with_owner,is_private,is_archived,is_disabled,html_url,pushed_at,visibility,default_branch)
                    VALUES (1,?,'repo',?,false,false,false,'https://example.com/repo',now(),'PUBLIC','main') RETURNING id
                    """, Long.class, provider.getId(), "detached/" + unique));
        long authorId = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO \"user\"(native_id,provider_id,login,type) VALUES" + " (2,?,?,'USER') RETURNING id",
                Long.class,
                provider.getId(),
                "reviewer-" + unique));
        long ownerId = Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO \"user\"(native_id,provider_id,login,type) VALUES" + " (4,?,?,'USER') RETURNING id",
                Long.class,
                providerId,
                "owner-" + unique));
        long parentId = Objects.requireNonNull(jdbc.queryForObject(
                """
                INSERT INTO issue(issue_type,native_id,provider_id,repository_id,number,
                    author_id,created_at,state,comments_count,is_locked,is_merged,title,is_draft,additions,deletions,changed_files,commits)
                VALUES (?,3,?,?,3,?,?,'OPEN',0,false,false,'Work',false,0,0,0,0) RETURNING id
                """,
                Long.class,
                pullRequest ? "PULL_REQUEST" : "ISSUE",
                provider.getId(),
                repositoryId,
                ownerId,
                Timestamp.from(NOTE_AT)));
        return new Fixture(
                workspace.getId(),
                repositories.findById(repositoryId).orElseThrow(),
                provider,
                providerId,
                users.findById(authorId).orElseThrow(),
                parentId);
    }

    private PullRequest detachedPullRequest(Fixture fixture) {
        return Objects.requireNonNull(new TransactionTemplate(transactions)
                .execute(status -> pullRequests.findById(fixture.parentId()).orElseThrow()));
    }

    @Test
    void shouldCommitRequestedChangesAndPreserveUnchangedReplayWhenParentTransactionEnded() {
        Fixture fixture = fixture(true);
        PullRequest parent = detachedPullRequest(fixture);
        assertThat(Hibernate.isInitialized(parent.getReviews())).isFalse();
        String noteId = "gid://gitlab/Note/" + fixture.parentId();
        var note = new GitLabReviewReconciler.SystemNote("requested changes", NOTE_AT, noteId, false);

        assertThat(reconciler.recordSystemNote(parent, fixture.author(), note, fixture.provider()))
                .isTrue();
        long nativeId = GitLabReviewReconciler.generateChangesRequestedNativeId(noteId, 2L);
        var first = reviews.findByNativeIdAndProviderId(nativeId, fixture.providerId())
                .orElseThrow();
        assertThat(Objects.requireNonNull(first.getPullRequest()).getId()).isEqualTo(fixture.parentId());
        assertThat(first.getSubmittedAt()).isEqualTo(NOTE_AT);
        Instant updatedAt = first.getUpdatedAt();
        String rowVersion = jdbc.queryForObject(
                "SELECT xmin::text FROM pull_request_review WHERE id=?", String.class, first.getId());
        assertThat(reconciler.recordSystemNote(
                        detachedPullRequest(fixture), fixture.author(), note, fixture.provider()))
                .isFalse();
        assertThat(reviews.findByNativeIdAndProviderId(nativeId, fixture.providerId())
                        .orElseThrow()
                        .getUpdatedAt())
                .isEqualTo(updatedAt);
        assertThat(jdbc.queryForObject(
                        "SELECT xmin::text FROM pull_request_review WHERE id=?", String.class, first.getId()))
                .isEqualTo(rowVersion);
        var corrected =
                new GitLabReviewReconciler.SystemNote("requested changes", NOTE_AT.minusSeconds(1), noteId, false);
        assertThat(reconciler.recordSystemNote(
                        detachedPullRequest(fixture), fixture.author(), corrected, fixture.provider()))
                .isFalse();
        assertThat(reviews.findByNativeIdAndProviderId(nativeId, fixture.providerId())
                        .orElseThrow()
                        .getSubmittedAt())
                .isEqualTo(corrected.at());
    }

    @Test
    void shouldCommitDiscussionStarterAndItsEventWhenParentTransactionEnded() {
        Fixture fixture = fixture(true);
        PullRequest parent = detachedPullRequest(fixture);
        assertThat(Hibernate.isInitialized(parent.getReviews())).isFalse();
        String discussionId = "detached-discussion-" + fixture.parentId();

        var review = reconciler.findOrCreateCommentedReview(
                parent,
                fixture.author(),
                discussionId,
                NOTE_AT,
                fixture.provider(),
                ProcessingContext.forSync(fixture.workspaceId(), fixture.repository()));

        assertThat(review).isNotNull();
        var stored = reviews.findByNativeIdAndProviderId(
                        GitLabReviewReconciler.generateCommentedReviewNativeId(discussionId, 2L), fixture.providerId())
                .orElseThrow();
        assertThat(Objects.requireNonNull(stored.getPullRequest()).getId()).isEqualTo(fixture.parentId());
        assertThat(events.ofType(ScmDomainEvent.ReviewSubmitted.class))
                .anyMatch(event -> event.review().id().equals(stored.getId())
                        && event.review().pullRequestId().equals(fixture.parentId())
                        && Objects.equals(event.context().scopeId(), fixture.workspaceId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldCommitSyncedCommentAndRepositoryEventWhenParentTransactionEnded(boolean pullRequest) {
        Fixture fixture = fixture(pullRequest);
        Issue parent = Objects.requireNonNull(new TransactionTemplate(transactions)
                .execute(status -> issues.findById(fixture.parentId()).orElseThrow()));
        assertThat(Hibernate.isInitialized(parent.getRepository())).isFalse();
        var note = new GitLabIssueCommentProcessor.SyncNoteData(
                fixture.parentId(),
                "Captured note",
                null,
                "gid://gitlab/User/2",
                fixture.author().getLogin(),
                null,
                null,
                null,
                false,
                NOTE_AT.toString(),
                NOTE_AT.toString());

        var result = commentProcessor.processFromSync(note, parent, fixture.providerId(), fixture.workspaceId());

        assertThat(result).isNotNull();
        assertThat(comments.findByNativeIdAndProviderId(note.id(), fixture.providerId()))
                .isPresent();
        assertThat(events.ofType(ScmDomainEvent.CommentCreated.class)).anyMatch(event -> {
            var repository = event.context().repository();
            return event.issueId().equals(fixture.parentId())
                    && repository != null
                    && repository.id().equals(fixture.repository().getId());
        });
    }

    @Test
    void shouldCommitDiffNoteWhenReviewAndParentTransactionsEnded() {
        Fixture fixture = fixture(true);
        PullRequest parent = detachedPullRequest(fixture);
        var created = Objects.requireNonNull(reconciler.findOrCreateCommentedReview(
                parent, fixture.author(), "detached-diff-" + fixture.parentId(), NOTE_AT, fixture.provider(), null));
        var review = Objects.requireNonNull(new TransactionTemplate(transactions)
                .execute(status -> reviews.findById(created.getId()).orElseThrow()));
        assertThat(Hibernate.isInitialized(review.getComments())).isFalse();
        assertThat(Hibernate.isInitialized(parent.getRepository())).isFalse();
        var thread = new PullRequestReviewThread();
        thread.setNativeId(fixture.parentId());
        thread.setProvider(fixture.provider());
        thread.setPullRequest(parent);
        thread.setCreatedAt(NOTE_AT);
        thread.setUpdatedAt(NOTE_AT);
        thread = threads.saveAndFlush(thread);
        var data = new GitLabPullRequestReviewCommentProcessor.DiffNoteData(
                "gid://gitlab/DiffNote/" + fixture.parentId(),
                "Captured diff note",
                null,
                "File.swift",
                1,
                null,
                "File.swift",
                null,
                "a".repeat(40),
                "b".repeat(40),
                null,
                NOTE_AT,
                NOTE_AT);
        var context = new GitLabPullRequestReviewCommentProcessor.CommentContext(
                thread, parent, fixture.author(), fixture.provider(), null, review, fixture.workspaceId(), true);

        var result = diffCommentProcessor.findOrCreateComment(data, context);

        assertThat(result).isNotNull();
        var stored = diffComments
                .findByNativeIdAndProviderId(fixture.parentId(), fixture.providerId())
                .orElseThrow();
        assertThat(Objects.requireNonNull(stored.getReview()).getId()).isEqualTo(review.getId());
        assertThat(events.ofType(ScmDomainEvent.ReviewCommentCreated.class))
                .anyMatch(event -> event.comment().id().equals(stored.getId())
                        && event.pullRequestId().equals(fixture.parentId()));
    }
}
